import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

struct OuraDailyReadiness: Decodable {
    let day: String
    let score: Int?
    let timestamp: String?
}

struct OuraDailySleep: Decodable {
    let day: String
    let score: Int?
    let timestamp: String?
}

struct OuraSleepPeriod: Decodable {
    let day: String
    let type: String?
    let totalSleepDuration: Int?
    let bedtimeEnd: String?

    enum CodingKeys: String, CodingKey {
        case day, type
        case totalSleepDuration = "total_sleep_duration"
        case bedtimeEnd = "bedtime_end"
    }
}

struct OuraCollectionPage<Record: Decodable>: Decodable {
    let data: [Record]
    let nextToken: String?

    enum CodingKeys: String, CodingKey {
        case data
        case nextToken = "next_token"
    }
}

struct OuraTokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String?
    let expiresIn: TimeInterval
    let tokenType: String
    let scope: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
        case tokenType = "token_type"
        case scope
    }

    func tokens(at now: Date, previousRefreshToken: String? = nil) throws -> OuraTokens {
        let refresh = refreshToken ?? previousRefreshToken
        guard tokenType.lowercased() == "bearer", expiresIn > 0, expiresIn.isFinite,
              !accessToken.isEmpty, let refresh, !refresh.isEmpty,
              !accessToken.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !refresh.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw OuraError.invalidResponse
        }
        return OuraTokens(accessToken: accessToken, refreshToken: refresh,
                          expiresAt: now.addingTimeInterval(expiresIn), scope: scope)
    }
}

/// Redirects are unnecessary for fixed API endpoints and must never forward token requests.
private final class OuraNoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class OuraAPIConnector: DashboardConnector {
    let source: DataSource = .oura
    private let configuration: OuraConfiguration
    private let session: URLSession
    private let tokenStorage: any OuraTokenStoring
    private let authenticator: any OuraAuthenticating
    private let now: () -> Date
    private var tokens: OuraTokens?
    private var didLoadTokens = false
    private var generation = 0
    private var refreshTask: Task<OuraTokens, Error>?

    init(configuration: OuraConfiguration = .current,
         session: URLSession? = nil,
         tokenStorage: any OuraTokenStoring = OuraKeychainTokenStore(),
         authenticator: (any OuraAuthenticating)? = nil,
         now: @escaping () -> Date = Date.init) {
        self.configuration = configuration
        self.session = session ?? URLSession(configuration: .ephemeral,
                                            delegate: OuraNoRedirectDelegate(), delegateQueue: nil)
        self.tokenStorage = tokenStorage
        self.authenticator = authenticator ?? OuraOAuthAuthenticator()
        self.now = now
    }

    func authorize() async throws {
        guard configuration.isConfigured else { throw OuraError.notConfigured }
        generation += 1
        let operation = generation
        refreshTask?.cancel()
        refreshTask = nil
        let configURL = try configuration.brokerEndpoint("config")
        let publicData = try await send(URLRequest(url: configURL)).0
        let publicConfig = try decode(OuraPublicConfiguration.self, from: publicData).validated()
        try checkOperation(operation)
        let grant = try await authenticator.authenticate(configuration: publicConfig)
        try checkOperation(operation)
        let response = try await brokerRequest("oauth/exchange", body: [
            "code": grant.code,
            "code_verifier": grant.codeVerifier,
            "redirect_uri": OuraConfiguration.callbackURL.absoluteString
        ])
        let newTokens = try decode(OuraTokenResponse.self, from: response).tokens(at: now())
        try checkOperation(operation)
        try tokenStorage.save(newTokens)
        tokens = newTokens
        didLoadTokens = true
    }

    func refresh() async throws -> [DashboardMetric] {
        guard configuration.isConfigured else { throw OuraError.notConfigured }
        let operation = generation
        let date = now()
        async let readiness: [OuraDailyReadiness] = collection("daily_readiness", at: date)
        async let dailySleep: [OuraDailySleep] = collection("daily_sleep", at: date)
        async let sleep: [OuraSleepPeriod] = collection("sleep", at: date)
        let records = try await (readiness, dailySleep, sleep)
        try checkOperation(operation)
        return Self.dashboardMetrics(readiness: records.0, dailySleep: records.1, sleep: records.2)
    }

    func disconnect() async -> DisconnectResult {
        let rememberedAccessToken = tokens?.accessToken
        generation += 1
        refreshTask?.cancel()
        refreshTask = nil
        tokens = nil
        didLoadTokens = true

        // Read only for this revocation attempt; never retain a token to retry later.
        let oldToken: String?
        var credentialsReadFailed = false
        do {
            oldToken = try tokenStorage.load()?.accessToken ?? rememberedAccessToken
        } catch {
            oldToken = rememberedAccessToken
            credentialsReadFailed = true
        }
        let localCredentialsCleared: Bool
        do {
            try tokenStorage.delete()
            localCredentialsCleared = true
        } catch {
            localCredentialsCleared = false
        }
        await authenticator.cancel()

        let remoteAuthorization: DisconnectResult.RemoteAuthorization
        if let oldToken, configuration.isConfigured {
            do {
                _ = try await brokerRequest("oauth/revoke", body: ["access_token": oldToken], allowEmpty: true)
                remoteAuthorization = .revoked
            } catch {
                remoteAuthorization = .unconfirmed
            }
        } else if oldToken != nil || credentialsReadFailed {
            remoteAuthorization = .unconfirmed
        } else {
            remoteAuthorization = .noStoredCredentials
        }
        return DisconnectResult(localCredentialsCleared: localCredentialsCleared,
                                remoteAuthorization: remoteAuthorization)
    }

    private func checkOperation(_ operation: Int) throws {
        try Task.checkCancellation()
        guard operation == generation else { throw OuraError.cancelled }
    }

    private func loadTokens() throws -> OuraTokens? {
        if !didLoadTokens {
            tokens = try tokenStorage.load()
            didLoadTokens = true
        }
        return tokens
    }

    private func accessToken(rejected: String? = nil) async throws -> String {
        guard let current = try loadTokens() else { throw OuraError.notConnected }
        // Another request may already have rotated the token rejected by this request.
        if let rejected, rejected != current.accessToken { return current.accessToken }
        if rejected == nil && current.expiresAt.timeIntervalSince(now()) > 60 {
            return current.accessToken
        }
        if let refreshTask { return try await refreshTask.value.accessToken }
        let operation = generation
        let task = Task { @MainActor in
            do {
                let data = try await self.brokerRequest("oauth/refresh", body: ["refresh_token": current.refreshToken])
                let refreshed = try self.decode(OuraTokenResponse.self, from: data)
                    .tokens(at: self.now(), previousRefreshToken: current.refreshToken)
                try self.checkOperation(operation)
                try self.tokenStorage.save(refreshed)
                self.tokens = refreshed
                return refreshed
            } catch {
                if self.generation == operation, error as? OuraError == .notConnected {
                    self.tokens = nil
                    try? self.tokenStorage.delete()
                }
                throw error
            }
        }
        refreshTask = task
        defer { if generation == operation { refreshTask = nil } }
        return try await task.value.accessToken
    }

    private func collection<Record: Decodable>(_ name: String, at date: Date) async throws -> [Record] {
        let interval = Self.queryDates(at: date)
        var records: [Record] = []
        var nextToken: String?
        var seenTokens = Set<String>()
        repeat {
            var components = URLComponents(url: configuration.apiURL.appendingPathComponent(name), resolvingAgainstBaseURL: false)!
            components.queryItems = [URLQueryItem(name: "start_date", value: interval.start),
                                     URLQueryItem(name: "end_date", value: interval.end)]
            if let nextToken { components.queryItems?.append(URLQueryItem(name: "next_token", value: nextToken)) }
            guard let url = components.url else { throw OuraError.invalidResponse }
            let data = try await authenticatedData(url: url)
            let page = try decode(OuraCollectionPage<Record>.self, from: data)
            records.append(contentsOf: page.data)
            nextToken = page.nextToken.flatMap { $0.isEmpty ? nil : $0 }
            if let nextToken {
                guard seenTokens.insert(nextToken).inserted, seenTokens.count < 20 else {
                    throw OuraError.invalidResponse
                }
            }
        } while nextToken != nil
        return records
    }

    private func authenticatedData(url: URL) async throws -> Data {
        let operation = generation
        var access = try await accessToken()
        for attempt in 0...1 {
            try checkOperation(operation)
            var request = URLRequest(url: url)
            request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await send(request, allowUnauthorized: true)
            if response.statusCode != 401 { return data }
            if attempt == 0 {
                access = try await accessToken(rejected: access)
            } else {
                if tokens?.accessToken == access {
                    tokens = nil
                    try? tokenStorage.delete()
                }
                throw OuraError.notConnected
            }
        }
        throw OuraError.notConnected
    }

    private func brokerRequest(_ path: String, body: [String: String], allowEmpty: Bool = false) async throws -> Data {
        var request = URLRequest(url: try configuration.brokerEndpoint(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        let response = try await send(request, allowEmpty: allowEmpty, isTokenRequest: !allowEmpty)
        if allowEmpty && response.1.statusCode != 204 {
            throw OuraError.invalidResponse
        }
        return response.0
    }

    private func send(_ original: URLRequest, allowEmpty: Bool = false,
                      allowUnauthorized: Bool = false, isTokenRequest: Bool = false) async throws -> (Data, HTTPURLResponse) {
        var request = original
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let response = response as? HTTPURLResponse,
              response.url?.scheme?.lowercased() == "https",
              response.url?.host == original.url?.host,
              response.url?.port == original.url?.port else { throw OuraError.invalidResponse }
        switch response.statusCode {
        case 200: return (data, response)
        case 204 where allowEmpty: return (data, response)
        case 401 where allowUnauthorized: return (data, response)
        case 400 where isTokenRequest: throw OuraError.notConnected
        case 401: throw OuraError.notConnected
        case 403: throw OuraError.permissionRequired
        case 429: throw OuraError.rateLimited
        default: throw OuraError.serviceUnavailable
        }
    }

    private func decode<Value: Decodable>(_ type: Value.Type, from data: Data) throws -> Value {
        do { return try JSONDecoder().decode(type, from: data) }
        catch { throw OuraError.invalidResponse }
    }

    static func queryDates(at date: Date, calendar: Calendar = .current) -> (start: String, end: String) {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let formatter = DateFormatter()
        formatter.calendar = gregorian
        formatter.timeZone = gregorian.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let start = gregorian.date(byAdding: .day, value: -7, to: date) ?? date
        let end = gregorian.date(byAdding: .day, value: 1, to: date) ?? date
        return (formatter.string(from: start), formatter.string(from: end))
    }

    static func dashboardMetrics(readiness: [OuraDailyReadiness], dailySleep: [OuraDailySleep],
                                 sleep: [OuraSleepPeriod]) -> [DashboardMetric] {
        let latestReadiness = readiness.filter { validScore($0.score) != nil }.max { $0.day < $1.day }
        let readinessScore = validScore(latestReadiness?.score)
        let readinessMetric = DashboardMetric(
            id: "readiness", source: .oura, title: "Readiness",
            value: readinessScore.map(String.init) ?? "—",
            detail: latestReadiness.map { "Oura · \($0.day)" } ?? "No recent Oura readiness data",
            symbol: "sparkles", tint: "violet",
            numericValue: readinessScore.map(Double.init), targetValue: 100,
            observedAt: latestReadiness.flatMap { observationDate(timestamp: $0.timestamp, day: $0.day) },
            origin: "Oura account"
        )
        let summary = dailySleep.max { $0.day < $1.day }
        let mainSleeps = sleep.filter { $0.type == "long_sleep" && ($0.totalSleepDuration ?? 0) > 0 }
        let sleepDay = summary?.day ?? mainSleeps.map(\.day).max()
        // Pick the main period for the daily summary; naps and split records are never summed.
        let mainSleep = mainSleeps.filter { $0.day == sleepDay }.max {
            if $0.totalSleepDuration == $1.totalSleepDuration { return ($0.bedtimeEnd ?? "") < ($1.bedtimeEnd ?? "") }
            return ($0.totalSleepDuration ?? 0) < ($1.totalSleepDuration ?? 0)
        }
        let duration = mainSleep?.totalSleepDuration.flatMap { $0 > 0 && $0 <= 86_400 ? $0 : nil }
        let sleepScore = validScore(summary?.score)
        var sleepDetail = sleepScore.map { "Score \($0)" } ?? "Oura sleep"
        if let sleepDay { sleepDetail += " · \(sleepDay)" }
        if duration == nil && sleepScore == nil { sleepDetail = "No recent Oura sleep data" }
        let sleepMetric = DashboardMetric(
            id: "sleep", source: .oura, title: "Sleep",
            value: duration.map(formatDuration) ?? sleepScore.map(String.init) ?? "—",
            detail: duration == nil && sleepScore != nil ? "Sleep score · \(sleepDay ?? "")" : sleepDetail,
            symbol: "moon.stars.fill", tint: "indigo",
            numericValue: duration.map(Double.init) ?? sleepScore.map(Double.init),
            targetValue: duration != nil ? nil : 100,
            observedAt: sleepDay.flatMap { observationDate(timestamp: mainSleep?.bedtimeEnd ?? summary?.timestamp, day: $0) },
            origin: "Oura account"
        )
        return [readinessMetric, sleepMetric]
    }

    private static func validScore(_ value: Int?) -> Int? {
        value.flatMap { (0...100).contains($0) ? $0 : nil }
    }

    static func formatDuration(_ seconds: Int) -> String {
        let minutes = max(0, seconds) / 60
        return "\(minutes / 60)h \(minutes % 60)m"
    }

    static func observationDate(timestamp: String?, day: String) -> Date? {
        if let timestamp {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: timestamp) { return date }
            formatter.formatOptions = [.withInternetDateTime]
            if let date = formatter.date(from: timestamp) { return date }
        }
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        return formatter.date(from: day)
    }
}
