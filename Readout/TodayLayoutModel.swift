import SwiftUI
import WidgetKit

/// The app's live copy of the Today layout. Every change is saved to the app
/// group so Lock Screen widgets set to Automatic follow it.
@MainActor
final class TodayLayoutModel: ObservableObject {
    static let shared = TodayLayoutModel()

    @Published private(set) var layout: TodayLayout

    init() {
        layout = TodayLayoutStore.layout
    }

    /// Picks up a reset from Delete local data.
    func reload() {
        let stored = TodayLayoutStore.layout
        if stored != layout { layout = stored }
    }

    func update(_ change: (inout TodayLayout) -> Void) {
        var next = layout
        change(&next)
        next = next.normalized()
        guard next != layout else { return }
        layout = next
        TodayLayoutStore.layout = next
        WidgetCenter.shared.reloadAllTimelines()
    }

    func reset() {
        TodayLayoutStore.reset()
        layout = TodayLayoutStore.layout
        WidgetCenter.shared.reloadAllTimelines()
    }

    func isShown(_ metricID: String) -> Bool { layout.isShown(metricID) }

    func shownBinding(_ metricID: String) -> Binding<Bool> {
        Binding(
            get: { self.layout.isShown(metricID) },
            set: { shown in self.update { $0.setShown(metricID, shown) } }
        )
    }
}
