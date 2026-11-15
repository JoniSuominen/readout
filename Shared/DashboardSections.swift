import Foundation
import SwiftUI

/// The default Today groups. Tiles are grouped by what they describe rather
/// than by the connector that fetched them, so Oura and Apple Health values
/// sit together. People can rearrange everything in TodayLayout.
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
        case .recovery:
            return ["readiness", "sleep", "ouraSleepScore", "ouraDeepSleep", "ouraRemSleep", "ouraEfficiency",
                    "ouraBedtime", "ouraTemperature", "ouraStress", "ouraResilience"]
        case .activity:
            return ["steps", "distance", "activeEnergy", "exercise", "workout", "ouraActivityScore", "ouraSteps", "ouraActiveCalories"]
        case .heart:
            return ["heart", "heartRate", "hrv", "walkingHeartRate", "ouraLowestHeartRate", "ouraOvernightHRV",
                    "ouraBreathingRate", "ouraSpO2", "ouraCardioAge", "ouraVO2Max"]
        case .calendar: return ["nextEvent", "agenda"]
        }
    }

    /// A metric this list does not know yet still lands in a sensible group.
    static func category(forMetricID id: String, source: DataSource) -> DashboardCategory {
        if let known = allCases.first(where: { $0.metricIDs.contains(id) }) { return known }
        switch source {
        case .weather: return .weather
        case .oura: return .recovery
        case .appleHealth: return .activity
        case .calendar: return .calendar
        }
    }

    static func category(for metric: DashboardMetric) -> DashboardCategory {
        category(forMetricID: metric.id, source: metric.source)
    }
}

/// One group of tiles on Today. Default groups use their category's raw
/// value as id; groups people create get a UUID.
struct TodayGroup: Codable, Identifiable, Hashable {
    var id: String
    var title: String
    var symbol: String
    var metricIDs: [String]

    init(id: String, title: String, symbol: String, metricIDs: [String]) {
        self.id = id
        self.title = title
        self.symbol = symbol
        self.metricIDs = metricIDs
    }

    init(category: DashboardCategory, metricIDs: [String]? = nil) {
        self.init(id: category.rawValue, title: category.title, symbol: category.symbol,
                  metricIDs: metricIDs ?? category.metricIDs)
    }

    static let customSymbol = "square.grid.2x2"
}

/// How Today is arranged: group order, tile order, and which tiles show.
/// Stored in the app group so Lock Screen widgets set to Automatic can skip
/// hidden values too.
struct TodayLayout: Codable, Equatable {
    var groups: [TodayGroup]
    /// Explicit choices only; anything missing follows the catalog default.
    var visibility: [String: Bool]

    static var standard: TodayLayout {
        TodayLayout(groups: DashboardCategory.allCases.map { TodayGroup(category: $0) }, visibility: [:])
    }

    func isShown(_ metricID: String) -> Bool {
        visibility[metricID] ?? MetricCatalog.descriptor(for: metricID)?.shownByDefault ?? true
    }

    /// Every catalog metric sits in exactly one group. A metric added in a
    /// later version joins its default group, or the last group when that one
    /// was deleted.
    func normalized() -> TodayLayout {
        var seen = Set<String>()
        var groups = self.groups.map { group -> TodayGroup in
            var copy = group
            copy.metricIDs = group.metricIDs.filter { seen.insert($0).inserted }
            return copy
        }
        for descriptor in MetricCatalog.all where !seen.contains(descriptor.id) {
            let category = DashboardCategory.category(forMetricID: descriptor.id, source: descriptor.source)
            if let index = groups.firstIndex(where: { $0.id == category.rawValue }) ?? groups.indices.last {
                groups[index].metricIDs.append(descriptor.id)
            } else {
                groups.append(TodayGroup(category: category, metricIDs: [descriptor.id]))
            }
            seen.insert(descriptor.id)
        }
        return TodayLayout(groups: groups, visibility: visibility)
    }

    func group(containing metricID: String) -> TodayGroup? {
        groups.first { $0.metricIDs.contains(metricID) }
    }

    // MARK: Editing

    mutating func setShown(_ metricID: String, _ shown: Bool) {
        let fallback = MetricCatalog.descriptor(for: metricID)?.shownByDefault ?? true
        visibility[metricID] = shown == fallback ? nil : shown
    }

    mutating func moveMetric(_ metricID: String, toGroup groupID: String) {
        guard let target = groups.firstIndex(where: { $0.id == groupID }),
              !groups[target].metricIDs.contains(metricID) else { return }
        for index in groups.indices { groups[index].metricIDs.removeAll { $0 == metricID } }
        groups[target].metricIDs.append(metricID)
    }

    mutating func moveMetrics(inGroup groupID: String, from offsets: IndexSet, to destination: Int) {
        guard let index = groups.firstIndex(where: { $0.id == groupID }) else { return }
        groups[index].metricIDs.move(fromOffsets: offsets, toOffset: destination)
    }

    mutating func moveGroups(from offsets: IndexSet, to destination: Int) {
        groups.move(fromOffsets: offsets, toOffset: destination)
    }

    @discardableResult
    mutating func addGroup(title: String) -> TodayGroup {
        let group = TodayGroup(id: UUID().uuidString, title: Self.cleanTitle(title, fallback: "New group"),
                               symbol: TodayGroup.customSymbol, metricIDs: [])
        groups.append(group)
        return group
    }

    mutating func renameGroup(_ groupID: String, to title: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupID }) else { return }
        groups[index].title = Self.cleanTitle(title, fallback: groups[index].title)
    }

    /// Tiles of a deleted group go back to their default group, or to the
    /// first remaining group. The last group cannot be deleted.
    mutating func deleteGroup(_ groupID: String) {
        guard groups.count > 1, let index = groups.firstIndex(where: { $0.id == groupID }) else { return }
        let orphans = groups.remove(at: index).metricIDs
        for id in orphans {
            let source = MetricCatalog.descriptor(for: id)?.source ?? .weather
            let category = DashboardCategory.category(forMetricID: id, source: source)
            let target = groups.firstIndex { $0.id == category.rawValue } ?? 0
            groups[target].metricIDs.append(id)
        }
    }

    private static func cleanTitle(_ title: String, fallback: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : String(trimmed.prefix(40))
    }
}

enum TodayLayoutStore {
    private static let key = "today.layout"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: DashboardStore.appGroupID) ?? .standard
    }

    static var layout: TodayLayout {
        get {
            guard let data = defaults.data(forKey: key),
                  let layout = try? JSONDecoder().decode(TodayLayout.self, from: data) else { return .standard }
            return layout.normalized()
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: key) }
    }

    static func reset() {
        defaults.removeObject(forKey: key)
    }
}

struct DashboardSection: Identifiable {
    let group: TodayGroup
    let metrics: [DashboardMetric]

    var id: String { group.id }
    var title: String { group.title }
    var symbol: String { group.symbol }

    /// Connected sources feeding this section, for its freshness label.
    var sources: [DataSource] {
        DataSource.allCases.filter { source in metrics.contains { $0.source == source } }
    }

    private var origins: Set<String> { Set(metrics.map(\.originLabel)) }

    /// Named once in the header when every tile comes from the same place.
    /// Nil when it would only repeat the section title.
    var headerOrigin: String? {
        guard origins.count == 1, let origin = origins.first, origin != title else { return nil }
        return origin
    }

    /// Tiles name their own origin only when the section mixes several.
    var showsOriginOnTiles: Bool { origins.count > 1 }

    /// Shown metrics in the layout's group and tile order. Empty groups are
    /// left out. A metric no group lists keeps its stored order after the
    /// listed ones in its default group.
    static func sections(for metrics: [DashboardMetric], layout: TodayLayout = .standard) -> [DashboardSection] {
        let layout = layout.normalized()
        let shown = metrics.filter { layout.isShown($0.id) }
        var placed = Set<String>()
        var grouped: [(group: TodayGroup, metrics: [DashboardMetric])] = layout.groups.map { group in
            let members = group.metricIDs.compactMap { id in shown.first { $0.id == id } }
            placed.formUnion(members.map(\.id))
            return (group: group, metrics: members)
        }
        for metric in shown where !placed.contains(metric.id) {
            let category = DashboardCategory.category(for: metric)
            if let index = grouped.firstIndex(where: { $0.group.id == category.rawValue }) ?? grouped.indices.last {
                grouped[index].metrics.append(metric)
            } else {
                grouped.append((group: TodayGroup(category: category, metricIDs: []), metrics: [metric]))
            }
            placed.insert(metric.id)
        }
        return grouped.filter { !$0.metrics.isEmpty }.map { DashboardSection(group: $0.group, metrics: $0.metrics) }
    }
}
