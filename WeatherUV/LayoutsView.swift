import SwiftUI
import UIKit
import WidgetKit

/// Lists tile layouts by widget size. Each Home Screen widget picks one in
/// Edit Widget.
struct LayoutsView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    @State private var designs = WidgetDesignStore.designs
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                ForEach(WidgetCanvas.allCases.filter { !$0.isPadOnly || UIDevice.current.userInterfaceIdiom == .pad }) { canvas in
                    Section(canvas.displayName) {
                        ForEach(designs.filter { $0.canvas == canvas }) { design in
                            NavigationLink(value: design.id) {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text(design.name.isEmpty ? "Untitled" : design.name).font(.headline)
                                    LayoutPreview(
                                        design: design,
                                        metrics: connectorManager.metrics,
                                        connectedSources: connectorManager.connectedSources,
                                        scale: canvas == .small ? 0.8 : 0.72
                                    )
                                }
                                .padding(.vertical, 4)
                            }
                        }
                        Button {
                            let design = WidgetDesignStore.newDesign(for: canvas)
                            WidgetDesignStore.save(design)
                            reload()
                            path.append(design.id)
                        } label: {
                            Label("New \(canvas.displayName.lowercased()) layout", systemImage: "plus")
                        }
                    }
                }
                Section {
                    Text("Add a widget: touch and hold the Home Screen, tap Edit, then Add Widget, and choose this app. To pick a layout, touch and hold the widget and choose Edit Widget.")
                    Text("The refresh tile updates the widget. Its number is the minutes since the oldest value shown was fetched.")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .navigationTitle("Widgets")
            .navigationDestination(for: String.self) { id in
                if let design = designs.first(where: { $0.id == id }) {
                    LayoutEditorView(design: design) { reload() }
                }
            }
            .onAppear { reload() }
        }
    }

    private func reload() {
        designs = WidgetDesignStore.designs
    }
}

/// A widget-sized, non-interactive rendering of a layout.
struct LayoutPreview: View {
    let design: WidgetDesign
    let metrics: [DashboardMetric]
    let connectedSources: Set<DataSource>
    var scale: CGFloat = 1

    /// Real values when anything is connected; sample values otherwise.
    private var shownMetrics: [DashboardMetric] {
        WidgetPrivacy.render(connectedSources.isEmpty ? DashboardMetric.defaults : metrics, mode: .showValues)
    }

    var body: some View {
        let size = design.canvas.previewSize
        let tileIDs = Set(design.tiles.map(\.metricID))
        TileGridView(
            design: design,
            canvas: design.canvas,
            metrics: shownMetrics,
            context: TileContext(
                now: .now,
                freshness: DashboardFreshness.stored(for: shownMetrics.filter { tileIDs.contains($0.id) }),
                privacyMode: .showValues,
                connectedSources: connectedSources.isEmpty ? Set(DataSource.allCases) : connectedSources,
                isInteractive: false,
                showsUnavailable: true,
                scale: scale
            )
        )
        .padding(WidgetCanvas.contentMargin * scale)
        .frame(width: size.width * scale, height: size.height * scale)
        .background { design.appearance.previewBackground.clipShape(RoundedRectangle(cornerRadius: 22 * scale, style: .continuous)) }
        .overlay(RoundedRectangle(cornerRadius: 22 * scale, style: .continuous).stroke(Color.secondary.opacity(0.25)))
        .widgetAppearance(design.appearance)
        .allowsHitTesting(false)
        .accessibilityElement(children: .contain)
    }
}
