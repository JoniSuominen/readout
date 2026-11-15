import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

struct OuraDailyReadiness: Decodable {
    let day: String
    let score: Int?
    let timestamp: String?
    var temperatureDeviation: Double? = nil

    enum CodingKeys: String, CodingKey {
        case day, score, timestamp
        case temperatureDeviation = "temperature_deviation"
    }
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
    var deepSleepDuration: Int? = nil
    var remSleepDuration: Int? = nil
    var lightSleepDuration: Int? = nil
    var efficiency: Int? = nil
    var latency: Int? = nil
    var averageHRV: Double? = nil
    var lowestHeartRate: Int? = nil
    var averageBreath: Double? = nil

    enum CodingKeys: String, CodingKey {
        case day, type, efficiency, latency
        case totalSleepDuration = "total_sleep_duration"
        case bedtimeEnd = "bedtime_end"
        case deepSleepDuration = "deep_sleep_duration"
        case remSleepDuration = "rem_sleep_duration"
        case lightSleepDuration = "light_sleep_duration"
        case averageHRV = "average_hrv"
        case lowestHeartRate = "lowest_heart_rate"
        case averageBreath = "average_breath"
    }
}

struct OuraDailyActivity: Decodable {
    let day: String
    var score: Int? = nil
    var steps: Int? = nil
    var activeCalories: Int? = nil

    enum CodingKeys: String, CodingKey {
        case day, score, steps
        case activeCalories = "active_calories"
    }
}

struct OuraDailyStress: Decodable {
    let day: String
    var stressHigh: Int? = nil
    var recoveryHigh: Int? = nil
    var daySummary: String? = nil

    enum CodingKeys: String, CodingKey {
        case day
        case stressHigh = "stress_high"
        case recoveryHigh = "recovery_high"
        case daySummary = "day_summary"
    }
}

struct OuraDailyResilience: Decodable {
    let day: String
    var level: String? = nil
}

struct OuraDailySpO2: Decodable {
    struct Percentage: Decodable { var average: Double? = nil }
    let day: String
    var spo2Percentage: Percentage? = nil
    var breathingDisturbanceIndex: Int? = nil

    enum CodingKeys: String, CodingKey {
        case day
        case spo2Percentage = "spo2_percentage"
        case breathingDisturbanceIndex = "breathing_disturbance_index"
    }
}

struct OuraSleepTime: Decodable {
    struct Window: Decodable {
        var startOffset: Int? = nil
        var endOffset: Int? = nil

        enum CodingKeys: String, CodingKey {
            case startOffset = "start_offset"
            case endOffset = "end_offset"
        }
    }
    let day: String
    var optimalBedtime: Window? = nil

    enum CodingKeys: String, CodingKey {
        case day
        case optimalBedtime = "optimal_bedtime"
    }
}

struct OuraCardiovascularAge: Decodable {
    let day: String
    var vascularAge: Int? = nil

    enum CodingKeys: String, CodingKey {
        case day
        case vascularAge = "vascular_age"
    }
}

struct OuraVO2Max: Decodable {
    let day: String
    var vo2Max: Double? = nil

    enum CodingKeys: String, CodingKey {
        case day
        case vo2Max = "vo2_max"
    }
}

/// Oura values beyond readiness and sleep. Each list can be empty: the
/// account may not have granted that scope, or the ring has no reading yet.
struct OuraExtraRecords {
    var activity: [OuraDailyActivity] = []
    var stress: [OuraDailyStress] = []
    var resilience: [OuraDailyResilience] = []
    var spo2: [OuraDailySpO2] = []
    var sleepTime: [OuraSleepTime] = []
    var cardiovascularAge: [OuraCardiovascularAge] = []
    var vo2Max: [OuraVO2Max] = []
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
    /// Only the app can show the Oura sign-in sheet.
    private let authenticator: (any OuraAuthenticating)?
    /// The widget reads with the saved access token and never signs in,
    /// rotates the single-use refresh token, or deletes credentials.
    private let readOnly: Bool
    private let now: () -> Date
    private var tokens: OuraTokens?
    private var didLoadTokens = false
    private var generation = 0
    private var refreshTask: Task<OuraTokens, Error>?

    init(configuration: OuraConfiguration = .current,
         session: URLSession? = nil,
         tokenStorage: any OuraTokenStoring = OuraKeychainTokenStore(),
         authenticator: (any OuraAuthenticating)? = nil,
         readOnly: Bool = false,
         now: @escaping () -> Date = Date.init) {
        self.configuration = configuration
        self.session = session ?? URLSession(configuration: .ephemeral,
                                            delegate: OuraNoRedirectDelegate(), delegateQueue: nil)
        self.tokenStorage = tokenStorage
        self.authenticator = authenticator
        self.readOnly = readOnly
        self.now = now
    }

    /// A reader for the widget, or nil unless the app saved an access token
    /// that stays valid long enough to finish a fetch. Opening the app renews
    /// an expired token.
    static func widgetReader(
        configuration: OuraConfiguration = .current,
        tokenStorage: any OuraTokenStoring = OuraKeychainTokenStore(),
        session: URLSession? = nil,
        now: @escaping () -> Date = Date.init
    ) -> OuraAPIConnector? {
        guard configuration.isConfigured, let tokens = try? tokenStorage.load(),
              tokens.expiresAt.timeIntervalSince(now()) > 120 else { return nil }
        return OuraAPIConnector(configuration: configuration, session: session,
                                tokenStorage: tokenStorage, readOnly: true, now: now)
    }

    func authorize() async throws {
        guard configuration.isConfigured, !readOnly, let authenticator else { throw OuraError.notConfigured }
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
        // Extras run after the core fetch so a token refresh happens once,
        // and a missing scope or empty endpoint never fails the source.
        async let activity: [OuraDailyActivity] = optionalCollection("daily_activity", at: date)
        async let stress: [OuraDailyStress] = optionalCollection("daily_stress", at: date)
        async let resilience: [OuraDailyResilience] = optionalCollection("daily_resilience", at: date)
        async let spo2: [OuraDailySpO2] = optionalCollection("daily_spo2", at: date)
        async let sleepTime: [OuraSleepTime] = optionalCollection("sleep_time", at: date)
        // Heart health values update every few weeks, not daily.
        async let cardio: [OuraCardiovascularAge] = optionalCollection("daily_cardiovascular_age", at: date, days: 60)
        async let vo2: [OuraVO2Max] = optionalCollection("vO2_max", at: date, days: 60)
        let extras = await OuraExtraRecords(activity: activity, stress: stress, resilience: resilience, spo2: spo2,
                                            sleepTime: sleepTime, cardiovascularAge: cardio, vo2Max: vo2)
        try checkOperation(operation)
        return Self.dashboardMetrics(readiness: records.0, dailySleep: records.1, sleep: records.2)
            + Self.extraMetrics(readiness: records.0, dailySleep: records.1, sleep: records.2, extras: extras, now: date)
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
        await authenticator?.cancel()

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
        // Two processes must never spend the same single-use refresh token.
        guard !readOnly else { throw OuraError.serviceUnavailable }
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

    /// Empty instead of an error: the scope may not be granted, and an
    /// optional endpoint must never refresh or delete the saved token.
    private func optionalCollection<Record: Decodable>(_ name: String, at date: Date, days: Int = 7) async -> [Record] {
        (try? await collection(name, at: date, days: days, optional: true)) ?? []
    }

    private func collection<Record: Decodable>(_ name: String, at date: Date, days: Int = 7,
                                               optional: Bool = false) async throws -> [Record] {
        let interval = Self.queryDates(at: date, days: days)
        var records: [Record] = []
        var nextToken: String?
        var seenTokens = Set<String>()
        repeat {
            var components = URLComponents(url: configuration.apiURL.appendingPathComponent(name), resolvingAgainstBaseURL: false)!
            components.queryItems = [URLQueryItem(name: "start_date", value: interval.start),
                                     URLQueryItem(name: "end_date", value: interval.end)]
            if let nextToken { components.queryItems?.append(URLQueryItem(name: "next_token", value: nextToken)) }
            guard let url = components.url else { throw OuraError.invalidResponse }
            let data = try await authenticatedData(url: url, optional: optional)
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

    private func authenticatedData(url: URL, optional: Bool = false) async throws -> Data {
        let operation = generation
        var access = try await accessToken()
        for attempt in 0...1 {
            try checkOperation(operation)
            var request = URLRequest(url: url)
            request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await send(request, allowUnauthorized: true)
            if response.statusCode != 401 { return data }
            if optional { throw OuraError.permissionRequired }
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

    static func queryDates(at date: Date, days: Int = 7, calendar: Calendar = .current) -> (start: String, end: String) {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let formatter = DateFormatter()
        formatter.calendar = gregorian
        formatter.timeZone = gregorian.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let start = gregorian.date(byAdding: .day, value: -days, to: date) ?? date
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
        let (sleepDay, mainSleep) = latestMainSleep(dailySleep: dailySleep, sleep: sleep)
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

    /// The latest night and its main period; naps and split records are never summed.
    static func latestMainSleep(dailySleep: [OuraDailySleep], sleep: [OuraSleepPeriod]) -> (day: String?, period: OuraSleepPeriod?) {
        let summary = dailySleep.max { $0.day < $1.day }
        let mainSleeps = sleep.filter { $0.type == "long_sleep" && ($0.totalSleepDuration ?? 0) > 0 }
        let sleepDay = summary?.day ?? mainSleeps.map(\.day).max()
        let period = mainSleeps.filter { $0.day == sleepDay }.max {
            if $0.totalSleepDuration == $1.totalSleepDuration { return ($0.bedtimeEnd ?? "") < ($1.bedtimeEnd ?? "") }
            return ($0.totalSleepDuration ?? 0) < ($1.totalSleepDuration ?? 0)
        }
        return (sleepDay, period)
    }

    /// Tiles beyond readiness and sleep. A value Oura did not send produces
    /// no tile rather than a placeholder.
    static func extraMetrics(readiness: [OuraDailyReadiness], dailySleep: [OuraDailySleep],
                             sleep: [OuraSleepPeriod], extras: OuraExtraRecords,
                             now: Date = .now, temperatureUnit: TemperatureUnit = UnitPreferences.temperature,
                             locale: Locale = .current) -> [DashboardMetric] {
        var metrics: [DashboardMetric] = []
        func add(_ id: String, _ title: String, value: String, detail: String, symbol: String, tint: String,
                 numeric: Double? = nil, target: Double? = nil, day: String) {
            metrics.append(DashboardMetric(
                id: id, source: .oura, title: title, value: value, detail: detail, symbol: symbol, tint: tint,
                numericValue: numeric, targetValue: target,
                observedAt: observationDate(timestamp: nil, day: day), origin: "Oura account"))
        }

        // Sleep details from the main period of the latest night.
        let (sleepDay, period) = latestMainSleep(dailySleep: dailySleep, sleep: sleep)
        if let score = validScore(dailySleep.max(by: { $0.day < $1.day })?.score), let sleepDay {
            add("ouraSleepScore", "Sleep score", value: "\(score)", detail: dayText(sleepDay, now: now),
                symbol: "bed.double.fill", tint: "indigo", numeric: Double(score), target: 100, day: sleepDay)
        }
        if let period, let sleepDay {
            let total = period.totalSleepDuration ?? 0
            func share(_ seconds: Int) -> String {
                total > 0 ? "\(Int((Double(seconds) / Double(total) * 100).rounded()))% of sleep" : dayText(sleepDay, now: now)
            }
            if let deep = period.deepSleepDuration, (1...86_400).contains(deep) {
                add("ouraDeepSleep", "Deep sleep", value: formatDuration(deep), detail: share(deep),
                    symbol: "moon.zzz.fill", tint: "indigo", numeric: Double(deep), day: sleepDay)
            }
            if let rem = period.remSleepDuration, (1...86_400).contains(rem) {
                add("ouraRemSleep", "REM sleep", value: formatDuration(rem), detail: share(rem),
                    symbol: "moon.stars", tint: "violet", numeric: Double(rem), day: sleepDay)
            }
            if let efficiency = period.efficiency, (0...100).contains(efficiency) {
                let detail = period.latency.map { "Fell asleep in \(max(0, $0) / 60) min" } ?? dayText(sleepDay, now: now)
                add("ouraEfficiency", "Sleep efficiency", value: "\(efficiency)%", detail: detail,
                    symbol: "chart.bar.fill", tint: "indigo", numeric: Double(efficiency), target: 100, day: sleepDay)
            }
            if let lowest = period.lowestHeartRate, (20...200).contains(lowest) {
                add("ouraLowestHeartRate", "Lowest heart rate", value: "\(lowest)", detail: "bpm overnight",
                    symbol: "heart.fill", tint: "pink", numeric: Double(lowest), day: sleepDay)
            }
            if let hrv = period.averageHRV, hrv > 0, hrv < 500 {
                add("ouraOvernightHRV", "Overnight HRV", value: "\(Int(hrv.rounded()))", detail: "ms average",
                    symbol: "waveform.path.ecg", tint: "pink", numeric: hrv, day: sleepDay)
            }
            if let breath = period.averageBreath, breath > 0, breath < 60 {
                add("ouraBreathingRate", "Breathing rate", value: breath.formatted(.number.precision(.fractionLength(1))),
                    detail: "breaths/min overnight", symbol: "lungs.fill", tint: "teal", numeric: breath, day: sleepDay)
            }
        }

        if let latest = readiness.filter({ $0.temperatureDeviation != nil }).max(by: { $0.day < $1.day }),
           let celsius = latest.temperatureDeviation, abs(celsius) < 10 {
            let fahrenheit = temperatureUnit == .fahrenheit
                || (temperatureUnit == .automatic && locale.measurementSystem == .us)
            let delta = fahrenheit ? celsius * 9 / 5 : celsius
            let value = abs(delta) < 0.05 ? "0.0°" : String(format: "%+.1f°", delta)
            add("ouraTemperature", "Body temperature", value: value, detail: "vs. your baseline",
                symbol: "thermometer.variable", tint: "orange", numeric: delta, day: latest.day)
        }

        if let latest = extras.activity.filter({ validScore($0.score) != nil }).max(by: { $0.day < $1.day }),
           let score = validScore(latest.score) {
            add("ouraActivityScore", "Activity score", value: "\(score)", detail: dayText(latest.day, now: now),
                symbol: "flame.circle.fill", tint: "green", numeric: Double(score), target: 100, day: latest.day)
        }
        if let latest = extras.activity.filter({ $0.steps != nil }).max(by: { $0.day < $1.day }),
           let steps = latest.steps, steps >= 0 {
            add("ouraSteps", "Oura steps", value: steps.formatted(), detail: dayText(latest.day, now: now),
                symbol: "figure.walk", tint: "mint", numeric: Double(steps), day: latest.day)
        }
        if let latest = extras.activity.filter({ $0.activeCalories != nil }).max(by: { $0.day < $1.day }),
           let calories = latest.activeCalories, calories >= 0 {
            add("ouraActiveCalories", "Oura active calories", value: calories.formatted(),
                detail: "kcal · \(dayText(latest.day, now: now).lowercased())",
                symbol: "flame.fill", tint: "red", numeric: Double(calories), day: latest.day)
        }

        if let latest = extras.stress.filter({ $0.daySummary != nil || $0.stressHigh != nil }).max(by: { $0.day < $1.day }) {
            let summary: String
            switch latest.daySummary {
            case "restored": summary = "Restored"
            case "normal": summary = "Normal"
            case "stressful": summary = "Stressful"
            default: summary = "—"
            }
            let parts = [latest.stressHigh.map { "Stressed \(minutesText($0))" },
                         latest.recoveryHigh.map { "restored \(minutesText($0))" }].compactMap { $0 }
            add("ouraStress", "Stress", value: summary,
                detail: parts.isEmpty ? dayText(latest.day, now: now) : parts.joined(separator: " · "),
                symbol: "brain.head.profile", tint: "orange", day: latest.day)
        }
        if let latest = extras.resilience.filter({ $0.level != nil }).max(by: { $0.day < $1.day }),
           let level = latest.level, !level.isEmpty {
            add("ouraResilience", "Resilience", value: level.prefix(1).uppercased() + level.dropFirst(),
                detail: dayText(latest.day, now: now), symbol: "shield.lefthalf.filled", tint: "teal", day: latest.day)
        }
        if let latest = extras.sleepTime.filter({ $0.optimalBedtime?.startOffset != nil }).max(by: { $0.day < $1.day }),
           let window = latest.optimalBedtime, let start = window.startOffset {
            let value = [Optional(start), window.endOffset].compactMap { $0 }
                .map { bedtimeText($0, now: now) }.joined(separator: "–")
            add("ouraBedtime", "Optimal bedtime", value: value, detail: "Oura recommendation",
                symbol: "bed.double.circle.fill", tint: "indigo", day: latest.day)
        }

        if let latest = extras.spo2.filter({ $0.spo2Percentage?.average != nil }).max(by: { $0.day < $1.day }),
           let average = latest.spo2Percentage?.average, (50...100).contains(average) {
            let detail = latest.breathingDisturbanceIndex.map { "Breathing disturbance \($0)" } ?? "Overnight average"
            add("ouraSpO2", "Blood oxygen", value: "\(average.formatted(.number.precision(.fractionLength(0...1))))%",
                detail: detail, symbol: "drop.degreesign.fill", tint: "red", numeric: average, target: 100, day: latest.day)
        }
        if let latest = extras.cardiovascularAge.filter({ $0.vascularAge != nil }).max(by: { $0.day < $1.day }),
           let age = latest.vascularAge, (10...120).contains(age) {
            add("ouraCardioAge", "Cardiovascular age", value: "\(age)", detail: "years",
                symbol: "heart.text.square.fill", tint: "pink", numeric: Double(age), day: latest.day)
        }
        if let latest = extras.vo2Max.filter({ $0.vo2Max != nil }).max(by: { $0.day < $1.day }),
           let vo2 = latest.vo2Max, vo2 > 0, vo2 < 100 {
            add("ouraVO2Max", "VO2 max", value: "\(Int(vo2.rounded()))", detail: "ml/kg/min",
                symbol: "lungs.fill", tint: "green", numeric: vo2, day: latest.day)
        }
        return metrics
    }

    /// "Today", "Yesterday", or a short date such as "12 Nov".
    static func dayText(_ day: String, now: Date, calendar: Calendar = .current) -> String {
        guard let date = observationDate(timestamp: nil, day: day) else { return day }
        if calendar.isDate(date, inSameDayAs: now) { return "Today" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) { return "Yesterday" }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }

    /// "45m" or "1h 20m" for a duration in seconds.
    static func minutesText(_ seconds: Int) -> String {
        let minutes = max(0, seconds) / 60
        return minutes < 60 ? "\(minutes)m" : formatDuration(seconds)
    }

    /// A clock time for an offset in seconds from midnight; negative is the evening before.
    static func bedtimeText(_ offset: Int, now: Date, calendar: Calendar = .current) -> String {
        let seconds = ((offset % 86_400) + 86_400) % 86_400
        let date = calendar.startOfDay(for: now).addingTimeInterval(TimeInterval(seconds))
        return date.formatted(date: .omitted, time: .shortened)
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
