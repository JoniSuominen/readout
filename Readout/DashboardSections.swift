import Foundation

/// The Today screen groups tiles by what they describe rather than by the
/// connector that fetched them, so Oura and Apple Health values sit together.
enum DashboardCategory: String, CaseIterable, Identifiable {
    case weather
    case recovery
    case activity
    case heart
    case calendar

    var id: String { rawValue }

    var title: String {
        switch self {
        case .weather: return "Weather"
        case .recovery: return "Sleep and recovery"
        case .activity: return "Activity"
        case .heart: return "Heart"
        case .calendar: return "Calendar"
        }
    }

    var symbol: String {
        switch self {
        case .weather: return "cloud.sun.fill"
        case .recovery: return "moon.stars.fill"
        case .activity: return "figure.walk"
        case .heart: return "heart.fill"
        case .calendar: return "calendar"
        }
    }

    /// Tiles in reading order: related values are neighbours in the grid.
    var metricIDs: [String] {
        switch self {
        case .weather:
            return ["temperature", "feelsLike", "rain", "wind", "uv", "humidity", "airQuality", "sun", "tomorrow"]
        case .recovery: return ["readiness", "sleep"]
        case .activity: return ["steps", "distance", "activeEnergy", "exercise", "workout"]
        case .heart: return ["heart", "heartRate", "hrv", "walkingHeartRate"]
        case .calendar: return ["nextEvent", "agenda"]
        }
    }

    /// A metric this list does not know yet still lands in a sensible group.
    static func category(for metric: DashboardMetric) -> DashboardCategory {
        if let known = allCases.first(where: { $0.metricIDs.contains(metric.id) }) { return known }
        switch metric.source {
        case .weather: return .weather
        case .oura: return .recovery
        case .appleHealth: return .activity
        case .calendar: return .calendar
        }
    }
}

struct DashboardSection: Identifiable {
    let category: DashboardCategory
    let metrics: [DashboardMetric]

    var id: String { category.rawValue }

    /// Connected sources feeding this section, for its freshness label.
    var sources: [DataSource] {
        DataSource.allCases.filter { source in metrics.contains { $0.source == source } }
    }

    private var origins: Set<String> { Set(metrics.map(\.originLabel)) }

    /// Named once in the header when every tile comes from the same place.
    /// Nil when it would only repeat the section title.
    var headerOrigin: String? {
        guard origins.count == 1, let origin = origins.first, origin != category.title else { return nil }
        return origin
    }

    /// Tiles name their own origin only when the section mixes several.
    var showsOriginOnTiles: Bool { origins.count > 1 }

    static func sections(for metrics: [DashboardMetric]) -> [DashboardSection] {
        let grouped = Dictionary(grouping: metrics) { DashboardCategory.category(for: $0) }
        return DashboardCategory.allCases.compactMap { category in
            guard let members = grouped[category] else { return nil }
            let order = category.metricIDs
            // Unknown metrics keep their stored order after the known ones.
            let sorted = members.enumerated().sorted { lhs, rhs in
                let left = order.firstIndex(of: lhs.element.id) ?? order.count
                let right = order.firstIndex(of: rhs.element.id) ?? order.count
                return left == right ? lhs.offset < rhs.offset : left < right
            }.map(\.element)
            return DashboardSection(category: category, metrics: sorted)
        }
    }
}
