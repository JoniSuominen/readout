import AppIntents
import SwiftUI
import WidgetKit

/// Everything a tile needs besides its own metric.
struct TileContext {
    var now: Date
    var freshness: DashboardFreshness
    var privacyMode: WidgetPrivacyMode
    var connectedSources: Set<DataSource>
    /// Widgets run the sync button; in-app previews draw it inert.
    var isInteractive: Bool
    /// Previews draw tiles of unconnected sources dimmed instead of empty.
    var showsUnavailable = false
    /// Multiplies fonts and spacing for enlarged editor previews.
    var scale: CGFloat = 1
}

struct TileGridGeometry {
    let cellWidth: CGFloat
    let cellHeight: CGFloat
    let spacing: CGFloat

    init(size: CGSize, canvas: WidgetCanvas, spacing: CGFloat) {
        self.spacing = spacing
        cellWidth = max(0, (size.width - spacing * CGFloat(canvas.columns - 1)) / CGFloat(canvas.columns))
        cellHeight = max(0, (size.height - spacing * CGFloat(canvas.rows - 1)) / CGFloat(canvas.rows))
    }

    /// One cell plus its gap: how far a tile moves to cross a cell.
    var pitch: CGSize {
        CGSize(width: max(1, cellWidth + spacing), height: max(1, cellHeight + spacing))
    }

    func frame(of tile: WidgetTile) -> CGRect {
        CGRect(
            x: CGFloat(tile.column) * (cellWidth + spacing),
            y: CGFloat(tile.row) * (cellHeight + spacing),
            width: CGFloat(tile.width) * cellWidth + CGFloat(tile.width - 1) * spacing,
            height: CGFloat(tile.height) * cellHeight + CGFloat(tile.height - 1) * spacing
        )
    }

    /// Whole cells covered by a drag.
    func cells(for translation: CGSize) -> (columns: Int, rows: Int) {
        (
            Int((translation.width / max(1, cellWidth + spacing)).rounded()),
            Int((translation.height / max(1, cellHeight + spacing)).rounded())
        )
    }
}

/// Draws a layout's tiles at their grid positions. Shared by the widget and
/// the app's previews so both always look the same.
struct TileGridView: View {
    let design: WidgetDesign
    let canvas: WidgetCanvas
    let metrics: [DashboardMetric]
    let context: TileContext

    var body: some View {
        GeometryReader { proxy in
            let grid = TileGridGeometry(size: proxy.size, canvas: canvas, spacing: WidgetCanvas.tileSpacing * context.scale)
            ZStack(alignment: .topLeading) {
                ForEach(design.visibleTiles(on: canvas)) { tile in
                    let frame = grid.frame(of: tile)
                    TileView(
                        tile: tile,
                        metric: metrics.first { $0.id == tile.metricID },
                        context: context,
                        size: frame.size,
                        isTrailing: tile.endColumn == canvas.columns
                    )
                    .frame(width: frame.width, height: frame.height)
                    .offset(x: frame.minX, y: frame.minY)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
        }
    }
}

struct TileView: View {
    let tile: WidgetTile
    let metric: DashboardMetric?
    let context: TileContext
    let size: CGSize
    var isTrailing = false

    var body: some View {
        if tile.isSync {
            SyncTileView(tile: tile, context: context, size: size, isTrailing: isTrailing)
        } else if let descriptor = MetricCatalog.descriptor(for: tile.metricID) {
            if metric != nil || context.showsUnavailable || context.connectedSources.contains(descriptor.source) {
                let content = MetricTileView(tile: tile, descriptor: descriptor, metric: metric, context: context, size: size)
                // Medium and large widgets open the tile's own app; small
                // widgets have one tap target and open Readout.
                if context.isInteractive, let url = TileLink.url(for: tile.metricID) {
                    Link(destination: url) { content }
                        .buttonStyle(.plain)
                } else {
                    content
                }
            } else {
                // A tile for a source that is not connected keeps its space empty.
                Color.clear
            }
        }
    }
}

private struct MetricTileView: View {
    let tile: WidgetTile
    let descriptor: MetricDescriptor
    let metric: DashboardMetric?
    let context: TileContext
    let size: CGSize

    @Environment(\.widgetAppearanceStyle) private var appearance

    private var scale: CGFloat { context.scale }
    private var value: String { metric?.value ?? "—" }
    private var title: String { metric?.title ?? descriptor.name }
    private var detail: String { metric?.detail ?? "" }
    private var showsIndicator: Bool { tile.style == .standard }
    private var tint: Color { appearance.tint(named: metric?.tint ?? "gray") }
    /// The first part of a detail such as "Moderate · peak 6 at 13:00".
    private var shortDetail: String { detail.components(separatedBy: " · ").first ?? detail }

    var body: some View {
        Group {
            if descriptor.id == "nextEvent" {
                event
            } else if descriptor.id == "agenda" {
                agenda
            } else if tile.height >= 2 && tile.width >= 2 {
                detailed
            } else if tile.height >= 2 {
                stacked
            } else {
                compact
            }
        }
        .privacySensitive(context.privacyMode == .showValues && descriptor.source != .weather)
        .opacity(metric == nil ? 0.45 : 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    private var compact: some View {
        let valueSize = clamp(size.height * 0.6, 11 * scale, 22 * scale)
        return HStack(spacing: 4 * scale) {
            if showsIndicator { indicator(size: valueSize, prefersText: true) }
            Text(value)
                .font(.system(size: valueSize, weight: .semibold, design: .rounded))
                .lineLimit(1).minimumScaleFactor(0.5)
                .layoutPriority(1)
            if tile.width >= 2 && !detail.isEmpty {
                Text(tile.width >= 3 ? detail : shortDetail)
                    .font(.system(size: 10 * scale))
                    .foregroundStyle(appearance.secondaryInk)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var stacked: some View {
        let valueSize = clamp((size.height - 18 * scale) * 0.62, 14 * scale, 40 * scale)
        return VStack(alignment: .leading, spacing: 2 * scale) {
            if showsIndicator { indicator(size: 13 * scale, prefersText: true) }
            Text(value)
                .font(.system(size: valueSize, weight: .semibold, design: .rounded))
                .lineLimit(1).minimumScaleFactor(0.4)
            if size.height >= 56 * scale && !shortDetail.isEmpty {
                Text(shortDetail)
                    .font(.system(size: 10 * scale))
                    .foregroundStyle(appearance.secondaryInk)
                    .lineLimit(tile.height >= 3 ? 2 : 1).minimumScaleFactor(0.8)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var detailed: some View {
        let valueSize = clamp((size.height - 30 * scale) * 0.85, 14 * scale, 46 * scale)
        return VStack(alignment: .leading, spacing: 1 * scale) {
            if showsIndicator {
                HStack(spacing: 3 * scale) {
                    indicator(size: 11 * scale, prefersText: false)
                    Text(title)
                        .font(.system(size: 10 * scale, weight: .medium))
                        .foregroundStyle(appearance.secondaryInk)
                }
                .lineLimit(1)
            }
            Text(value)
                .font(.system(size: valueSize, weight: .semibold, design: .rounded))
                .lineLimit(1).minimumScaleFactor(0.5)
            if !detail.isEmpty {
                Text(detail)
                    .font(.system(size: 10.5 * scale))
                    .foregroundStyle(appearance.secondaryInk)
                    .lineLimit(tile.height >= 3 ? 2 : 1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// The start time and title always share one line, followed by when it
    /// happens relative to now.
    @ViewBuilder
    private var event: some View {
        // Taller event tiles use a larger font rather than leaving empty rows.
        let fontSize = tile.height == 1
            ? clamp(size.height * 0.5, 11 * scale, 15 * scale)
            : clamp(size.height * 0.3, 14 * scale, 20 * scale)
        if let metric, let start = metric.observedAt {
            let ongoing = EventTiming.isOngoing(start: start, end: metric.endsAt, now: context.now)
            if tile.width == 1 {
                VStack(alignment: .leading, spacing: 2 * scale) {
                    HStack(spacing: 4 * scale) {
                        if showsIndicator { indicator(size: fontSize, prefersText: false) }
                        Text(EventTiming.compactText(start: start, end: metric.endsAt, now: context.now))
                            .font(.system(size: fontSize, weight: .semibold))
                            .lineLimit(1).minimumScaleFactor(0.6)
                    }
                    if tile.height >= 2 {
                        Text(metric.value).font(.system(size: 11 * scale)).lineLimit(tile.height)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: tile.height >= 2 ? .topLeading : .leading)
            } else {
                let narrow = tile.width == 2 && tile.height == 1
                let when = ongoing ? "Now" : narrow
                    ? EventTiming.compactText(start: start, end: metric.endsAt, now: context.now)
                    : EventTiming.startText(start, now: context.now)
                let relative = narrow ? "" : " · " + EventTiming.relativeText(start: start, end: metric.endsAt, now: context.now)
                HStack(alignment: tile.height >= 2 ? .top : .center, spacing: 4 * scale) {
                    if showsIndicator { indicator(size: fontSize, prefersText: false) }
                    (Text(when).fontWeight(.semibold)
                        + Text(" " + metric.value)
                        + Text(relative).foregroundStyle(appearance.secondaryInk))
                        .font(.system(size: fontSize))
                        .lineLimit(tile.height)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: tile.height >= 2 ? .topLeading : .leading)
            }
        } else {
            HStack(spacing: 4 * scale) {
                if showsIndicator { indicator(size: fontSize, prefersText: false) }
                Text(metric == nil ? "—" : tile.width >= 2 ? "No upcoming events" : "None")
                    .font(.system(size: fontSize))
                    .foregroundStyle(appearance.secondaryInk)
                    .lineLimit(1).minimumScaleFactor(0.7)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }

    /// One event per grid row: when it starts, then its title. A one-column
    /// tile shows only the times.
    private var agenda: some View {
        let rowHeight = size.height / CGFloat(max(1, tile.height))
        let fontSize = clamp(rowHeight * 0.5, 10 * scale, 14 * scale)
        let rows = Array((metric?.items ?? []).prefix(max(1, tile.height)))
        return VStack(alignment: .leading, spacing: 0) {
            if rows.isEmpty {
                HStack(spacing: 4 * scale) {
                    if showsIndicator { indicator(size: fontSize, prefersText: false) }
                    Text(metric == nil ? "—" : tile.width >= 2 ? "No upcoming events" : "None")
                        .font(.system(size: fontSize))
                        .foregroundStyle(appearance.secondaryInk)
                        .lineLimit(1).minimumScaleFactor(0.7)
                }
                .frame(height: rowHeight)
            } else {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, item in
                    (Text(EventTiming.agendaText(item, now: context.now)).fontWeight(.semibold)
                        + Text(tile.width >= 2 ? " " + (item.title ?? "Busy") : ""))
                        .font(.system(size: fontSize))
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(height: rowHeight)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    /// Weather symbols identify most tiles. UV and tomorrow use short text
    /// labels in small tiles, where a sun or cloud alone would be ambiguous.
    @ViewBuilder
    private func indicator(size: CGFloat, prefersText: Bool) -> some View {
        if prefersText && descriptor.id == "uv" {
            Text("UV")
                .font(.system(size: size * 0.7, weight: .heavy, design: .rounded))
                .foregroundStyle(tint)
                .lineLimit(1).fixedSize()
                .widgetAccentable()
        } else if prefersText && descriptor.id == "tomorrow" {
            Text("TMRW")
                .font(.system(size: size * 0.5, weight: .heavy, design: .rounded))
                .foregroundStyle(tint)
                .lineLimit(1).fixedSize()
                .widgetAccentable()
        } else {
            Image(systemName: metric?.symbol ?? descriptor.symbol)
                .font(.system(size: size * 0.85, weight: .medium))
                .foregroundStyle(tint)
                // Tinted Home Screens color indicators; values stay primary.
                .widgetAccentable()
        }
    }

    private var accessibilityText: String {
        guard let metric else { return "\(descriptor.name), no data" }
        if descriptor.id == "nextEvent", let start = metric.observedAt {
            return "Next event, \(metric.value), \(EventTiming.startText(start, now: context.now)), \(EventTiming.relativeText(start: start, end: metric.endsAt, now: context.now))"
        }
        if descriptor.id == "agenda", let items = metric.items, !items.isEmpty {
            let lines = items.prefix(max(1, tile.height)).map {
                "\(EventTiming.agendaText($0, now: context.now)) \($0.title ?? "Busy")"
            }
            return (["Agenda"] + lines).joined(separator: ", ")
        }
        return [title, value, detail].filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

/// The refresh button. Its label is the age of the oldest shown data in
/// whole minutes; it turns orange when data is stale or a refresh failed.
private struct SyncTileView: View {
    let tile: WidgetTile
    let context: TileContext
    let size: CGSize
    let isTrailing: Bool

    @Environment(\.widgetAppearanceStyle) private var appearance

    private var scale: CGFloat { context.scale }
    private var freshness: DashboardFreshness { context.freshness }
    private var isStale: Bool { freshness.isStale(at: context.now) }

    private var symbol: String {
        freshness.hasError ? "exclamationmark.arrow.circlepath" : "arrow.clockwise"
    }

    private var age: String? {
        guard freshness.hasMetrics else { return nil }
        guard let fetchedAt = freshness.oldestFetchAt else { return "—" }
        return tile.width >= 2 && tile.height >= 2
            ? UpdatedLabel.long(since: fetchedAt, now: context.now)
            : UpdatedLabel.short(since: fetchedAt, now: context.now)
    }

    private var spokenState: String {
        if freshness.hasError { return "Refresh failed" }
        guard let fetchedAt = freshness.oldestFetchAt else { return "Not refreshed yet" }
        let minutes = UpdatedLabel.minutes(since: fetchedAt, now: context.now)
        return "Updated \(minutes) minute\(minutes == 1 ? "" : "s") ago\(isStale ? ", stale" : "")"
    }

    var body: some View {
        if context.isInteractive {
            Button(intent: RefreshDataIntent()) { label }
                .buttonStyle(.plain)
        } else {
            label
        }
    }

    private var label: some View {
        let fontSize = clamp(size.height * 0.5, 10 * scale, 15 * scale)
        return HStack(spacing: 3 * scale) {
            Image(systemName: symbol)
                .font(.system(size: fontSize, weight: .semibold))
                .widgetAccentable()
            if tile.style == .standard, let age {
                Text(age)
                    .font(.system(size: fontSize, weight: .medium).monospacedDigit())
                    .lineLimit(1).minimumScaleFactor(0.6)
                    .invalidatableContent()
            }
        }
        .foregroundStyle(freshness.hasError || isStale ? AnyShapeStyle(appearance.warningColor) : appearance.secondaryInk)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: isTrailing ? .trailing : .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Refresh. \(spokenState)")
    }
}

enum TileTint {
    static let names = ["pink", "mint", "blue", "violet", "indigo", "orange", "yellow", "green", "red", "teal", "gray"]

    /// Light versions of every tint, for dark gradient backgrounds. Each is at
    /// least 4.5:1 against the gradients in `WidgetAppearance`.
    static func brightComponents(_ name: String) -> WidgetRGB {
        switch name {
        case "pink": return WidgetRGB(red: 1.00, green: 0.74, blue: 0.85)
        case "mint": return WidgetRGB(red: 0.55, green: 0.95, blue: 0.80)
        case "blue": return WidgetRGB(red: 0.62, green: 0.84, blue: 1.00)
        case "violet": return WidgetRGB(red: 0.86, green: 0.77, blue: 1.00)
        case "indigo": return WidgetRGB(red: 0.76, green: 0.78, blue: 1.00)
        case "orange": return WidgetRGB(red: 1.00, green: 0.75, blue: 0.45)
        case "yellow": return WidgetRGB(red: 1.00, green: 0.88, blue: 0.40)
        case "green": return WidgetRGB(red: 0.55, green: 0.95, blue: 0.60)
        case "red": return WidgetRGB(red: 1.00, green: 0.72, blue: 0.70)
        case "teal": return WidgetRGB(red: 0.45, green: 0.92, blue: 0.92)
        default: return WidgetRGB(red: 0.82, green: 0.84, blue: 0.88)
        }
    }

    static func bright(_ name: String) -> Color {
        brightComponents(name).color
    }

    static func color(_ name: String) -> Color {
        switch name {
        case "pink": return .pink
        case "mint": return .mint
        case "blue": return .blue
        case "violet": return .purple
        case "indigo": return .indigo
        case "orange": return .orange
        // System yellow is hard to read on a light widget background.
        case "yellow": return Color(red: 0.85, green: 0.65, blue: 0.0)
        case "green": return .green
        case "red": return .red
        case "teal": return .teal
        default: return .gray
        }
    }
}

private func clamp(_ value: CGFloat, _ lower: CGFloat, _ upper: CGFloat) -> CGFloat {
    min(max(value, lower), upper)
}
