import SwiftUI

struct PrivacyPolicyView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Data you choose to connect") {
                    Text("The app reads the Apple Health categories, Oura account metrics, and calendars you authorize. Health and calendar connections read records on this device. Oura account access retrieves data from Oura over the network.")
                    Text("Weather and air quality are fetched from Open-Meteo using a coordinate rounded to about 1 km, either your approximate location or a city you choose. City search sends the text you type to Open-Meteo.")
                }
                Section("Storage and sharing") {
                    Text("A local cache stores the values needed by the app and widgets in their shared app container. Widgets may refresh weather, Apple Health, Calendar, and Oura values themselves. Oura credentials are protected in the iOS Keychain and shared only between the app and its widget. The widget reads Oura with them; only the app signs in, renews, or removes them. Health and calendar data are never sold or used for advertising.")
                }
                Section("Your controls") {
                    Text("Choose exactly which calendars are read, and whether event titles appear. Calendar titles are hidden by default. Each Home Screen widget uses a layout you design and its own privacy mode; Lock Screen widgets have their own metric and privacy mode. Discreet widgets hide health values and event titles, including from accessibility labels.")
                    Text("Disconnect stops use of a source and clears its cached values. Saved Oura credentials are removed and Oura access is revoked where possible, and the result is reported. Delete local data also clears cached values, layouts, the weather location, connection state, and preferences. These actions do not delete records from Apple Health, Oura, or your calendar accounts. Revoke device permissions in iOS Settings and review Oura authorization in your Oura account settings.")
                }
                Section("Widget freshness") {
                    Text("Widgets show the last successfully fetched data and its age in minutes. Tap the refresh tile to update. iOS decides when widgets reload in the background.")
                    Text("Tapping a tile opens Readout, which hands calendar tiles to Calendar and health tiles to Apple Health. The link names only the tile, never its value.")
                }
            }
            .navigationTitle("Privacy policy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
