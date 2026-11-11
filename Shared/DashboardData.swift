import Foundation

enum DataSource: String, Codable, CaseIterable, Identifiable {
    case weather = "Weather"
    case appleHealth = "Apple Health"
    case oura = "Oura"
    /// The raw value is the stored key from when only Google was planned; keep it.
    case calendar = "Google Calendar"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .calendar: return "Calendar"
        default: return rawValue
        }
    }

    var symbol: String {
        switch self {
        case .weather: return "cloud.sun.fill"
        case .appleHealth: return "heart.fill"
        case .oura: return "circle.hexagongrid.fill"
        case .calendar: return "calendar"
        }
    }
}

/// One line of the agenda tile. The title is kept only when the user allows
/// event titles; widget privacy projection removes it for discreet widgets.
struct AgendaItem: Codable, Hashable {
    let startsAt: Date
    let endsAt: Date
    let isAllDay: Bool
    let title: String?
}

struct DashboardMetric: Codable, Identifiable, Hashable {
    let id: String
    let source: DataSource
    let title: String
    let value: String
    let detail: String
    let symbol: String
    let tint: String

    // Numeric metadata belongs to the source. Never infer a gauge from a
    // formatted value such as "7h 42m" or "8,420".
    let numericValue: Double?
    let targetValue: Double?
    let observedAt: Date?
    /// Calendar title kept only when the user explicitly enables titles.
    /// Widget privacy projection must remove it before rendering a discreet widget.
    let privateValue: String?
    /// End of a calendar event, used to show "Now" and to reload when it ends.
    let endsAt: Date?
    /// When the value stops describing the present: midnight for today's
    /// steps and forecast, the end time for an event. Cached metrics are
    /// dropped from this instant on.
    let expiresAt: Date?
    /// The app or device behind the value, such as "Oura" or a watch name.
    /// App only: the widget privacy projection drops it.
    let origin: String?
    /// Upcoming events for the agenda tile, soonest first.
    let items: [AgendaItem]?

    var originLabel: String {
        origin ?? (source == .weather ? "Open-Meteo" : source.displayName)
    }

    func isExpired(at date: Date) -> Bool {
        guard let expiresAt else { return false }
        return expiresAt <= date
    }

    var displayValue: String { privateValue ?? value }

    init(
        id: String,
        source: DataSource,
        title: String,
        value: String,
        detail: String,
        symbol: String,
        tint: String,
        numericValue: Double? = nil,
        targetValue: Double? = nil,
        observedAt: Date? = nil,
        privateValue: String? = nil,
        endsAt: Date? = nil,
        expiresAt: Date? = nil,
        origin: String? = nil,
        items: [AgendaItem]? = nil
    ) {
        self.id = id
        self.source = source
        self.title = title
        self.value = value
        self.detail = detail
        self.symbol = symbol
        self.tint = tint
        self.numericValue = numericValue
        self.targetValue = targetValue
        self.observedAt = observedAt
        self.privateValue = privateValue
        self.endsAt = endsAt
        self.expiresAt = expiresAt
        self.origin = origin
        self.items = items
    }

    /// A normalized, finite goal or score. Metrics without a meaningful target
    /// (sleep duration, heart rate, events) use a text presentation instead.
    var gaugeProgress: Double? {
        guard let numericValue, let targetValue,
              numericValue.isFinite, targetValue.isFinite,
              numericValue >= 0, targetValue > 0 else { return nil }
        return min(numericValue / targetValue, 1)
    }

    /// Illustrative data for WidgetKit placeholders and previews only.
    static let defaults: [DashboardMetric] = [
        .init(id: "temperature", source: .weather, title: "Now", value: "14°", detail: "Partly cloudy · 17° / 9°", symbol: "cloud.sun.fill", tint: "orange", numericValue: 14),
        .init(id: "uv", source: .weather, title: "UV index", value: "5", detail: "Moderate · peak 6 at 13:00", symbol: "sun.max.fill", tint: "yellow", numericValue: 5, targetValue: 11),
        .init(id: "rain", source: .weather, title: "Rain today", value: "2.4 mm", detail: "60% · most likely 15:00", symbol: "drop.fill", tint: "blue", numericValue: 2.4),
        .init(id: "tomorrow", source: .weather, title: "Tomorrow", value: "12°", detail: "Rain · 15° / 8°", symbol: "cloud.rain.fill", tint: "teal", numericValue: 12),
        .init(id: "feelsLike", source: .weather, title: "Feels like", value: "12°", detail: "Actual 14°", symbol: "thermometer.medium", tint: "orange", numericValue: 12),
        .init(id: "wind", source: .weather, title: "Wind", value: "5 m/s", detail: "from NW · gusts 9 m/s", symbol: "wind", tint: "teal", numericValue: 5),
        .init(id: "humidity", source: .weather, title: "Humidity", value: "62%", detail: "Relative humidity", symbol: "humidity.fill", tint: "blue", numericValue: 62, targetValue: 100),
        .init(id: "sun", source: .weather, title: "Sunset", value: "21:48", detail: "Sunrise 05:12", symbol: "sunset.fill", tint: "yellow"),
        .init(id: "airQuality", source: .weather, title: "Air quality", value: "18", detail: "Good · PM2.5 4", symbol: "aqi.medium", tint: "green", numericValue: 18, targetValue: 100),
        .init(id: "readiness", source: .oura, title: "Readiness", value: "86", detail: "Optimal", symbol: "sparkles", tint: "violet", numericValue: 86, targetValue: 100),
        .init(id: "sleep", source: .oura, title: "Sleep", value: "7h 42m", detail: "Score 91", symbol: "moon.stars.fill", tint: "indigo"),
        .init(id: "steps", source: .appleHealth, title: "Steps", value: "8,420", detail: "84% of goal", symbol: "figure.walk", tint: "mint", numericValue: 8420, targetValue: 10000),
        .init(id: "heart", source: .appleHealth, title: "Resting heart rate", value: "62", detail: "bpm resting", symbol: "heart.fill", tint: "pink"),
        .init(id: "heartRate", source: .appleHealth, title: "Heart rate", value: "71", detail: "bpm · 14:05", symbol: "heart.fill", tint: "pink"),
        .init(id: "hrv", source: .appleHealth, title: "HRV", value: "48", detail: "ms · 07:12", symbol: "heart.fill", tint: "pink"),
        .init(id: "walkingHeartRate", source: .appleHealth, title: "Walking heart rate", value: "98", detail: "bpm · 31 Oct", symbol: "heart.fill", tint: "pink"),
        .init(id: "activeEnergy", source: .appleHealth, title: "Active energy", value: "420", detail: "kcal today", symbol: "flame.fill", tint: "red", numericValue: 420),
        .init(id: "exercise", source: .appleHealth, title: "Exercise", value: "24", detail: "min today · goal 30", symbol: "figure.run", tint: "green", numericValue: 24, targetValue: 30),
        .init(id: "distance", source: .appleHealth, title: "Distance", value: "5.2 km", detail: "Walking and running today", symbol: "figure.walk.motion", tint: "mint", numericValue: 5200),
        .init(id: "workout", source: .appleHealth, title: "Last workout", value: "45m", detail: "Running · 07:30", symbol: "dumbbell.fill", tint: "green", numericValue: 2700),
        .init(id: "nextEvent", source: .calendar, title: "Up next", value: "Busy", detail: "", symbol: "calendar", tint: "blue",
              observedAt: Date.now.addingTimeInterval(24 * 60), privateValue: "Design sync", endsAt: Date.now.addingTimeInterval(54 * 60)),
        .init(id: "agenda", source: .calendar, title: "Agenda", value: "Busy", detail: "", symbol: "list.bullet", tint: "blue",
              observedAt: Date.now.addingTimeInterval(24 * 60), items: [
                AgendaItem(startsAt: Date.now.addingTimeInterval(24 * 60), endsAt: Date.now.addingTimeInterval(54 * 60), isAllDay: false, title: "Design sync"),
                AgendaItem(startsAt: Date.now.addingTimeInterval(150 * 60), endsAt: Date.now.addingTimeInterval(210 * 60), isAllDay: false, title: "Lunch"),
                AgendaItem(startsAt: Date.now.addingTimeInterval(300 * 60), endsAt: Date.now.addingTimeInterval(330 * 60), isAllDay: false, title: "Dentist")
              ])
    ]
}

enum DashboardStore {
    static let appGroupID = "group.com.jonisuominen.readout"
    private static let metricsKey = "dashboard.metrics"
    private static let sourcesKey = "dashboard.sources"
    private static let updatedAtKey = "dashboard.updatedAt"
    private static let sourceUpdatesKey = "dashboard.sourceUpdates"
    private static let sourceErrorsKey = "dashboard.sourceErrors"
    private static let privacyVersionKey = "dashboard.privacyVersion"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroupID) ?? .standard
    }

    static var metrics: [DashboardMetric] {
        get {
            migratePrivacyIfNeeded()
            guard let data = defaults.data(forKey: metricsKey),
                  let metrics = try? JSONDecoder().decode([DashboardMetric].self, from: data) else {
                return []
            }
            return metrics.filter { !$0.isExpired(at: .now) }
        }
        set {
            defaults.set(try? JSONEncoder().encode(newValue), forKey: metricsKey)
        }
    }

    static var connectedSources: Set<DataSource> {
        get {
            let values = defaults.stringArray(forKey: sourcesKey) ?? []
            return Set(values.compactMap(DataSource.init(rawValue:)))
        }
        set { defaults.set(newValue.map(\.rawValue), forKey: sourcesKey) }
    }

    static var updatedAt: Date? {
        get { defaults.object(forKey: updatedAtKey) as? Date }
        set { defaults.set(newValue, forKey: updatedAtKey) }
    }

    static func updatedAt(for source: DataSource) -> Date? {
        let updates = defaults.dictionary(forKey: sourceUpdatesKey) ?? [:]
        return updates[source.rawValue] as? Date
    }

    static func error(for source: DataSource) -> String? {
        let errors = defaults.dictionary(forKey: sourceErrorsKey) ?? [:]
        return errors[source.rawValue] as? String
    }

    static func recordError(_ message: String, for source: DataSource) {
        var errors = defaults.dictionary(forKey: sourceErrorsKey) ?? [:]
        errors[source.rawValue] = message
        defaults.set(errors, forKey: sourceErrorsKey)
    }

    static func replaceMetrics(_ newMetrics: [DashboardMetric], for source: DataSource, fetchedAt: Date = .now) {
        var current = metrics
        current.removeAll { $0.source == source }
        current.append(contentsOf: newMetrics.filter { $0.source == source })
        let order = MetricCatalog.all.map(\.id)
        current.sort {
            (order.firstIndex(of: $0.id) ?? order.count) <
                (order.firstIndex(of: $1.id) ?? order.count)
        }
        metrics = current
        var updates = defaults.dictionary(forKey: sourceUpdatesKey) ?? [:]
        updates[source.rawValue] = fetchedAt
        defaults.set(updates, forKey: sourceUpdatesKey)
        clearError(for: source)
        updatedAt = fetchedAt
    }

    static func removeMetrics(for source: DataSource) {
        metrics = metrics.filter { $0.source != source }
        var updates = defaults.dictionary(forKey: sourceUpdatesKey) ?? [:]
        updates.removeValue(forKey: source.rawValue)
        defaults.set(updates, forKey: sourceUpdatesKey)
        clearError(for: source)
        // Removing a source is not a data fetch.
        updatedAt = DataSource.allCases.compactMap { updatedAt(for: $0) }.max()
    }

    static func deleteLocalData() {
        defaults.removeObject(forKey: metricsKey)
        defaults.removeObject(forKey: sourcesKey)
        defaults.removeObject(forKey: updatedAtKey)
        defaults.removeObject(forKey: sourceUpdatesKey)
        defaults.removeObject(forKey: sourceErrorsKey)
        CalendarPreferences.reset()
        UnitPreferences.reset()
        WidgetDesignStore.reset()
        WeatherLocationStore.clear()
        defaults.set(1, forKey: privacyVersionKey)
    }

    /// An older snapshot can contain event titles copied before calendar privacy
    /// controls existed. Discard that source before the app or widget can display it.
    static func migratePrivacyIfNeeded() {
        guard defaults.integer(forKey: privacyVersionKey) < 1 else { return }
        if let data = defaults.data(forKey: metricsKey),
           let oldMetrics = try? JSONDecoder().decode([DashboardMetric].self, from: data) {
            let safeMetrics = oldMetrics.filter { $0.source != .calendar }
            defaults.set(try? JSONEncoder().encode(safeMetrics), forKey: metricsKey)
        }
        var updates = defaults.dictionary(forKey: sourceUpdatesKey) ?? [:]
        updates.removeValue(forKey: DataSource.calendar.rawValue)
        defaults.set(updates, forKey: sourceUpdatesKey)
        var errors = defaults.dictionary(forKey: sourceErrorsKey) ?? [:]
        errors.removeValue(forKey: DataSource.calendar.rawValue)
        defaults.set(errors, forKey: sourceErrorsKey)
        let remainingDates = updates.values.compactMap { $0 as? Date }
        if let latest = remainingDates.max() { defaults.set(latest, forKey: updatedAtKey) }
        else { defaults.removeObject(forKey: updatedAtKey) }
        // App and widget can migrate concurrently. Publish completion only once
        // the legacy private calendar snapshot and metadata have been removed.
        defaults.set(1, forKey: privacyVersionKey)
    }

    private static func clearError(for source: DataSource) {
        var errors = defaults.dictionary(forKey: sourceErrorsKey) ?? [:]
        errors.removeValue(forKey: source.rawValue)
        defaults.set(errors, forKey: sourceErrorsKey)
    }
}

/// Freshness describes the last successful source fetch, not a widget timeline
/// read or the date of the underlying measurement. One stale source makes the
/// displayed group stale even when another source fetched successfully.
struct DashboardFreshness {
    static let staleInterval: TimeInterval = 60 * 60

    let oldestFetchAt: Date?
    let hasError: Bool
    let hasMissingFetch: Bool
    let hasMetrics: Bool

    init(
        metrics: [DashboardMetric],
        updatedAtBySource: [DataSource: Date],
        errorsBySource: [DataSource: String]
    ) {
        let sources = Set(metrics.map(\.source))
        let dates = sources.compactMap { updatedAtBySource[$0] }
        oldestFetchAt = dates.min()
        hasError = sources.contains { errorsBySource[$0] != nil }
        hasMissingFetch = dates.count < sources.count
        hasMetrics = !metrics.isEmpty
    }

    static func stored(for metrics: [DashboardMetric]) -> DashboardFreshness {
        var updates: [DataSource: Date] = [:]
        var errors: [DataSource: String] = [:]
        for source in Set(metrics.map(\.source)) {
            updates[source] = DashboardStore.updatedAt(for: source)
            errors[source] = DashboardStore.error(for: source)
        }
        return DashboardFreshness(metrics: metrics, updatedAtBySource: updates, errorsBySource: errors)
    }

    func isStale(at date: Date = .now) -> Bool {
        guard hasMetrics else { return false }
        guard !hasError, !hasMissingFetch, let oldestFetchAt else { return true }
        return date.timeIntervalSince(oldestFetchAt) >= Self.staleInterval
    }
}
