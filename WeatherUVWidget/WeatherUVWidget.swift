import WidgetKit
import SwiftUI

struct WeatherUVWidget: Widget {
    let kind = "ReadoutWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: HomeWidgetIntent.self, provider: Provider<HomeWidgetIntent>()) { entry in
            let appearance = entry.design?.appearance ?? .system
            HomeWidgetView(entry: entry)
                .widgetAppearance(appearance)
                .containerBackground(for: .widget) { appearance.background }
        }
        .configurationDisplayName("Dashboard")
        .description("Weather, health, and your next event in one layout you design in the app.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct HomeWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DashboardEntry

    private var canvas: WidgetCanvas { WidgetCanvas(family: family) ?? .small }

    var body: some View {
        if entry.connectedSources.isEmpty && entry.metrics.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: "link.badge.plus").font(.title3)
                Text("Open the app to connect weather, health, or calendar.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            TileGridView(
                design: entry.design ?? WidgetDesignStore.resolvedDesign(id: entry.configuration.designID, for: canvas),
                canvas: canvas,
                metrics: entry.metrics,
                context: TileContext(
                    now: entry.date,
                    freshness: entry.freshness,
                    privacyMode: entry.configuration.privacyMode,
                    connectedSources: entry.connectedSources,
                    isInteractive: true
                )
            )
        }
    }
}
