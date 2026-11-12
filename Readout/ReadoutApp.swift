import SwiftUI

@main
struct ReadoutApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        ReadoutBackgroundRefresh.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { ReadoutBackgroundRefresh.schedule() }
        }
    }
}
