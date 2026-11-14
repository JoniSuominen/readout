import SwiftUI

struct DashboardView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var metrics: [DashboardMetric] { connectorManager.metrics }
    private var isRefreshing: Bool { connectorManager.activities.values.contains { $0.isBusy } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SourceRefreshWarnings()

                    if metrics.isEmpty {
                        if isRefreshing {
                            VStack(spacing: 12) {
                                ProgressView()
                                Text("Refreshing…").foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, minHeight: 240)
                        } else {
                            ContentUnavailableView(
                                connectorManager.connectedSources.isEmpty ? "Nothing connected" : "No data yet",
                                systemImage: "link.badge.plus",
                                description: Text("Connect weather, Apple Health, Oura, or Calendar in Sources.")
                            )
                            .frame(minHeight: 240)
                        }
                    } else {
                        // Minute steps keep event countdowns and ages current.
                        TimelineView(.everyMinute) { timeline in
                            VStack(alignment: .leading, spacing: 28) {
                                ForEach(DashboardSection.sections(for: metrics)) { section in
                                    VStack(alignment: .leading, spacing: 10) {
                                        SectionHeader(section: section, now: timeline.date)
                                        LazyVGrid(
                                            columns: dynamicTypeSize.isAccessibilitySize
                                                ? [.init(.flexible())]
                                                : [.init(.flexible(), spacing: 12), .init(.flexible())],
                                            spacing: 12
                                        ) {
                                            ForEach(section.metrics) {
                                                MetricCard(metric: $0, showsOrigin: section.showsOriginOnTiles, now: timeline.date)
                                            }
                                        }
                                    }
                                }
                                sourcesWithoutTiles(now: timeline.date)
                            }
                        }
                    }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Today")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await connectorManager.refreshConnectedSources() }
                    } label: {
                        if isRefreshing {
                            ProgressView()
                        } else {
                            Label("Refresh data", systemImage: "arrow.clockwise")
                        }
                    }
                    .disabled(isRefreshing || connectorManager.connectedSources.isEmpty)
                    .accessibilityLabel("Refresh connected data sources")
                }
            }
            .refreshable { await connectorManager.refreshConnectedSources() }
        }
    }

    /// Section headers carry freshness for sources with tiles. A connected
    /// source that shows nothing is accounted for here instead.
    @ViewBuilder
    private func sourcesWithoutTiles(now: Date) -> some View {
        let idle = DataSource.allCases.filter { source in
            connectorManager.connectedSources.contains(source) && !metrics.contains { $0.source == source }
        }
        if !idle.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(idle) { source in
                    if let date = DashboardStore.updatedAt(for: source) {
                        Text("\(source.displayName): no data · \(UpdatedLabel.long(since: date, now: now).lowercased())")
                    } else {
                        Text("\(source.displayName): waiting for a successful refresh")
                    }
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }
}

private struct SectionHeader: View {
    let section: DashboardSection
    let now: Date

    /// "Open-Meteo · updated 2 min ago". The age is the oldest fetch among
    /// the section's sources and is left out until all of them have one.
    private var caption: String? {
        var parts: [String] = []
        if let origin = section.headerOrigin { parts.append(origin) }
        let dates = section.sources.map { DashboardStore.updatedAt(for: $0) }
        if let oldest = dates.compactMap({ $0 }).min(), dates.allSatisfy({ $0 != nil }) {
            let updated = UpdatedLabel.long(since: oldest, now: now)
            parts.append(parts.isEmpty ? updated : updated.lowercased())
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(section.category.title, systemImage: section.category.symbol)
                .font(.title3.bold())
                .accessibilityAddTraits(.isHeader)
            if let caption {
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 4)
    }
}

private struct MetricCard: View {
    let metric: DashboardMetric
    let showsOrigin: Bool
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(metric.title, systemImage: metric.symbol)
                .font(.subheadline)
                .foregroundStyle(TileTint.color(metric.tint))
                .fixedSize(horizontal: false, vertical: true)
            if metric.id == "nextEvent", let start = metric.observedAt {
                // Start time and title belong together on one line.
                (Text(EventTiming.isOngoing(start: start, end: metric.endsAt, now: now) ? "Now" : EventTiming.startText(start, now: now)).bold()
                    + Text(" " + metric.displayValue))
                    .font(.title3)
                    .fixedSize(horizontal: false, vertical: true)
                Text(EventTiming.relativeText(start: start, end: metric.endsAt, now: now))
                    .font(.caption).foregroundStyle(.secondary)
            } else if metric.id == "agenda", let items = metric.items, !items.isEmpty {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    (Text(EventTiming.agendaText(item, now: now)).bold() + Text(" " + (item.title ?? "Busy")))
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                Text(metric.displayValue).font(.title2.bold()).fixedSize(horizontal: false, vertical: true)
                Text(metric.detail).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if showsOrigin {
                Text("From \(metric.originLabel)")
                    .font(.caption2).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding()
        // The plain background colour is black in dark mode and vanishes
        // against the grouped page background.
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
    }
}

struct SourceRefreshWarnings: View {
    @EnvironmentObject private var connectorManager: ConnectorManager

    private var failedSources: [DataSource] {
        DataSource.allCases.filter {
            if case .failed = connectorManager.activity(for: $0) { return true }
            return false
        }
    }

    var body: some View {
        if !failedSources.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Label("Some sources need attention", systemImage: "exclamationmark.triangle.fill")
                    .font(.subheadline.bold())
                ForEach(failedSources) { source in
                    if case .failed(let message) = connectorManager.activity(for: source) {
                        Text("\(source.displayName): \(message)").font(.footnote)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text("Cached values may be older. Manage connections or try Refresh in Sources.")
                    .font(.footnote)
            }
            .foregroundStyle(.orange)
        }
    }
}
