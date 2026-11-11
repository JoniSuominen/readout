import Foundation
import XCTest
@testable import WeatherUV

@MainActor
final class OuraConnectorTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_791_590_400)

    func testConfigurationRejectsPlainHTTPAndUnresolvedBuildSettings() {
        XCTAssertFalse(OuraConfiguration(brokerURL: URL(string: "http://broker.example.com")).isConfigured)
        XCTAssertFalse(OuraConfiguration(brokerURL: URL(string: "https://$(OURA_BROKER_URL)")).isConfigured)
        XCTAssertFalse(OuraConfiguration(brokerURL: URL(string: "https://user:secret@broker.example.com")).isConfigured)
        XCTAssertFalse(OuraConfiguration(brokerURL: URL(string: "https://broker.example.com?token=secret")).isConfigured)
        XCTAssertFalse(OuraConfiguration(brokerURL: URL(string: "https://broker.example.com"),
                                         apiURL: URL(string: "http://api.ouraring.com")!).isConfigured)
        XCTAssertTrue(OuraConfiguration(brokerURL: URL(string: "https://broker.example.com")).isConfigured)
    }

    func testAuthorizationRequestsDailyScopeAndPKCE() throws {
        let request = authorizationRequest()
        let url = try request.authorizationURL(clientID: "public-client")
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first { $0.name == "scope" }?.value, "daily")
        XCTAssertEqual(query.first { $0.name == "state" }?.value, "expected-state")
        XCTAssertEqual(query.first { $0.name == "code_challenge_method" }?.value, "S256")
        XCTAssertEqual(query.first { $0.name == "redirect_uri" }?.value,
                       "com.jonisuominen.readout://oauth/oura")
        XCTAssertNil(query.first { $0.name == "client_secret" })
    }

    func testCallbackRequiresExactRedirectStateAndSingleCode() throws {
        let request = authorizationRequest()
        let valid = URL(string: "com.jonisuominen.readout://oauth/oura?state=expected-state&code=provider-code")!
        XCTAssertEqual(try request.validateCallback(valid).code, "provider-code")
        for url in [
            "com.jonisuominen.readout://oauth/oura?state=wrong&code=provider-code",
            "com.jonisuominen.readout://other/oura?state=expected-state&code=provider-code",
            "com.jonisuominen.readout://oauth/other?state=expected-state&code=provider-code",
            "com.jonisuominen.readout://oauth/oura?state=expected-state&state=expected-state&code=provider-code",
            "com.jonisuominen.readout://oauth/oura?state=expected-state&code=a&code=b",
            "com.jonisuominen.readout://oauth/oura?state=expected-state&code=provider-code#fragment"
        ] {
            XCTAssertThrowsError(try request.validateCallback(URL(string: url)!)) {
                XCTAssertEqual($0 as? OuraError, .invalidCallback)
            }
        }
        XCTAssertThrowsError(try request.validateCallback(URL(string:
            "com.jonisuominen.readout://oauth/oura?state=expected-state&error=access_denied")!)) {
            XCTAssertEqual($0 as? OuraError, .authorizationDenied)
        }
    }

    func testPublicConfigurationRejectsDifferentCallback() throws {
        let bad = try JSONDecoder().decode(OuraPublicConfiguration.self, from: json(
            #"{"client_id":"public-client","redirect_uri":"other-app://oauth/oura"}"#))
        XCTAssertThrowsError(try bad.validated())
    }

    func testAPIDatesUseGregorianYearsWithNonGregorianDeviceCalendar() {
        var calendar = Calendar(identifier: .buddhist)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = ISO8601DateFormatter().date(from: "2026-10-10T12:00:00Z")!
        let interval = OuraAPIConnector.queryDates(at: date, calendar: calendar)
        XCTAssertEqual(interval.start, "2026-10-03")
        XCTAssertEqual(interval.end, "2026-10-11")
    }

    func testLatestDayUsesMainSleepAndNeverAddsNapsOrOlderPeriods() throws {
        let readiness = try JSONDecoder().decode(OuraCollectionPage<OuraDailyReadiness>.self, from: json(
            #"{"data":[{"day":"2026-10-08","score":80,"timestamp":"2026-10-08T09:00:00+03:00"},{"day":"2026-10-09","score":86,"timestamp":"2026-10-09T09:00:00+03:00"}],"next_token":null}"#))
        let daily = try JSONDecoder().decode(OuraCollectionPage<OuraDailySleep>.self, from: json(
            #"{"data":[{"day":"2026-10-09","score":91,"timestamp":"2026-10-09T09:00:00+03:00"}]}"#))
        let sleep = try JSONDecoder().decode(OuraCollectionPage<OuraSleepPeriod>.self, from: json(
            #"{"data":[{"day":"2026-10-08","type":"long_sleep","total_sleep_duration":32000},{"day":"2026-10-09","type":"long_sleep","total_sleep_duration":27720,"bedtime_end":"2026-10-09T08:10:00+03:00"},{"day":"2026-10-09","type":"sleep","total_sleep_duration":3600},{"day":"2026-10-09","type":"long_sleep","total_sleep_duration":7200}],"next_token":null}"#))
        let metrics = OuraAPIConnector.dashboardMetrics(readiness: readiness.data,
                                                         dailySleep: daily.data, sleep: sleep.data)
        XCTAssertEqual(metrics[0].value, "86")
        XCTAssertEqual(metrics[0].numericValue, 86)
        XCTAssertEqual(metrics[0].targetValue, 100)
        XCTAssertEqual(metrics[1].value, "7h 42m")
        XCTAssertEqual(metrics[1].numericValue, 27_720)
        XCTAssertNil(metrics[1].targetValue)
        XCTAssertEqual(metrics[1].detail, "Score 91 · 2026-10-09")
        XCTAssertEqual(metrics[1].observedAt,
                       OuraAPIConnector.observationDate(timestamp: "2026-10-09T08:10:00+03:00", day: "2026-10-09"))
    }

    func testSleepScoreDoesNotBorrowDurationFromAnOlderDay() {
        let metrics = OuraAPIConnector.dashboardMetrics(
            readiness: [], dailySleep: [OuraDailySleep(day: "2026-10-09", score: 91, timestamp: nil)],
            sleep: [OuraSleepPeriod(day: "2026-10-08", type: "long_sleep", totalSleepDuration: 27_720, bedtimeEnd: nil)])
        XCTAssertEqual(metrics[1].value, "91")
        XCTAssertEqual(metrics[1].detail, "Sleep score · 2026-10-09")
        XCTAssertEqual(metrics[1].numericValue, 91)
        XCTAssertEqual(metrics[1].targetValue, 100)
    }

    func testEmptyResponsesDoNotInventScoresOrObservations() {
        let metrics = OuraAPIConnector.dashboardMetrics(readiness: [], dailySleep: [], sleep: [])
        XCTAssertEqual(metrics.map(\.value), ["—", "—"])
        XCTAssertTrue(metrics.allSatisfy { $0.numericValue == nil && $0.observedAt == nil })
    }

    func testRefreshTokenRotationAndMissingRefreshTokenFallback() throws {
        let rotated = try JSONDecoder().decode(OuraTokenResponse.self, from: json(tokenJSON()))
            .tokens(at: date, previousRefreshToken: "old-refresh")
        XCTAssertEqual(rotated.refreshToken, "refresh-new")
        XCTAssertEqual(rotated.expiresAt, date.addingTimeInterval(3_600))
        let noRefresh = try JSONDecoder().decode(OuraTokenResponse.self, from: json(
            #"{"access_token":"access-new","expires_in":3600,"token_type":"Bearer"}"#))
        XCTAssertEqual(try noRefresh.tokens(at: date, previousRefreshToken: "old-refresh").refreshToken, "old-refresh")
        XCTAssertThrowsError(try noRefresh.tokens(at: date))
    }

    func testAuthorizationExchangesPKCEGrantAndStoresTokens() async throws {
        let store = OuraMemoryTokenStore()
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            if request.url!.path == "/config" {
                return (200, #"{"client_id":"public-client","redirect_uri":"com.jonisuominen.readout://oauth/oura"}"#)
            }
            XCTAssertEqual(request.url!.path, "/oauth/exchange")
            let body = try JSONDecoder().decode([String: String].self, from: self.bodyData(request))
            XCTAssertEqual(body["code"], "provider-code")
            XCTAssertEqual(body["code_verifier"], String(repeating: "a", count: 43))
            XCTAssertEqual(body["redirect_uri"], OuraConfiguration.callbackURL.absoluteString)
            XCTAssertNil(body["client_secret"])
            return (200, self.tokenJSON())
        }
        try await connector.authorize()
        XCTAssertEqual(store.tokens?.accessToken, "access-new")
        XCTAssertEqual(store.tokens?.refreshToken, "refresh-new")
        XCTAssertEqual(log.requests.count, 2)
    }

    func testExpiredTokenRefreshesOnceForConcurrentCollections() async throws {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(-1), scope: "daily"))
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            if request.url!.path == "/oauth/refresh" {
                let body = try JSONDecoder().decode([String: String].self, from: self.bodyData(request))
                XCTAssertEqual(body, ["refresh_token": "refresh-old"])
                return (200, self.tokenJSON())
            }
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-new")
            return (200, self.collectionJSON(path: request.url!.path))
        }
        let metrics = try await connector.refresh()
        XCTAssertEqual(metrics.map(\.value), ["86", "7h 42m"])
        XCTAssertEqual(log.requests.filter { $0.url!.path == "/oauth/refresh" }.count, 1)
        XCTAssertEqual(store.tokens?.refreshToken, "refresh-new")
    }

    func testUnauthorizedResponsesRefreshAndRetryOnlyOnce() async throws {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(3_600), scope: "daily"))
        let log = OuraRequestLog()
        let connector = makeConnector(store: store) { request in
            log.record(request)
            if request.url!.path == "/oauth/refresh" { return (200, self.tokenJSON()) }
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer access-old" {
                return (401, #"{"detail":"expired"}"#)
            }
            return (200, self.collectionJSON(path: request.url!.path))
        }
        _ = try await connector.refresh()
        XCTAssertEqual(log.requests.filter { $0.url!.path == "/oauth/refresh" }.count, 1)
        XCTAssertEqual(store.tokens?.accessToken, "access-new")
    }

    func testRevokedRefreshTokenClearsLocalCredentials() async {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(-1), scope: "daily"))
        let connector = makeConnector(store: store) { _ in (400, #"{"error":"invalid_grant"}"#) }
        do {
            _ = try await connector.refresh()
            XCTFail("Refresh with a revoked grant should fail")
        } catch {
            XCTAssertEqual(error as? OuraError, .notConnected)
        }
        XCTAssertNil(store.tokens)
    }

    func testDisconnectDeletesCredentialsEvenWhenRevokeIsUnavailable() async {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(3_600), scope: "daily"))
        let auth = OuraMockAuthenticator()
        let connector = makeConnector(store: store, authenticator: auth) { _ in (503, #"{"error":"service_unavailable"}"#) }
        let result = await connector.disconnect()
        XCTAssertNil(store.tokens)
        XCTAssertEqual(auth.cancelCount, 1)
        XCTAssertTrue(result.localCredentialsCleared)
        XCTAssertEqual(result.remoteAuthorization, .unconfirmed)
        XCTAssertNotNil(result.warningMessage)
    }

    func testMissingConfigurationFailsBeforeAnyRequest() async {
        let connector = OuraAPIConnector(configuration: OuraConfiguration(brokerURL: nil),
                                          tokenStorage: OuraMemoryTokenStore(), authenticator: OuraMockAuthenticator())
        do {
            try await connector.authorize()
            XCTFail("A missing broker should not authorize")
        } catch {
            XCTAssertEqual(error as? OuraError, .notConfigured)
        }
    }

    func testWidgetReaderNeedsAnUnexpiredSavedToken() {
        let configuration = OuraConfiguration(brokerURL: URL(string: "https://broker.example.com"))
        let valid = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(3_600), scope: "daily"))
        let expiring = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                              expiresAt: date.addingTimeInterval(60), scope: "daily"))
        XCTAssertNotNil(OuraAPIConnector.widgetReader(configuration: configuration, tokenStorage: valid, now: { self.date }))
        XCTAssertNil(OuraAPIConnector.widgetReader(configuration: configuration, tokenStorage: expiring, now: { self.date }))
        XCTAssertNil(OuraAPIConnector.widgetReader(configuration: configuration, tokenStorage: OuraMemoryTokenStore(), now: { self.date }))
        XCTAssertNil(OuraAPIConnector.widgetReader(configuration: OuraConfiguration(brokerURL: nil), tokenStorage: valid, now: { self.date }))
    }

    func testWidgetReaderReadsMetricsWithTheSavedAccessToken() async throws {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(3_600), scope: "daily"))
        let reader = try XCTUnwrap(makeWidgetReader(store: store) { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-old")
            return (200, self.collectionJSON(path: request.url!.path))
        })
        let metrics = try await reader.refresh()
        XCTAssertEqual(metrics.map(\.value), ["86", "7h 42m"])
        XCTAssertEqual(store.saveCount, 0)
    }

    func testWidgetReaderNeverRefreshesOrDeletesCredentials() async throws {
        let store = OuraMemoryTokenStore(tokens: OuraTokens(accessToken: "access-old", refreshToken: "refresh-old",
                                                           expiresAt: date.addingTimeInterval(3_600), scope: "daily"))
        let log = OuraRequestLog()
        let reader = try XCTUnwrap(makeWidgetReader(store: store) { request in
            log.record(request)
            return (401, #"{"detail":"expired"}"#)
        })
        do {
            _ = try await reader.refresh()
            XCTFail("A rejected token should fail the widget read")
        } catch {
            XCTAssertEqual(error as? OuraError, .serviceUnavailable)
        }
        XCTAssertFalse(log.requests.contains { $0.url!.path.hasPrefix("/oauth") }, "Only the app talks to the broker")
        XCTAssertEqual(store.saveCount, 0)
        XCTAssertEqual(store.deleteCount, 0)
        XCTAssertEqual(store.tokens?.refreshToken, "refresh-old")
    }

    private func makeWidgetReader(store: OuraMemoryTokenStore,
                                  handler: @escaping (URLRequest) throws -> (Int, String)) -> OuraAPIConnector? {
        OuraTestURLProtocol.setHandler(handler)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [OuraTestURLProtocol.self]
        return OuraAPIConnector.widgetReader(
            configuration: OuraConfiguration(brokerURL: URL(string: "https://broker.example.com")),
            tokenStorage: store, session: URLSession(configuration: config), now: { self.date }
        )
    }

    private func makeConnector(store: OuraMemoryTokenStore,
                               authenticator: OuraMockAuthenticator? = nil,
                               handler: @escaping (URLRequest) throws -> (Int, String)) -> OuraAPIConnector {
        OuraTestURLProtocol.setHandler(handler)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [OuraTestURLProtocol.self]
        return OuraAPIConnector(configuration: OuraConfiguration(brokerURL: URL(string: "https://broker.example.com")),
                                 session: URLSession(configuration: config), tokenStorage: store,
                                 authenticator: authenticator ?? OuraMockAuthenticator(), now: { self.date })
    }

    private func authorizationRequest() -> OuraAuthorizationRequest {
        OuraAuthorizationRequest(state: "expected-state", codeVerifier: String(repeating: "a", count: 43), codeChallenge: "challenge")
    }

    private nonisolated func json(_ value: String) -> Data { Data(value.utf8) }

    private nonisolated func bodyData(_ request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var body = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            body.append(buffer, count: count)
        }
        return body
    }

    private nonisolated func tokenJSON() -> String {
        #"{"access_token":"access-new","refresh_token":"refresh-new","expires_in":3600,"token_type":"Bearer","scope":"daily"}"#
    }

    private nonisolated func collectionJSON(path: String) -> String {
        if path.hasSuffix("daily_readiness") {
            return #"{"data":[{"day":"2026-10-09","score":86,"timestamp":"2026-10-09T09:00:00+03:00"}]}"#
        }
        if path.hasSuffix("daily_sleep") {
            return #"{"data":[{"day":"2026-10-09","score":91}]}"#
        }
        return #"{"data":[{"day":"2026-10-09","type":"long_sleep","total_sleep_duration":27720,"bedtime_end":"2026-10-09T08:10:00+03:00"}]}"#
    }
}

final class OuraMemoryTokenStore: OuraTokenStoring {
    var tokens: OuraTokens?
    var loadError: Error?
    var deleteError: Error?
    private(set) var saveCount = 0
    private(set) var deleteCount = 0
    init(tokens: OuraTokens? = nil) { self.tokens = tokens }
    func load() throws -> OuraTokens? {
        if let loadError { throw loadError }
        return tokens
    }
    func save(_ tokens: OuraTokens) throws {
        saveCount += 1
        self.tokens = tokens
    }
    func delete() throws {
        deleteCount += 1
        if let deleteError { throw deleteError }
        tokens = nil
    }
}

@MainActor
final class OuraMockAuthenticator: OuraAuthenticating {
    private(set) var cancelCount = 0
    func authenticate(configuration: OuraPublicConfiguration) async throws -> OuraAuthorizationGrant {
        OuraAuthorizationGrant(code: "provider-code", codeVerifier: String(repeating: "a", count: 43))
    }
    func cancel() async { cancelCount += 1 }
}

final class OuraRequestLog {
    private let lock = NSLock()
    private var values: [URLRequest] = []
    var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
    func record(_ request: URLRequest) {
        lock.lock()
        defer { lock.unlock() }
        values.append(request)
    }
}

final class OuraTestURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var handler: ((URLRequest) throws -> (Int, String))?

    static func setHandler(_ handler: @escaping (URLRequest) throws -> (Int, String)) {
        lock.lock()
        defer { lock.unlock() }
        self.handler = handler
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let handler = Self.handler
        Self.lock.unlock()
        guard let handler else {
            client?.urlProtocol(self, didFailWithError: OuraError.serviceUnavailable)
            return
        }
        do {
            let (status, body) = try handler(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    override func stopLoading() {}
}
