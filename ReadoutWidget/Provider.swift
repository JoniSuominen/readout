import WidgetKit

extension WidgetCanvas {
    init?(family: WidgetFamily) {
        switch family {
        case .systemSmall: self = .small
        case .systemMedium: self = .medium
        case .systemLarge: self = .large
        case .systemExtraLarge: self = .extraLarge
        default: return nil
        }
    }
}

struct DashboardEntry: TimelineEntry {
    let date: Date
    /// Privacy-rendered metrics for this widget's privacy mode.
    let metrics: [DashboardMetric]
    let configuration: WidgetInstanceConfiguration
    /// The tile layout for Home Screen widgets; nil for Lock Screen widgets.
    let design: WidgetDesign?
    let connectedSources: Set<DataSource>
    let updatedAtBySource: [DataSource: Date]
    let errorsBySource: [DataSource: String]

    var orderedMetrics: [DashboardMetric] {
        configuration.orderedMetrics(metrics)
    }

    /// Metrics this widget actually draws; freshness covers only these.
    var displayedMetrics: [DashboardMetric] {
        if let design {
            let ids = Set(design.tiles.map(\.metricID))
            return metrics.filter { ids.contains($0.id) }
        }
        let ordered = orderedMetrics
        guard let primary = ordered.first else { return [] }
        if primary.id != "nextEvent", let event = ordered.first(where: { $0.id == "nextEvent" }) {
            return [primary, event]
        }
        return [primary]
    }

    var freshness: DashboardFreshness {
        DashboardFreshness(metrics: displayedMetrics, updatedAtBySource: updatedAtBySource, errorsBySource: errorsBySource)
    }

    static func cached(at date: Date, configuration: WidgetInstanceConfiguration, family: WidgetFamily) -> DashboardEntry {
        var updates: [DataSource: Date] = [:]
        var errors: [DataSource: String] = [:]
        for source in DataSource.allCases {
            updates[source] = DashboardStore.updatedAt(for: source)
            errors[source] = DashboardStore.error(for: source)
        }
        return DashboardEntry(
            date: date,
            metrics: configuration.renderedMetrics(DashboardStore.metrics),
            configuration: configuration,
            design: WidgetCanvas(family: family).map { WidgetDesignStore.resolvedDesign(id: configuration.designID, for: $0) },
            connectedSources: DashboardStore.connectedSources,
            updatedAtBySource: updates,
            errorsBySource: errors
        )
    }

    static func preview(at date: Date = .now, configuration: WidgetInstanceConfiguration, family: WidgetFamily) -> DashboardEntry {
        DashboardEntry(
            date: date,
            metrics: configuration.renderedMetrics(DashboardMetric.defaults),
            configuration: configuration,
            design: WidgetCanvas(family: family).map { WidgetDesignStore.resolvedDesign(id: configuration.designID, for: $0) },
            connectedSources: Set(DataSource.allCases),
            updatedAtBySource: Dictionary(uniqueKeysWithValues: DataSource.allCases.map { ($0, date) }),
            errorsBySource: [:]
        )
    }

    func scheduled(at date: Date) -> DashboardEntry {
        DashboardEntry(
            date: date,
            metrics: metrics.filter { !$0.isExpired(at: date) },
            configuration: configuration,
            design: design,
            connectedSources: connectedSources,
            updatedAtBySource: updatedAtBySource,
            errorsBySource: errorsBySource
        )
    }
}

struct Provider<Configuration: DashboardWidgetIntent>: AppIntentTimelineProvider {
    typealias Intent = Configuration

    static var reloadInterval: TimeInterval { 30 * 60 }

    func placeholder(in context: Context) -> DashboardEntry {
        .preview(configuration: Configuration.defaultConfiguration, family: context.family)
    }

    func snapshot(for configuration: Configuration, in context: Context) async -> DashboardEntry {
        context.isPreview
            ? .preview(configuration: configuration.instanceConfiguration, family: context.family)
            : .cached(at: .now, configuration: configuration.instanceConfiguration, family: context.family)
    }

    func timeline(for configuration: Configuration, in context: Context) async -> Timeline<DashboardEntry> {
        // Weather, Health, and Calendar can be read here, so a widget stays
        // current without opening the app. Recently fetched sources are skipped.
        await WidgetDataRefresher.refresh(userInitiated: false)

        let now = Date.now
        let entry = DashboardEntry.cached(at: now, configuration: configuration.instanceConfiguration, family: context.family)
        var reloadAt = now.addingTimeInterval(Self.reloadInterval)
        // Refetch when a value expires: the next event once the shown one
        // ends, and today's steps and forecast at midnight.
        if let expiry = entry.metrics.compactMap(\.expiresAt).filter({ $0 > now.addingTimeInterval(60) }).min() {
            reloadAt = min(reloadAt, expiry.addingTimeInterval(1))
        }

        // One entry per minute, aligned to the oldest fetch, steps the age
        // label and event countdowns without a per-second relative clock.
        let anchor = entry.freshness.oldestFetchAt ?? now
        let elapsed = max(0, now.timeIntervalSince(anchor))
        var next = anchor.addingTimeInterval((floor(elapsed / 60) + 1) * 60)
        var entries = [entry]
        while next < reloadAt {
            entries.append(entry.scheduled(at: next))
            next = next.addingTimeInterval(60)
        }
        return Timeline(entries: entries, policy: .after(reloadAt))
    }
}
