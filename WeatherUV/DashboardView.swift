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
                            VStack(alignment: .leading, spacing: 16) {
                                LazyVGrid(
                                    columns: dynamicTypeSize.isAccessibilitySize
                                        ? [.init(.flexible())]
                                        : [.init(.flexible()), .init(.flexible())],
                                    spacing: 12
                                ) {
                                    ForEach(metrics) { MetricCard(metric: $0, now: timeline.date) }
                                }
                                sourceRefreshDates(now: timeline.date)
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

    private func sourceRefreshDates(now: Date) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(DataSource.allCases.filter { connectorManager.connectedSources.contains($0) }) { source in
                if let date = DashboardStore.updatedAt(for: source) {
                    Text("\(source.displayName): \(UpdatedLabel.long(since: date, now: now).lowercased())")
                } else {
                    Text("\(source.displayName): waiting for a successful refresh")
                }
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}

private struct MetricCard: View {
    let metric: DashboardMetric
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
            Text("From \(metric.originLabel)")
                .font(.caption2).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding()
        .background(.background, in: RoundedRectangle(cornerRadius: 18))
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
