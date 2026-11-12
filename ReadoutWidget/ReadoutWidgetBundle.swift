import WidgetKit
import SwiftUI

@main
struct ReadoutWidgetBundle: WidgetBundle {
    var body: some Widget {
        ReadoutWidget()          // Home Screen small
        ReadoutAccessoryWidget() // Lock Screen circular / rectangular / inline
    }
}
