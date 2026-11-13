package com.jonisuominen.readout.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64

@Serializable
data class OuraDailyReadiness(val day: String, val score: Int? = null, val timestamp: String? = null)

@Serializable
data class OuraDailySleep(val day: String, val score: Int? = null, val timestamp: String? = null)

@Serializable
data class OuraSleepPeriod(
    val day: String,
    val type: String? = null,
    @SerialName("total_sleep_duration") val totalSleepDuration: Int? = null,
    @SerialName("bedtime_end") val bedtimeEnd: String? = null,
)

@Serializable
data class OuraCollectionPage<Record>(
    val data: List<Record>,
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
data class OuraTokens(
    val accessToken: String,
    val refreshToken: String,
    @Serializable(InstantSerializer::class) val expiresAt: Instant,
    val scope: String? = null,
)

/** Where account tokens are kept. Implementations encrypt them at rest. */
interface OuraTokenStoring {
    fun load(): OuraTokens?
    fun save(tokens: OuraTokens)
    fun delete()
}

@Serializable
data class OuraTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Double,
    @SerialName("token_type") val tokenType: String,
    val scope: String? = null,
) {
    fun tokens(at: Instant, previousRefreshToken: String? = null): OuraTokens {
        val refresh = refreshToken ?: previousRefreshToken
        if (tokenType.lowercase() != "bearer" || !(expiresIn > 0) || !expiresIn.isFinite() ||
            accessToken.isEmpty() || refresh.isNullOrEmpty() ||
            accessToken.any(Char::isISOControl) || refresh.any(Char::isISOControl)
        ) {
            throw OuraException(OuraError.INVALID_RESPONSE)
        }
        return OuraTokens(accessToken, refresh, at.plusMillis((expiresIn * 1000).toLong()), scope)
    }
}

enum class OuraError(val message: String) {
    NOT_CONFIGURED("Direct Oura connection is unavailable. You can use Oura sleep through Health Connect."),
    NOT_CONNECTED("Connect your Oura account to update readiness and sleep."),
    CANCELLED("Oura connection was cancelled."),
    AUTHORIZATION_DENIED("Oura access was not granted. Connect again and allow daily data access."),
    INVALID_CALLBACK("Oura sign-in could not be verified. Please connect again."),
    INVALID_RESPONSE("Oura returned an unexpected response. Please try again."),
    SECURE_STORAGE("Your Oura connection could not be saved securely. Please try again."),
    PERMISSION_REQUIRED("Oura daily data permission is required. Reconnect Oura and allow daily data access."),
    RATE_LIMITED("Oura is receiving too many requests. Please try again shortly."),
    SERVICE_UNAVAILABLE("Oura is temporarily unavailable. Please try again later."),
}

class OuraException(val error: OuraError) : Exception(error.message)

class OuraConfiguration(brokerURL: String?, val apiURL: String = OFFICIAL_API_URL) {
    val brokerURL: String? = brokerURL?.trim()?.takeIf { isSecureEndpoint(it) }

    val isConfigured: Boolean get() = brokerURL != null && isSecureEndpoint(apiURL)

    val integrationStatus: String
        get() = if (isConfigured) {
            "Connect your Oura account for readiness and sleep."
        } else {
            "Use Oura sleep shared with Health Connect."
        }

    fun brokerEndpoint(path: String): String {
        val base = brokerURL?.takeIf { isConfigured } ?: throw OuraException(OuraError.NOT_CONFIGURED)
        return path.split("/").filter { it.isNotEmpty() }.fold(base.trimEnd('/')) { url, part -> "$url/$part" }
    }

    companion object {
        const val CALLBACK_URL = "com.jonisuominen.readout://oauth/oura"
        const val CALLBACK_SCHEME = "com.jonisuominen.readout"
        const val CALLBACK_HOST = "oauth"
        const val CALLBACK_PATH = "/oura"
        const val AUTHORIZATION_URL = "https://cloud.ouraring.com/oauth/authorize"
        const val OFFICIAL_API_URL = "https://api.ouraring.com/v2/usercollection"

        fun isSecureEndpoint(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return false
            }
            val host = uri.host
            if (uri.scheme?.lowercase() != "https" || host.isNullOrEmpty()) return false
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
            return !host.contains('$') && !host.contains('(') && !host.contains(')')
        }
    }
}

@Serializable
data class OuraPublicConfiguration(
    @SerialName("client_id") val clientID: String,
    @SerialName("redirect_uri") val redirectURI: String,
) {
    fun validated(): OuraPublicConfiguration {
        if (clientID.isEmpty() || clientID.length > 256 || clientID.any(Char::isISOControl) ||
            redirectURI != OuraConfiguration.CALLBACK_URL
        ) {
            throw OuraException(OuraError.INVALID_RESPONSE)
        }
        return this
    }
}

data class OuraAuthorizationGrant(val code: String, val codeVerifier: String)

/** Shows the Oura sign-in page and returns the verified authorization code. */
interface OuraAuthenticating {
    suspend fun authenticate(configuration: OuraPublicConfiguration): OuraAuthorizationGrant
    suspend fun cancel()
}

/** One sign-in attempt: a fresh state and PKCE verifier, discarded afterwards. */
class OuraAuthorizationRequest(
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
) {
    fun authorizationURL(clientID: String): String = OuraConfiguration.AUTHORIZATION_URL + "?" + queryString(
        listOf(
            "response_type" to "code",
            "client_id" to clientID,
            "redirect_uri" to OuraConfiguration.CALLBACK_URL,
            "scope" to "daily",
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        ),
    )

    fun validateCallback(callback: String): OuraAuthorizationGrant {
        val uri = try {
            URI(callback)
        } catch (_: Exception) {
            throw OuraException(OuraError.INVALID_CALLBACK)
        }
        if (uri.scheme != OuraConfiguration.CALLBACK_SCHEME || uri.host != OuraConfiguration.CALLBACK_HOST ||
            uri.rawPath != OuraConfiguration.CALLBACK_PATH || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawFragment != null
        ) {
            throw OuraException(OuraError.INVALID_CALLBACK)
        }
        val items = uri.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.map { item ->
            val name = item.substringBefore("=")
            val value = if (item.contains("=")) item.substringAfter("=") else null
            decode(name) to value?.let(::decode)
        }
        val states = items.filter { it.first == "state" }
        if (states.size != 1 || states[0].second != state) throw OuraException(OuraError.INVALID_CALLBACK)
        val errors = items.filter { it.first == "error" }
        if (errors.size == 1) throw OuraException(OuraError.AUTHORIZATION_DENIED)
        val codes = items.filter { it.first == "code" }
        val code = codes.singleOrNull()?.second
        if (errors.isNotEmpty() || code.isNullOrEmpty() || code.length > 4096 || code.any(Char::isISOControl)) {
            throw OuraException(OuraError.INVALID_CALLBACK)
        }
        return OuraAuthorizationGrant(code, codeVerifier)
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        throw OuraException(OuraError.INVALID_CALLBACK)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): OuraAuthorizationRequest {
            val verifier = secureRandom(random)
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return OuraAuthorizationRequest(secureRandom(random), verifier, base64URL(digest))
        }

        private fun secureRandom(random: SecureRandom): String =
            base64URL(ByteArray(32).also(random::nextBytes))

        private fun base64URL(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Reads readiness and sleep from Oura's API with tokens from the broker.
 * One instance serves the app, its widgets, and background work, so the
 * single-use refresh token is only ever spent by one request at a time.
 */
class OuraApiConnector(
    private val configuration: OuraConfiguration,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val tokenStorage: OuraTokenStoring,
    /** Only the app on screen can show the Oura sign-in page. */
    private val authenticator: OuraAuthenticating? = null,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = { DisplayEnvironment.current.zone },
) : DashboardConnector {
    override val source = DataSource.OURA

    private val stateLock = Any()
    private val refreshMutex = Mutex()
    private var tokens: OuraTokens? = null
    private var didLoadTokens = false
    private var generation = 0

    override suspend fun authorize() {
        val authenticator = authenticator
        if (!configuration.isConfigured || authenticator == null) throw OuraException(OuraError.NOT_CONFIGURED)
        val operation = synchronized(stateLock) { ++generation }
        val publicData = send(HttpRequest(configuration.brokerEndpoint("config"))).body
        val publicConfig = decode(OuraPublicConfiguration.serializer(), publicData).validated()
        checkOperation(operation)
        val grant = authenticator.authenticate(publicConfig)
        checkOperation(operation)
        val response = brokerRequest(
            "oauth/exchange",
            mapOf(
                "code" to grant.code,
                "code_verifier" to grant.codeVerifier,
                "redirect_uri" to OuraConfiguration.CALLBACK_URL,
            ),
        )
        val newTokens = decode(OuraTokenResponse.serializer(), response).tokens(now())
        checkOperation(operation)
        saveTokens(newTokens)
    }

    override suspend fun refresh(): List<DashboardMetric> {
        if (!configuration.isConfigured) throw OuraException(OuraError.NOT_CONFIGURED)
        val operation = synchronized(stateLock) { generation }
        val date = now()
        val metrics = coroutineScope {
            val readiness = async { collection("daily_readiness", OuraDailyReadiness.serializer(), date) }
            val dailySleep = async { collection("daily_sleep", OuraDailySleep.serializer(), date) }
            val sleep = async { collection("sleep", OuraSleepPeriod.serializer(), date) }
            dashboardMetrics(readiness.await(), dailySleep.await(), sleep.await(), zone())
        }
        checkOperation(operation)
        return metrics
    }

    override suspend fun disconnect(): DisconnectResult {
        val rememberedAccessToken = synchronized(stateLock) {
            val remembered = tokens?.accessToken
            generation += 1
            tokens = null
            didLoadTokens = true
            remembered
        }

        // Read only for this revocation attempt; never retain a token to retry later.
        var credentialsReadFailed = false
        val oldToken = try {
            tokenStorage.load()?.accessToken ?: rememberedAccessToken
        } catch (_: Exception) {
            credentialsReadFailed = true
            rememberedAccessToken
        }
        val localCredentialsCleared = try {
            tokenStorage.delete()
            true
        } catch (_: Exception) {
            false
        }
        authenticator?.cancel()

        val remoteAuthorization = if (oldToken != null && configuration.isConfigured) {
            try {
                brokerRequest("oauth/revoke", mapOf("access_token" to oldToken), allowEmpty = true)
                DisconnectResult.RemoteAuthorization.REVOKED
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DisconnectResult.RemoteAuthorization.UNCONFIRMED
            }
        } else if (oldToken != null || credentialsReadFailed) {
            DisconnectResult.RemoteAuthorization.UNCONFIRMED
        } else {
            DisconnectResult.RemoteAuthorization.NO_STORED_CREDENTIALS
        }
        return DisconnectResult(localCredentialsCleared, remoteAuthorization)
    }

    private suspend fun checkOperation(operation: Int) {
        currentCoroutineContext().ensureActive()
        if (synchronized(stateLock) { generation } != operation) throw OuraException(OuraError.CANCELLED)
    }

    private fun loadTokens(): OuraTokens? = synchronized(stateLock) {
        if (!didLoadTokens) {
            tokens = tokenStorage.load()
            didLoadTokens = true
        }
        tokens
    }

    private fun saveTokens(newTokens: OuraTokens) = synchronized(stateLock) {
        tokenStorage.save(newTokens)
        tokens = newTokens
        didLoadTokens = true
    }

    private fun forgetTokens(operation: Int) = synchronized(stateLock) {
        if (generation != operation) return@synchronized
        tokens = null
        try {
            tokenStorage.delete()
        } catch (_: Exception) {
            // The next connect or disconnect reports a storage failure.
        }
    }

    private suspend fun accessToken(rejected: String? = null): String {
        val current = loadTokens() ?: throw OuraException(OuraError.NOT_CONNECTED)
        // Another request may already have rotated the token rejected by this request.
        if (rejected != null && rejected != current.accessToken) return current.accessToken
        if (rejected == null && current.expiresAt.toEpochMilli() - now().toEpochMilli() > 60_000) {
            return current.accessToken
        }
        // Two requests must never spend the same single-use refresh token.
        return refreshMutex.withLock {
            val latest = loadTokens() ?: throw OuraException(OuraError.NOT_CONNECTED)
            if (latest.accessToken != current.accessToken) return@withLock latest.accessToken
            val operation = synchronized(stateLock) { generation }
            try {
                val data = brokerRequest("oauth/refresh", mapOf("refresh_token" to latest.refreshToken))
                val refreshed = decode(OuraTokenResponse.serializer(), data).tokens(now(), latest.refreshToken)
                checkOperation(operation)
                saveTokens(refreshed)
                refreshed.accessToken
            } catch (error: OuraException) {
                if (error.error == OuraError.NOT_CONNECTED) forgetTokens(operation)
                throw error
            }
        }
    }

    private suspend fun <Record> collection(name: String, serializer: KSerializer<Record>, date: Instant): List<Record> {
        val (start, end) = queryDates(date, zone())
        val records = ArrayList<Record>()
        var nextToken: String? = null
        val seenTokens = HashSet<String>()
        do {
            val items = ArrayList<Pair<String, String>>()
            items += "start_date" to start
            items += "end_date" to end
            nextToken?.let { items += "next_token" to it }
            val url = "${configuration.apiURL.trimEnd('/')}/$name?${queryString(items)}"
            val page = decode(OuraCollectionPage.serializer(serializer), authenticatedData(url))
            records += page.data
            nextToken = page.nextToken?.takeIf { it.isNotEmpty() }
            nextToken?.let {
                if (!seenTokens.add(it) || seenTokens.size >= 20) throw OuraException(OuraError.INVALID_RESPONSE)
            }
        } while (nextToken != null)
        return records
    }

    private suspend fun authenticatedData(url: String): ByteArray {
        val operation = synchronized(stateLock) { generation }
        var access = accessToken()
        for (attempt in 0..1) {
            checkOperation(operation)
            val response = send(
                HttpRequest(url, headers = mapOf("Authorization" to "Bearer $access")),
                allowUnauthorized = true,
            )
            if (response.status != 401) return response.body
            if (attempt == 0) {
                access = accessToken(rejected = access)
            } else {
                if (synchronized(stateLock) { tokens?.accessToken } == access) forgetTokens(operation)
                throw OuraException(OuraError.NOT_CONNECTED)
            }
        }
        throw OuraException(OuraError.NOT_CONNECTED)
    }

    private suspend fun brokerRequest(path: String, body: Map<String, String>, allowEmpty: Boolean = false): ByteArray {
        val json = ReadoutJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), body)
        val response = send(
            HttpRequest(
                configuration.brokerEndpoint(path),
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.toByteArray(Charsets.UTF_8),
            ),
            allowEmpty = allowEmpty,
            isTokenRequest = !allowEmpty,
        )
        if (allowEmpty && response.status != 204) throw OuraException(OuraError.INVALID_RESPONSE)
        return response.body
    }

    private suspend fun send(
        original: HttpRequest,
        allowEmpty: Boolean = false,
        allowUnauthorized: Boolean = false,
        isTokenRequest: Boolean = false,
    ): HttpResponse {
        if (!OuraConfiguration.isSecureEndpoint(original.url.substringBefore("?"))) {
            throw OuraException(OuraError.INVALID_RESPONSE)
        }
        val request = HttpRequest(
            original.url,
            original.method,
            original.headers + ("Accept" to "application/json"),
            original.body,
            timeoutMillis = 30_000,
        )
        val response = try {
            transport.send(request)
        } catch (_: java.io.IOException) {
            throw OuraException(OuraError.SERVICE_UNAVAILABLE)
        }
        currentCoroutineContext().ensureActive()
        return when {
            response.status == 200 -> response
            response.status == 204 && allowEmpty -> response
            response.status == 401 && allowUnauthorized -> response
            response.status == 400 && isTokenRequest -> throw OuraException(OuraError.NOT_CONNECTED)
            response.status == 401 -> throw OuraException(OuraError.NOT_CONNECTED)
            response.status == 403 -> throw OuraException(OuraError.PERMISSION_REQUIRED)
            response.status == 429 -> throw OuraException(OuraError.RATE_LIMITED)
            else -> throw OuraException(OuraError.SERVICE_UNAVAILABLE)
        }
    }

    private fun <Value> decode(serializer: KSerializer<Value>, data: ByteArray): Value = try {
        ReadoutJson.decodeFromString(serializer, data.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        throw OuraException(OuraError.INVALID_RESPONSE)
    }

    companion object {
        /** A week back through tomorrow, as Oura's inclusive `yyyy-MM-dd` dates. */
        fun queryDates(date: Instant, zone: ZoneId): Pair<String, String> {
            val day = date.atZone(zone).toLocalDate()
            return day.minusDays(7).toString() to day.plusDays(1).toString()
        }

        fun dashboardMetrics(
            readiness: List<OuraDailyReadiness>,
            dailySleep: List<OuraDailySleep>,
            sleep: List<OuraSleepPeriod>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<DashboardMetric> {
            val latestReadiness = readiness.filter { validScore(it.score) != null }.maxByOrNull { it.day }
            val readinessScore = validScore(latestReadiness?.score)
            val readinessMetric = DashboardMetric(
                id = "readiness",
                source = DataSource.OURA,
                title = "Readiness",
                value = readinessScore?.toString() ?: "—",
                detail = latestReadiness?.let { "Oura · ${it.day}" } ?: "No recent Oura readiness data",
                symbol = Symbols.SPARKLES,
                tint = "violet",
                numericValue = readinessScore?.toDouble(),
                targetValue = 100.0,
                observedAt = latestReadiness?.let { observationDate(it.timestamp, it.day, zone) },
                origin = "Oura account",
            )
            val summary = dailySleep.maxByOrNull { it.day }
            val mainSleeps = sleep.filter { it.type == "long_sleep" && (it.totalSleepDuration ?: 0) > 0 }
            val sleepDay = summary?.day ?: mainSleeps.maxOfOrNull { it.day }
            // Pick the main period for the daily summary; naps and split records are never summed.
            val mainSleep = mainSleeps.filter { it.day == sleepDay }
                .maxWithOrNull(compareBy<OuraSleepPeriod> { it.totalSleepDuration ?: 0 }.thenBy { it.bedtimeEnd ?: "" })
            val duration = mainSleep?.totalSleepDuration?.takeIf { it in 1..86_400 }
            val sleepScore = validScore(summary?.score)
            var sleepDetail = sleepScore?.let { "Score $it" } ?: "Oura sleep"
            if (sleepDay != null) sleepDetail += " · $sleepDay"
            if (duration == null && sleepScore == null) sleepDetail = "No recent Oura sleep data"
            val sleepMetric = DashboardMetric(
                id = "sleep",
                source = DataSource.OURA,
                title = "Sleep",
                value = duration?.let(::formatDuration) ?: sleepScore?.toString() ?: "—",
                detail = if (duration == null && sleepScore != null) "Sleep score · ${sleepDay ?: ""}" else sleepDetail,
                symbol = Symbols.SLEEP,
                tint = "indigo",
                numericValue = duration?.toDouble() ?: sleepScore?.toDouble(),
                targetValue = if (duration != null) null else 100.0,
                observedAt = sleepDay?.let { observationDate(mainSleep?.bedtimeEnd ?: summary?.timestamp, it, zone) },
                origin = "Oura account",
            )
            return listOf(readinessMetric, sleepMetric)
        }

        private fun validScore(value: Int?): Int? = value?.takeIf { it in 0..100 }

        fun formatDuration(seconds: Int): String {
            val minutes = maxOf(0, seconds) / 60
            return "${minutes / 60}h ${minutes % 60}m"
        }

        fun observationDate(timestamp: String?, day: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
            if (timestamp != null) {
                try {
                    return OffsetDateTime.parse(timestamp).toInstant()
                } catch (_: Exception) {
                    // Fall back to the day.
                }
            }
            return try {
                LocalDate.parse(day).atStartOfDay(zone).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }
}
