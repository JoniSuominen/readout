import SwiftUI
import UIKit

struct CalendarSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var connectorManager: ConnectorManager
    @StateObject private var calendarStore = CalendarSelectionStore()
    @State private var selectedCalendarIDs = CalendarPreferences.selectedCalendarIDs
    @State private var showEventTitles = CalendarPreferences.showEventTitles
    @State private var isLoading = true

    private var controlsUnavailable: Bool {
        connectorManager.isDeletingLocalData ||
        !connectorManager.connectedSources.contains(.calendar) ||
        connectorManager.activity(for: .calendar) == .disconnecting
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Only calendars you select are used. Leaving every calendar unchecked shows no events.")
                        .foregroundStyle(.secondary)
                }
                Section("Calendars") {
                    if isLoading {
                        ProgressView("Loading calendars…")
                    }
                    if let message = calendarStore.errorMessage {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                        Link("Open Settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                    } else if !isLoading && calendarStore.calendars.isEmpty {
                        Text("No calendars found.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(calendarStore.calendars) { calendar in
                        Toggle(isOn: calendarBinding(calendar.id)) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(calendar.title)
                                Text(calendar.accountName).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(controlsUnavailable || isLoading || calendarStore.errorMessage != nil)
                    }
                    if selectedCalendarIDs.isEmpty {
                        Text("No calendars selected. No events show until you select one.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if !selectedCalendarIDs.isEmpty {
                        Button("Deselect all calendars") { saveCalendarSelection([]) }
                            .disabled(controlsUnavailable)
                    }
                    if !isLoading && calendarStore.errorMessage == nil &&
                        !selectedCalendarIDs.subtracting(Set(calendarStore.calendars.map(\.id))).isEmpty {
                        Text("Some selected calendars are no longer available. Only selected calendars in this list can contribute events.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if calendarStore.errorMessage == nil {
                    Section("Missing a calendar?") {
                        Text("Readout shows every calendar account on this iPhone: Google, Outlook, Exchange, iCloud, and others. To add one, open the Settings app, find Calendar (under Apps), then choose Calendar Accounts → Add Account. No separate sign-in is needed here; this list updates when you come back.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Link("Open Settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                    }
                }
                Section("Event title privacy") {
                    Toggle("Show event titles", isOn: eventTitleBinding)
                        .disabled(controlsUnavailable || (calendarStore.errorMessage != nil && !showEventTitles))
                    Text("Off shows only availability and timing. On allows event titles in the app and in widgets set to show values. Discreet widgets always hide titles.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("System permission") {
                    Text("This list reads calendars only with full Calendar access. Disconnecting Calendar here does not revoke iOS permission. Manage permission in Settings.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Calendar privacy")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await loadCalendars() }
            .refreshable { await loadCalendars() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await loadCalendars() } }
            }
        }
    }

    private var eventTitleBinding: Binding<Bool> {
        Binding(
            get: { showEventTitles },
            set: { newValue in
                guard !controlsUnavailable else { return }
                showEventTitles = newValue
                CalendarPreferences.showEventTitles = newValue
                refreshAfterPreferenceChange()
            }
        )
    }

    private func calendarBinding(_ id: String) -> Binding<Bool> {
        Binding(
            get: { selectedCalendarIDs.contains(id) },
            set: { isSelected in
                var selection = selectedCalendarIDs
                if isSelected { selection.insert(id) } else { selection.remove(id) }
                saveCalendarSelection(selection)
            }
        )
    }

    private func saveCalendarSelection(_ selection: Set<String>) {
        guard !controlsUnavailable else { return }
        selectedCalendarIDs = selection
        CalendarPreferences.selectedCalendarIDs = selection
        refreshAfterPreferenceChange()
    }

    private func refreshAfterPreferenceChange() {
        // The preference setter already removed the shared snapshot. Drop the
        // in-memory copy before the asynchronous fetch can suspend.
        connectorManager.calendarPreferencesDidChange()
        Task { await connectorManager.refresh(.calendar) }
    }

    private func loadCalendars() async {
        isLoading = true
        selectedCalendarIDs = CalendarPreferences.selectedCalendarIDs
        showEventTitles = CalendarPreferences.showEventTitles
        await calendarStore.load()
        if calendarStore.errorMessage != nil {
            connectorManager.calendarPreferencesDidChange()
        }
        isLoading = false
    }
}
