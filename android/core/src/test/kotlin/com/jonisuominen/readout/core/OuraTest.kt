package com.jonisuominen.readout.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class OuraTest {
    private class MemoryTokens(var tokens: OuraTokens? = null) : OuraTokenStoring {
        var failLoad = false
        var failDelete = false
        var saves = 0
        override fun load(): OuraTokens? {
            if (failLoad) throw OuraException(OuraError.SECURE_STORAGE)
            return tokens
        }
        override fun save(tokens: OuraTokens) {
            saves++
            this.tokens = tokens
        }
        override fun delete() {
            if (failDelete) throw OuraException(OuraError.SECURE_STORAGE)
            tokens = null
        }
    }

    private class Recorder(val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = ArrayList<HttpRequest>()
        override suspend fun send(request: HttpRequest): HttpResponse {
            synchronized(requests) { requests += request }
            return handler(request)
        }
        fun count(part: String) = synchronized(requests) { requests.count { it.url.contains(part) } }
    }

    private class FakeAuthenticator : OuraAuthenticating {
        var cancelled = 0
        var grant: OuraAuthorizationGrant? = OuraAuthorizationGrant("code-1", "verifier-1")
        override suspend fun authenticate(configuration: OuraPublicConfiguration): OuraAuthorizationGrant =
            grant ?: throw OuraException(OuraError.CANCELLED)
        override suspend fun cancel() {
            cancelled++
        }
    }

    private val configuration = OuraConfiguration("https://broker.example")
    private val clock = MutableClock()

    private fun json(text: String, status: Int = 200) = HttpResponse(status, text.toByteArray())
    private fun tokenJSON(access: String, refresh: String? = "refresh-2") =
        """{"access_token":"$access",${refresh?.let { "\"refresh_token\":\"$it\"," } ?: ""}"expires_in":3600,"token_type":"Bearer","scope":"daily"}"""
    private val emptyPage = """{"data":[],"next_token":null}"""
    private fun validTokens(access: String = "access-1", expiresInMinutes: Long = 60) =
        OuraTokens(access, "refresh-1", baseNow.plusMinutes(expiresInMinutes), "daily")

    private fun connector(transport: HttpTransport, tokens: MemoryTokens, authenticator: OuraAuthenticating? = null, config: OuraConfiguration = configuration) =
        OuraApiConnector(config, transport, tokens, authenticator, clock) { utc }

    private suspend fun expectError(expected: OuraError, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: OuraException) {
            assertEquals(expected, error.error)
        }
    }

    @Test
    fun configurationRejectsPlainHTTPAndUnresolvedBuildSettings() {
        assertTrue(OuraConfiguration("https://broker.example").isConfigured)
        assertTrue(OuraConfiguration(" https://broker.example/base ").isConfigured)
        for (bad in listOf(null, "", "http://broker.example", "https://", "https://user:pw@broker.example", "https://broker.example?x=1", "https://broker.example#f", "https://\$(OURA_BROKER_URL)", "not a url")) {
            assertFalse("$bad", OuraConfiguration(bad).isConfigured)
        }
        assertEquals("https://broker.example/base/oauth/exchange", OuraConfiguration("https://broker.example/base/").brokerEndpoint("oauth/exchange"))
        try {
            OuraConfiguration(null).brokerEndpoint("config")
            fail()
        } catch (error: OuraException) {
            assertEquals(OuraError.NOT_CONFIGURED, error.error)
        }
    }

    @Test
    fun authorizationRequestsDailyScopeAndPKCE() {
        val request = OuraAuthorizationRequest.generate()
        val other = OuraAuthorizationRequest.generate()
        assertNotEquals(request.state, other.state)
        assertNotEquals(request.codeVerifier, other.codeVerifier)
        assertTrue(request.codeVerifier.length in 43..128)
        assertTrue(request.codeVerifier.all { it.isLetterOrDigit() || it in "-._~" })
        val digest = MessageDigest.getInstance("SHA-256").digest(request.codeVerifier.toByteArray())
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest), request.codeChallenge)
        val url = request.authorizationURL("client id")
        assertTrue(url.startsWith("https://cloud.ouraring.com/oauth/authorize?response_type=code&client_id=client+id&"))
        assertTrue(url.contains("redirect_uri=com.jonisuominen.readout%3A%2F%2Foauth%2Foura"))
        assertTrue(url.contains("scope=daily"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${request.state}"))
    }

    @Test
    fun callbackRequiresExactRedirectStateAndSingleCode() {
        val request = OuraAuthorizationRequest("state-1", "verifier", "challenge")
        val base = OuraConfiguration.CALLBACK_URL
        assertEquals(OuraAuthorizationGrant("abc 1", "verifier"), request.validateCallback("$base?code=abc%201&state=state-1"))
        fun error(callback: String): OuraError = try {
            request.validateCallback(callback)
            throw AssertionError("Accepted $callback")
        } catch (error: OuraException) {
            error.error
        }
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=abc&state=other"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=abc"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=abc&state=state-1&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=a&code=b&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("com.jonisuominen.readout://oauth/other?code=abc&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("com.jonisuominen.readout://open/oura?code=abc&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("https://oauth/oura?code=abc&state=state-1"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=abc&state=state-1#fragment"))
        assertEquals(OuraError.INVALID_CALLBACK, error("$base?code=a%00b&state=state-1"))
        assertEquals(OuraError.AUTHORIZATION_DENIED, error("$base?error=access_denied&state=state-1"))
        assertEquals("An error without the state proves nothing", OuraError.INVALID_CALLBACK, error("$base?error=access_denied"))
    }

    @Test
    fun publicConfigurationRejectsDifferentCallback() {
        OuraPublicConfiguration("client", OuraConfiguration.CALLBACK_URL).validated()
        for (bad in listOf(
            OuraPublicConfiguration("client", "https://evil.example/callback"),
            OuraPublicConfiguration("", OuraConfiguration.CALLBACK_URL),
            OuraPublicConfiguration("a".repeat(257), OuraConfiguration.CALLBACK_URL),
            OuraPublicConfiguration("cli\nent", OuraConfiguration.CALLBACK_URL),
        )) {
            try {
                bad.validated()
                fail()
            } catch (error: OuraException) {
                assertEquals(OuraError.INVALID_RESPONSE, error.error)
            }
        }
    }

    @Test
    fun apiDatesCoverAWeekBackThroughTomorrow() {
        assertEquals("2026-06-08" to "2026-06-16", OuraApiConnector.queryDates(baseNow, utc))
        assertEquals(
            "2026-06-09" to "2026-06-17",
            OuraApiConnector.queryDates(baseNow.plusMinutes(13 * 60), java.time.ZoneId.of("Asia/Tokyo")),
        )
    }

    @Test
    fun latestDayUsesMainSleepAndNeverAddsNapsOrOlderPeriods() {
        val metrics = OuraApiConnector.dashboardMetrics(
            readiness = listOf(
                OuraDailyReadiness("2026-06-14", 70, null),
                OuraDailyReadiness("2026-06-15", 86, "2026-06-15T06:30:00+03:00"),
                OuraDailyReadiness("2026-06-16", 400, null),
            ),
            dailySleep = listOf(OuraDailySleep("2026-06-14", 80), OuraDailySleep("2026-06-15", 91)),
            sleep = listOf(
                OuraSleepPeriod("2026-06-14", "long_sleep", 30_000, "2026-06-14T07:00:00+03:00"),
                OuraSleepPeriod("2026-06-15", "long_sleep", 27_720, "2026-06-15T07:10:00+03:00"),
                OuraSleepPeriod("2026-06-15", "long_sleep", 9_000, "2026-06-15T09:00:00+03:00"),
                OuraSleepPeriod("2026-06-15", "late_nap", 3_600, "2026-06-15T15:00:00+03:00"),
            ),
            zone = utc,
        )
        val readiness = metrics[0]
        assertEquals("86", readiness.value)
        assertEquals("Oura · 2026-06-15", readiness.detail)
        assertEquals(java.time.Instant.parse("2026-06-15T03:30:00Z"), readiness.observedAt)
        assertEquals(0.86, readiness.gaugeProgress!!, 1e-9)
        val sleep = metrics[1]
        assertEquals("7h 42m", sleep.value)
        assertEquals("Score 91 · 2026-06-15", sleep.detail)
        assertEquals(27_720.0, sleep.numericValue)
        assertNull("A duration has no goal", sleep.targetValue)
        assertEquals(java.time.Instant.parse("2026-06-15T04:10:00Z"), sleep.observedAt)
        assertEquals("Oura account", sleep.origin)
    }

    @Test
    fun sleepScoreDoesNotBorrowDurationFromAnOlderDay() {
        val metrics = OuraApiConnector.dashboardMetrics(
            emptyList(),
            listOf(OuraDailySleep("2026-06-15", 77)),
            listOf(OuraSleepPeriod("2026-06-14", "long_sleep", 30_000, null)),
            utc,
        )
        assertEquals("77", metrics[1].value)
        assertEquals("Sleep score · 2026-06-15", metrics[1].detail)
        assertEquals(100.0, metrics[1].targetValue)
        assertEquals(java.time.Instant.parse("2026-06-15T00:00:00Z"), metrics[1].observedAt)
    }

    @Test
    fun emptyResponsesDoNotInventScoresOrObservations() {
        val metrics = OuraApiConnector.dashboardMetrics(emptyList(), emptyList(), emptyList(), utc)
        assertEquals(listOf("—", "—"), metrics.map { it.value })
        assertEquals("No recent Oura readiness data", metrics[0].detail)
        assertEquals("No recent Oura sleep data", metrics[1].detail)
        assertTrue(metrics.all { it.numericValue == null && it.observedAt == null })
        assertNull(OuraApiConnector.observationDate("garbage", "also garbage", utc))
    }

    @Test
    fun refreshTokenRotationAndMissingRefreshTokenFallback() {
        val rotated = ReadoutJson.decodeFromString(OuraTokenResponse.serializer(), tokenJSON("a2")).tokens(baseNow, "old")
        assertEquals("refresh-2", rotated.refreshToken)
        assertEquals(baseNow.plusMinutes(60), rotated.expiresAt)
        val kept = ReadoutJson.decodeFromString(OuraTokenResponse.serializer(), tokenJSON("a2", refresh = null)).tokens(baseNow, "old")
        assertEquals("old", kept.refreshToken)
        for (bad in listOf(
            OuraTokenResponse("a", null, 3600.0, "Bearer"),
            OuraTokenResponse("", "r", 3600.0, "Bearer"),
            OuraTokenResponse("a", "r", 0.0, "Bearer"),
            OuraTokenResponse("a", "r", Double.NaN, "Bearer"),
            OuraTokenResponse("a", "r", 3600.0, "mac"),
            OuraTokenResponse("a\n", "r", 3600.0, "Bearer"),
        )) {
            try {
                bad.tokens(baseNow)
                fail("$bad")
            } catch (error: OuraException) {
                assertEquals(OuraError.INVALID_RESPONSE, error.error)
            }
        }
    }

    @Test
    fun authorizationExchangesPKCEGrantAndStoresTokens() = runTest {
        val transport = Recorder { request ->
            when {
                request.url.endsWith("/config") -> json("""{"client_id":"client","redirect_uri":"${OuraConfiguration.CALLBACK_URL}"}""")
                request.url.endsWith("/oauth/exchange") -> json(tokenJSON("access-1", "refresh-1"))
                else -> json(emptyPage)
            }
        }
        val tokens = MemoryTokens()
        val connector = connector(transport, tokens, FakeAuthenticator())
        connector.authorize()
        assertEquals("access-1", tokens.tokens?.accessToken)
        val exchange = transport.requests.first { it.url.endsWith("/oauth/exchange") }
        assertEquals("POST", exchange.method)
        val body = exchange.body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"code\":\"code-1\""))
        assertTrue(body.contains("\"code_verifier\":\"verifier-1\""))
        assertTrue(body.contains("\"redirect_uri\":\"${OuraConfiguration.CALLBACK_URL}\""))

        val metrics = connector.refresh()
        assertEquals(listOf("readiness", "sleep"), metrics.map { it.id })
        val read = transport.requests.first { it.url.contains("daily_readiness") }
        assertEquals("Bearer access-1", read.headers["Authorization"])
        assertTrue(read.url.startsWith("https://api.ouraring.com/v2/usercollection/daily_readiness?start_date=2026-06-08&end_date=2026-06-16"))
        assertEquals(0, transport.count("/oauth/refresh"))
    }

    @Test
    fun missingConfigurationFailsBeforeAnyRequest() = runTest {
        val transport = Recorder { json(emptyPage) }
        val unconfigured = connector(transport, MemoryTokens(validTokens()), FakeAuthenticator(), OuraConfiguration(null))
        expectError(OuraError.NOT_CONFIGURED) { unconfigured.authorize() }
        expectError(OuraError.NOT_CONFIGURED) { unconfigured.refresh() }
        expectError(OuraError.NOT_CONFIGURED) { connector(transport, MemoryTokens(), null).authorize() }
        expectError(OuraError.NOT_CONNECTED) { connector(transport, MemoryTokens(), null).refresh() }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun expiredTokenRefreshesOnceForConcurrentCollections() = runTest {
        val transport = Recorder { request ->
            if (request.url.endsWith("/oauth/refresh")) json(tokenJSON("access-2")) else json(emptyPage)
        }
        val tokens = MemoryTokens(validTokens(expiresInMinutes = 0))
        connector(transport, tokens).refresh()
        assertEquals("The single-use refresh token is spent once", 1, transport.count("/oauth/refresh"))
        assertEquals("access-2", tokens.tokens?.accessToken)
        assertEquals("refresh-2", tokens.tokens?.refreshToken)
        assertTrue(transport.requests.filter { it.url.contains("usercollection") }.all { it.headers["Authorization"] == "Bearer access-2" })
    }

    @Test
    fun unauthorizedResponsesRefreshAndRetryOnlyOnce() = runTest {
        val transport = Recorder { request ->
            when {
                request.url.endsWith("/oauth/refresh") -> json(tokenJSON("access-2"))
                request.headers["Authorization"] == "Bearer access-1" -> json("", 401)
                else -> json(emptyPage)
            }
        }
        val tokens = MemoryTokens(validTokens())
        connector(transport, tokens).refresh()
        assertEquals(1, transport.count("/oauth/refresh"))

        val alwaysRejected = Recorder { request ->
            if (request.url.endsWith("/oauth/refresh")) json(tokenJSON("access-2")) else json("", 401)
        }
        val rejectedTokens = MemoryTokens(validTokens())
        expectError(OuraError.NOT_CONNECTED) { connector(alwaysRejected, rejectedTokens).refresh() }
        assertNull("Credentials Oura keeps rejecting are removed", rejectedTokens.tokens)
    }

    @Test
    fun revokedRefreshTokenClearsLocalCredentials() = runTest {
        val transport = Recorder { request ->
            if (request.url.endsWith("/oauth/refresh")) json("""{"error":"invalid_grant"}""", 400) else json(emptyPage)
        }
        val tokens = MemoryTokens(validTokens(expiresInMinutes = 0))
        expectError(OuraError.NOT_CONNECTED) { connector(transport, tokens).refresh() }
        assertNull(tokens.tokens)
    }

    @Test
    fun serviceErrorsMapToClearStates() = runTest {
        for ((status, expected) in listOf(403 to OuraError.PERMISSION_REQUIRED, 429 to OuraError.RATE_LIMITED, 500 to OuraError.SERVICE_UNAVAILABLE, 302 to OuraError.SERVICE_UNAVAILABLE)) {
            val tokens = MemoryTokens(validTokens())
            expectError(expected) { connector(Recorder { json("", status) }, tokens).refresh() }
            assertEquals("A temporary failure keeps the credentials", "access-1", tokens.tokens?.accessToken)
        }
        expectError(OuraError.SERVICE_UNAVAILABLE) {
            connector({ throw java.io.IOException("offline") }, MemoryTokens(validTokens())).refresh()
        }
        expectError(OuraError.INVALID_RESPONSE) { connector(Recorder { json("not json") }, MemoryTokens(validTokens())).refresh() }
        val looping = Recorder { json("""{"data":[],"next_token":"same"}""") }
        expectError(OuraError.INVALID_RESPONSE) { connector(looping, MemoryTokens(validTokens())).refresh() }
    }

    @Test
    fun pagesAreFollowed() = runTest {
        val transport = Recorder { request ->
            when {
                request.url.contains("daily_readiness") && !request.url.contains("next_token") ->
                    json("""{"data":[{"day":"2026-06-14","score":70}],"next_token":"page 2"}""")
                request.url.contains("daily_readiness") -> json("""{"data":[{"day":"2026-06-15","score":88}]}""")
                else -> json(emptyPage)
            }
        }
        val metrics = connector(transport, MemoryTokens(validTokens())).refresh()
        assertEquals("88", metrics[0].value)
        assertTrue(transport.requests.any { it.url.contains("next_token=page+2") })
    }

    @Test
    fun successfulRevocationConfirmsBothOperations() = runTest {
        val transport = Recorder { json("", 204) }
        val tokens = MemoryTokens(validTokens())
        val authenticator = FakeAuthenticator()
        val result = connector(transport, tokens, authenticator).disconnect()
        assertEquals(DisconnectResult.revoked, result)
        assertNull(tokens.tokens)
        assertEquals(1, authenticator.cancelled)
        val revoke = transport.requests.single()
        assertTrue(revoke.url.endsWith("/oauth/revoke"))
        assertTrue(revoke.body!!.toString(Charsets.UTF_8).contains("access-1"))
        assertFalse(result.requiresAttention)
    }

    @Test
    fun disconnectReportsLocalAndRemoteResultsSeparately() = runTest {
        val offline = HttpTransport { throw java.io.IOException("offline") }
        val tokens = MemoryTokens(validTokens())
        val unconfirmed = connector(offline, tokens).disconnect()
        assertEquals(DisconnectResult(true, DisconnectResult.RemoteAuthorization.UNCONFIRMED), unconfirmed)
        assertNull("Offline revocation does not keep credentials", tokens.tokens)
        assertTrue(unconfirmed.requiresAttention)

        assertEquals(
            "An unexpected reply is not a confirmed revocation",
            DisconnectResult.RemoteAuthorization.UNCONFIRMED,
            connector(Recorder { json("{}", 200) }, MemoryTokens(validTokens())).disconnect().remoteAuthorization,
        )
        assertEquals(DisconnectResult.noStoredCredentials, connector(Recorder { json("", 204) }, MemoryTokens()).disconnect())

        val stuck = MemoryTokens(validTokens()).apply { failDelete = true }
        val stuckResult = connector(Recorder { json("", 204) }, stuck).disconnect()
        assertEquals(DisconnectResult(false, DisconnectResult.RemoteAuthorization.REVOKED), stuckResult)
        assertTrue(stuckResult.requiresAttention)

        val unreadable = MemoryTokens(validTokens()).apply { failLoad = true }
        val transport = Recorder { json("", 204) }
        val unreadableResult = connector(transport, unreadable).disconnect()
        assertEquals(DisconnectResult(true, DisconnectResult.RemoteAuthorization.UNCONFIRMED), unreadableResult)
        assertTrue("No token, so nothing to revoke with", transport.requests.isEmpty())

        val noBroker = MemoryTokens(validTokens())
        val noBrokerResult = connector(Recorder { json("", 204) }, noBroker, config = OuraConfiguration(null)).disconnect()
        assertEquals(DisconnectResult(true, DisconnectResult.RemoteAuthorization.UNCONFIRMED), noBrokerResult)
        assertNull(noBroker.tokens)
    }

    @Test
    fun disconnectDuringSignInBlocksLateTokenWrites() = runTest {
        val tokens = MemoryTokens()
        lateinit var connector: OuraApiConnector
        val authenticator = object : OuraAuthenticating {
            override suspend fun authenticate(configuration: OuraPublicConfiguration): OuraAuthorizationGrant {
                connector.disconnect()
                return OuraAuthorizationGrant("code", "verifier")
            }
            override suspend fun cancel() {}
        }
        val transport = Recorder { request ->
            when {
                request.url.endsWith("/config") -> json("""{"client_id":"client","redirect_uri":"${OuraConfiguration.CALLBACK_URL}"}""")
                else -> json(tokenJSON("late"))
            }
        }
        connector = connector(transport, tokens, authenticator)
        expectError(OuraError.CANCELLED) { connector.authorize() }
        assertNull(tokens.tokens)
        assertEquals(0, tokens.saves)
        assertEquals(0, transport.count("/oauth/exchange"))
    }

    @Test
    fun statusMessagesExplainRemainingAndroidPermissions() {
        assertTrue(DisconnectResult.localOnly.statusMessage.contains("Android Settings"))
        assertNull(DisconnectResult.localOnly.warningMessage)
        assertTrue(DisconnectResult.revoked.statusMessage.contains("revoked"))
        for (remote in DisconnectResult.RemoteAuthorization.entries) {
            assertTrue(DisconnectResult(false, remote).requiresAttention)
            assertTrue(DisconnectResult(false, remote).statusMessage.isNotEmpty())
        }
    }
}
