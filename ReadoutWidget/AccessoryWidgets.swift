import WidgetKit
import SwiftUI

struct ReadoutAccessoryWidget: Widget {
    let kind = "ReadoutAccessoryWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: LockWidgetIntent.self, provider: Provider<LockWidgetIntent>()) { entry in
            AccessoryView(entry: entry)
                .widgetURL(entry.orderedMetrics.first.flatMap { TileLink.url(for: $0.id) })
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Glance")
        .description("One metric, discreet by default. Edit the widget to choose the metric and to show private values.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct AccessoryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DashboardEntry

    private var primary: DashboardMetric? { entry.orderedMetrics.first }
    private var event: DashboardMetric? { entry.metrics.first { $0.id == "nextEvent" } }
    private var freshness: DashboardFreshness { entry.freshness }
    private var isStale: Bool { freshness.isStale(at: entry.date) }

    var body: some View {
        if let primary {
            switch family {
            case .accessoryCircular:
                circular(primary)
            case .accessoryRectangular:
                rectangular(primary)
            default:
                Label(inlineText(primary), systemImage: isStale ? "exclamationmark.arrow.circlepath" : primary.symbol)
                    .privacySensitive(isSensitive(primary))
            }
        } else {
            Label("Connect a source", systemImage: "link.badge.plus")
        }
    }

    @ViewBuilder
    private func circular(_ metric: DashboardMetric) -> some View {
        if let progress = metric.gaugeProgress {
            Gauge(value: progress, in: 0...1) {
                if metric.id == "uv" {
                    Text("UV")
                } else {
                    Image(systemName: metric.symbol)
                }
            } currentValueLabel: {
                Text(metric.value).font(.system(size: 14, weight: .semibold)).minimumScaleFactor(0.5)
            }
            .gaugeStyle(.accessoryCircular)
            .privacySensitive(isSensitive(metric))
            .accessibilityLabel(spoken(metric))
        } else {
            VStack(spacing: 1) {
                Image(systemName: metric.symbol).font(.caption2)
                Text(shortValue(metric)).font(.system(size: 13, weight: .semibold)).lineLimit(2).minimumScaleFactor(0.5)
                if isStale {
                    Image(systemName: "exclamationmark.arrow.circlepath").font(.system(size: 8))
                }
            }
            .privacySensitive(isSensitive(metric))
            .accessibilityLabel(spoken(metric))
        }
    }

    private func rectangular(_ metric: DashboardMetric) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(lineText(metric), systemImage: metric.symbol)
                .font(.headline)
                .lineLimit(1).minimumScaleFactor(0.7)
                .privacySensitive(isSensitive(metric))
            if let event, event.id != metric.id {
                Label(lineText(event), systemImage: "calendar")
                    .font(.caption)
                    .lineLimit(1)
                    .privacySensitive(isSensitive(event))
            } else if metric.id != "nextEvent" {
                Text(metric.detail).font(.caption).lineLimit(1)
                    .privacySensitive(isSensitive(metric))
            }
            Text(ageText).font(.system(size: 10)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// One line per metric. Events keep their start time and title together.
    private func lineText(_ metric: DashboardMetric) -> String {
        if metric.id == "nextEvent", let start = metric.observedAt {
            return "\(EventTiming.compactText(start: start, end: metric.endsAt, now: entry.date)) \(metric.value)"
        }
        if metric.id == "uv" {
            return "UV \(metric.value) \(metric.detail.components(separatedBy: " · ").first ?? "")"
        }
        return "\(metric.title) \(metric.value)"
    }

    private func inlineText(_ metric: DashboardMetric) -> String {
        "\(isStale ? "Stale · " : "")\(lineText(metric))"
    }

    private func shortValue(_ metric: DashboardMetric) -> String {
        if metric.id == "nextEvent", let start = metric.observedAt {
            return EventTiming.compactText(start: start, end: metric.endsAt, now: entry.date)
        }
        return metric.value
    }

    private var ageText: String {
        if freshness.hasError { return "Refresh failed" }
        guard let fetchedAt = freshness.oldestFetchAt else { return "Not refreshed yet" }
        return "\(isStale ? "Stale · " : "")Updated \(UpdatedLabel.short(since: fetchedAt, now: entry.date)) ago"
    }

    private func spoken(_ metric: DashboardMetric) -> String {
        "\(metric.title), \(shortValue(metric))\(isStale ? ", stale, open the app to refresh" : "")"
    }

    private func isSensitive(_ metric: DashboardMetric) -> Bool {
        entry.configuration.privacyMode == .showValues && metric.source != .weather
    }
}
