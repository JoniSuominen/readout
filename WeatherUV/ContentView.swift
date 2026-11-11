import SwiftUI
import UIKit
import WidgetKit

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
    }
}

private struct DashboardView: View {
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

private struct SourcesView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    @State private var showingDeleteConfirmation = false
    @State private var showingPrivacy = false
    @State private var showingCalendarSettings = false
    @State private var showingLocationPicker = false
    @State private var pendingSource: DataSource?

    var body: some View {
        NavigationStack {
            List {
                Section("Oura connection") {
                    Picker("Connect Oura through", selection: ouraMethodBinding) {
                        ForEach(OuraConnectionMethod.allCases) { method in
                            Text(method.displayName).tag(method)
                                .disabled(method == .direct && !connectorManager.directOuraAvailable)
                        }
                    }
                    .disabled(connectorManager.activity(for: .oura).isBusy || connectorManager.isDeletingLocalData)
                    if !connectorManager.directOuraAvailable {
                        Text("Oura account access is unavailable in this build. You can use sleep data that Oura shares with Apple Health.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Text("Changing the connection method disconnects the current Oura source. Connect again to use the new method.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                Section("Sources") {
                    ForEach(DataSource.allCases) { source in
                        sourceRow(source)
                    }
                }

                Section("Privacy") {
                    Label("Cached values are shared locally with your widgets. Weather requests send an approximate location to Open-Meteo. Oura account access also contacts Oura and the secure sign-in service.", systemImage: "lock.shield.fill")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("Read privacy policy") { showingPrivacy = true }
                }

                Section {
                    Button("Delete local data", role: .destructive) { showingDeleteConfirmation = true }
                        .disabled(connectorManager.isDeletingLocalData)
                    if connectorManager.isDeletingLocalData {
                        ProgressView("Deleting local data and disconnecting…")
                    }
                    if let result = connectorManager.dataDeletionResult {
                        Text("Cached dashboard and widget data removed.")
                            .font(.footnote).foregroundStyle(.secondary)
                        DisconnectFeedbackView(source: .oura, result: result)
                    }
                }
            }
            .navigationTitle("Sources")
            .sheet(item: $pendingSource) { source in
                PermissionPrimer(source: source, ouraMethod: connectorManager.ouraConnectionMethod) {
                    pendingSource = nil
                    Task {
                        // The primer sheet is still animating away; iOS cannot present
                        // the HealthKit permission sheet over it, so the request would hang.
                        try? await Task.sleep(for: .milliseconds(700))
                        await connectorManager.connect(source)
                    }
                } chooseCity: {
                    pendingSource = nil
                    showingLocationPicker = true
                }
            }
            .sheet(isPresented: $showingPrivacy) { PrivacyPolicyView() }
            .sheet(isPresented: $showingCalendarSettings) { CalendarSettingsView() }
            .sheet(isPresented: $showingLocationPicker) {
                LocationPickerView { location in
                    showingLocationPicker = false
                    Task { await connectorManager.setWeatherLocation(location) }
                }
            }
            .confirmationDialog(
                "Delete local data?",
                isPresented: $showingDeleteConfirmation,
                titleVisibility: .visible
            ) {
                Button("Delete local data", role: .destructive) {
                    Task { await connectorManager.deleteLocalData() }
                }
            } message: {
                Text("This removes cached metrics, widget layouts, the weather location, app preferences, and connection state. Saved Oura credentials are removed and Oura access is revoked where possible; the result is shown here. Provider records and iOS permissions remain unchanged.")
            }
        }
    }

    private var ouraMethodBinding: Binding<OuraConnectionMethod> {
        Binding(
            get: { connectorManager.ouraConnectionMethod },
            set: { method in
                guard method != .direct || connectorManager.directOuraAvailable else { return }
                Task { await connectorManager.setOuraConnectionMethod(method) }
            }
        )
    }

    private func sourceRow(_ source: DataSource) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: source.symbol)
                    .frame(width: 38, height: 38)
                    .foregroundStyle(.white)
                    .background(sourceColor(source), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(source.displayName).font(.headline)
                    Text(description(source)).font(.subheadline).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if case .failed(let message) = connectorManager.activity(for: source) {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
            sourceButton(source)
            if source == .weather && connectorManager.connectedSources.contains(source) {
                Button {
                    showingLocationPicker = true
                } label: {
                    Label("Location: \(WeatherLocationStore.location?.name ?? "Current location")", systemImage: "location")
                }
                .disabled(connectorManager.isDeletingLocalData || connectorManager.activity(for: .weather).isBusy)
            }
            if source == .weather {
                // Open-Meteo data is licensed CC BY 4.0 and requires this credit.
                Link("Weather data by Open-Meteo.com", destination: URL(string: "https://open-meteo.com/")!)
                    .font(.footnote)
            }
            if source == .googleCalendar && connectorManager.connectedSources.contains(source) {
                Button {
                    showingCalendarSettings = true
                } label: {
                    Label("Choose calendars and title privacy", systemImage: "slider.horizontal.3")
                }
                .disabled(connectorManager.isDeletingLocalData)
                Text(CalendarPreferences.selectedCalendarIDs.isEmpty
                    ? "No calendars selected. Choose at least one to show events."
                    : "Event titles are \(CalendarPreferences.showEventTitles ? "shown" : "hidden").")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let result = connectorManager.disconnectResults[source] {
                DisconnectFeedbackView(source: source, result: result)
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func sourceButton(_ source: DataSource) -> some View {
        let activity = connectorManager.activity(for: source)
        if activity.isBusy {
            HStack {
                ProgressView()
                Text(activity == .connecting ? "Connecting…" : activity == .disconnecting ? "Disconnecting…" : "Refreshing…")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        } else if connectorManager.connectedSources.contains(source) {
            Menu {
                Button("Refresh") { Task { await connectorManager.refresh(source) } }
                Button("Disconnect", role: .destructive) { Task { await connectorManager.disconnect(source) } }
            } label: {
                Label("Connected", systemImage: "checkmark.circle")
            }
            .buttonStyle(.bordered)
            .tint(.green)
            .accessibilityLabel("Manage connected \(source.displayName)")
        } else {
            Button("Connect") { pendingSource = source }
                .buttonStyle(.bordered)
                .tint(.indigo)
                .disabled(connectorManager.isDeletingLocalData
                    || connectorManager.disconnectResults[source]?.localCredentialsCleared == false
                    || (source == .oura && connectorManager.ouraConnectionMethod == .direct && !connectorManager.directOuraAvailable))
                .accessibilityLabel("Connect \(source.displayName)")
        }
    }

    private func sourceColor(_ source: DataSource) -> Color {
        switch source {
        case .weather: return .orange
        case .appleHealth: return .pink
        case .oura: return .purple
        case .googleCalendar: return .blue
        }
    }

    private func description(_ source: DataSource) -> String {
        switch source {
        case .weather: return "Temperature, UV index, rain, and tomorrow's forecast"
        case .appleHealth: return "Steps, heart rate, resting heart rate, and HRV"
        case .oura:
            return connectorManager.ouraConnectionMethod == .direct
                ? "Readiness and sleep from your Oura account"
                : "Oura sleep shared with Apple Health"
        case .googleCalendar: return "Calendars on this iPhone, including Google accounts"
        }
    }
}

private struct CalendarSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var connectorManager: ConnectorManager
    @StateObject private var calendarStore = CalendarSelectionStore()
    @State private var selectedCalendarIDs = CalendarPreferences.selectedCalendarIDs
    @State private var showEventTitles = CalendarPreferences.showEventTitles
    @State private var isLoading = true

    private var controlsUnavailable: Bool {
        connectorManager.isDeletingLocalData ||
        !connectorManager.connectedSources.contains(.googleCalendar) ||
        connectorManager.activity(for: .googleCalendar) == .disconnecting
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
        Task { await connectorManager.refresh(.googleCalendar) }
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

private struct DisconnectFeedbackView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    let source: DataSource
    let result: DisconnectResult

    private var color: Color {
        if !result.localCredentialsCleared { return .red }
        return result.requiresAttention ? .orange : .secondary
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(
                result.warningMessage ?? result.statusMessage,
                systemImage: result.requiresAttention ? "exclamationmark.triangle.fill" : "checkmark.shield"
            )
            .font(.footnote)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)

            if source == .oura {
                if !result.localCredentialsCleared {
                    Button("Retry removing Oura credentials") {
                        Task { await connectorManager.retryOuraDisconnection() }
                    }
                    .disabled(connectorManager.isDeletingLocalData || connectorManager.activity(for: .oura).isBusy)
                }
                if result.requiresAttention || result.remoteAuthorization == .noStoredCredentials {
                    Link("Review access in your Oura account", destination: URL(string: "https://cloud.ouraring.com/")!)
                        .font(.footnote)
                }
            }
        }
    }
}

private struct PermissionPrimer: View {
    @Environment(\.dismiss) private var dismiss
    let source: DataSource
    let ouraMethod: OuraConnectionMethod
    let connect: () -> Void
    let chooseCity: () -> Void

    private var explanation: String {
        switch source {
        case .weather:
            return "Weather comes from Open-Meteo. Use your approximate location, or choose a city if you prefer not to share location. Only a rounded coordinate is sent."
        case .appleHealth:
            return "Reads your step count, heart rate, resting and walking heart rate, and heart rate variability from Apple Health. Pick which one each tile shows in Widgets. Nothing is written to your health records. Choose the categories to share on the next iOS screen."
        case .oura where ouraMethod == .direct:
            return "Sign in with Oura to read your readiness and sleep. Oura and a secure sign-in service handle authorization and credential refresh. Access credentials are protected in the iOS Keychain. You can disconnect from Sources at any time."
        case .oura:
            return "Reads sleep records from Apple Health and shows only records written by Oura. First enable Apple Health sharing in the Oura app. This connection does not include Oura Readiness or sleep scores."
        case .googleCalendar:
            return "Reads calendars on this iPhone, including Google accounts already added to iOS. After connecting, choose which calendars to include; none are selected automatically. Event titles stay hidden unless you turn them on. Your events are never changed."
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Label("Connect \(source.displayName)", systemImage: source.symbol)
                        .font(.title2.bold())
                    Text(explanation).fixedSize(horizontal: false, vertical: true)
                    Text("Your selection controls what appears in the app and widgets. Disconnecting stops using this source and clears its cached data. System permissions are managed in iOS Settings.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Button(action: connect) {
                        Text(primaryActionTitle).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    if source == .weather {
                        Button(action: chooseCity) {
                            Text("Choose a city").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered).controlSize(.large)
                    }
                }
                .padding()
            }
            .navigationTitle("Your permission")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var primaryActionTitle: String {
        switch source {
        case .weather: return "Use my location"
        case .oura where ouraMethod == .direct: return "Continue to Oura"
        default: return "Continue"
        }
    }
}

private struct PrivacyPolicyView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Data you choose to connect") {
                    Text("The app reads the Apple Health categories, Oura account metrics, and calendars you authorize. Health and calendar connections read records on this device. Oura account access retrieves data from Oura over the network.")
                    Text("Weather is fetched from Open-Meteo using a coordinate rounded to about 1 km, either your approximate location or a city you choose. City search sends the text you type to Open-Meteo.")
                }
                Section("Storage and sharing") {
                    Text("A local cache stores the values needed by the app and widgets in their shared app container. Widgets may refresh weather, Apple Health, and Calendar values themselves. Oura credentials are protected in the iOS Keychain and are used only by the app. Health and calendar data are never sold or used for advertising.")
                }
                Section("Your controls") {
                    Text("Choose exactly which calendars are read, and whether event titles appear. Calendar titles are hidden by default. Each Home Screen widget uses a layout you design and its own privacy mode; Lock Screen widgets have their own metric and privacy mode. Discreet widgets hide health values and event titles, including from accessibility labels.")
                    Text("Disconnect stops use of a source and clears its cached values. Saved Oura credentials are removed and Oura access is revoked where possible, and the result is reported. Delete local data also clears cached values, layouts, the weather location, connection state, and preferences. These actions do not delete records from Apple Health, Oura, or your calendar accounts. Revoke device permissions in iOS Settings and review Oura authorization in your Oura account settings.")
                }
                Section("Widget freshness") {
                    Text("Widgets show the last successfully fetched data and its age in minutes. Tap the refresh tile to update. iOS decides when widgets reload in the background.")
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

#Preview { ContentView() }
