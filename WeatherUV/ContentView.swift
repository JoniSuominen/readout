import SwiftUI
import UIKit

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var selection = 0
    @StateObject private var connectorManager = ConnectorManager.shared

    var body: some View {
        TabView(selection: $selection) {
            DashboardView()
                .tabItem { Label("Today", systemImage: "square.grid.2x2.fill") }
                .tag(0)
            SourcesView()
                .tabItem { Label("Sources", systemImage: "link") }
                .tag(1)
            LayoutsView()
                .tabItem { Label("Widgets", systemImage: "rectangle.3.group.fill") }
                .tag(2)
        }
        .tint(.indigo)
        .environmentObject(connectorManager)
        .task { await connectorManager.refreshIfNeeded() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await connectorManager.refreshIfNeeded() }
            }
        }
        .onOpenURL { open($0) }
    }

    /// A tapped widget tile: hand calendar and health tiles to their own app,
    /// and show Today for the rest.
    private func open(_ url: URL) {
        guard let metricID = TileLink.metricID(from: url) else { return }
        selection = 0
        if let external = TileLink.externalURL(
            for: metricID,
            metrics: connectorManager.metrics,
            ouraMethod: connectorManager.ouraConnectionMethod
        ) {
            UIApplication.shared.open(external)
        }
    }
}

#Preview { ContentView() }
