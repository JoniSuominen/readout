import Foundation

/// Every metric a tile or Lock Screen widget can show, including ones whose
/// source is not connected yet. The order is also the dashboard order.
struct MetricDescriptor: Identifiable, Hashable {
    let id: String
    let name: String
    let source: DataSource
    let symbol: String
}

enum MetricCatalog {
    static let all: [MetricDescriptor] = [
        .init(id: "temperature", name: "Temperature", source: .weather, symbol: "thermometer.medium"),
        .init(id: "uv", name: "UV index", source: .weather, symbol: "sun.max.fill"),
        .init(id: "rain", name: "Rain today", source: .weather, symbol: "drop.fill"),
        .init(id: "tomorrow", name: "Tomorrow", source: .weather, symbol: "calendar"),
        .init(id: "feelsLike", name: "Feels like", source: .weather, symbol: "thermometer.medium"),
        .init(id: "wind", name: "Wind", source: .weather, symbol: "wind"),
        .init(id: "humidity", name: "Humidity", source: .weather, symbol: "humidity.fill"),
        .init(id: "sun", name: "Sunrise and sunset", source: .weather, symbol: "sunrise.fill"),
        .init(id: "airQuality", name: "Air quality", source: .weather, symbol: "aqi.medium"),
        .init(id: "readiness", name: "Readiness", source: .oura, symbol: "sparkles"),
        .init(id: "sleep", name: "Sleep", source: .oura, symbol: "moon.stars.fill"),
        .init(id: "steps", name: "Steps", source: .appleHealth, symbol: "figure.walk"),
        .init(id: "heart", name: "Resting heart rate", source: .appleHealth, symbol: "heart.fill"),
        .init(id: "heartRate", name: "Heart rate", source: .appleHealth, symbol: "heart.fill"),
        .init(id: "hrv", name: "HRV", source: .appleHealth, symbol: "heart.fill"),
        .init(id: "walkingHeartRate", name: "Walking heart rate", source: .appleHealth, symbol: "heart.fill"),
        .init(id: "activeEnergy", name: "Active energy", source: .appleHealth, symbol: "flame.fill"),
        .init(id: "exercise", name: "Exercise minutes", source: .appleHealth, symbol: "figure.run"),
        .init(id: "distance", name: "Distance", source: .appleHealth, symbol: "figure.walk.motion"),
        .init(id: "workout", name: "Last workout", source: .appleHealth, symbol: "dumbbell.fill"),
        .init(id: "nextEvent", name: "Next event", source: .calendar, symbol: "calendar"),
        .init(id: "agenda", name: "Agenda", source: .calendar, symbol: "list.bullet")
    ]

    static func descriptor(for id: String) -> MetricDescriptor? {
        all.first { $0.id == id }
    }

    /// Lock Screen widgets set to Automatic show the first available metric.
    static let automaticPriority = ["uv", "temperature", "nextEvent", "readiness", "steps", "sleep", "heart", "hrv", "heartRate", "walkingHeartRate", "rain", "tomorrow",
                                    "feelsLike", "wind", "airQuality", "humidity", "sun", "activeEnergy", "exercise", "distance", "workout"]
}

enum WidgetMetricChoice: String, CaseIterable, Identifiable {
    case automatic
    case uv
    case temperature
    case rain
    case tomorrow
    case feelsLike
    case wind
    case humidity
    case sun
    case airQuality
    case readiness
    case sleep
    case steps
    case heart
    case heartRate
    case hrv
    case walkingHeartRate
    case activeEnergy
    case exercise
    case distance
    case workout
    case nextEvent

    var id: String { rawValue }
    var metricID: String { self == .automatic ? "" : rawValue }

    var displayName: String {
        self == .automatic ? "Automatic" : MetricCatalog.descriptor(for: rawValue)?.name ?? rawValue
    }
}

/// A widget's own settings. Installed widgets always receive this value from
/// their configuration intent, so one widget cannot change another's choices.
struct WidgetInstanceConfiguration: Equatable {
    /// Home Screen tile layout. Nil uses the first layout for the widget's size.
    let designID: String?
    /// Lock Screen metric. Empty means automatic.
    let primaryMetricID: String
    let privacyMode: WidgetPrivacyMode

    init(
        designID: String? = nil,
        primaryMetricID: String = "",
        privacyMode: WidgetPrivacyMode = .discreet
    ) {
        self.designID = designID
        self.primaryMetricID = primaryMetricID
        self.privacyMode = privacyMode
    }

    static let homeDefault = WidgetInstanceConfiguration(privacyMode: .showValues)
    static let lockDefault = WidgetInstanceConfiguration(privacyMode: .discreet)

    func orderedMetrics(_ metrics: [DashboardMetric]) -> [DashboardMetric] {
        let priorities = MetricCatalog.automaticPriority
        // Preserve source order for equally ranked or new metric types.
        var ordered = metrics.enumerated().sorted { lhs, rhs in
            let left = priorities.firstIndex(of: lhs.element.id) ?? priorities.count
            let right = priorities.firstIndex(of: rhs.element.id) ?? priorities.count
            return left == right ? lhs.offset < rhs.offset : left < right
        }.map(\.element)
        if !primaryMetricID.isEmpty, let index = ordered.firstIndex(where: { $0.id == primaryMetricID }) {
            ordered.insert(ordered.remove(at: index), at: 0)
        }
        return ordered
    }

    func renderedMetrics(_ metrics: [DashboardMetric]) -> [DashboardMetric] {
        orderedMetrics(WidgetPrivacy.render(metrics, mode: privacyMode))
    }
}
