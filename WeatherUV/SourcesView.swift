import SwiftUI

struct SourcesView: View {
    @EnvironmentObject private var connectorManager: ConnectorManager
    @State private var showingDeleteConfirmation = false
    @State private var showingPrivacy = false
    @State private var showingCalendarSettings = false
    @State private var showingLocationPicker = false
    @State private var pendingSource: DataSource?
    @AppStorage(UnitPreferences.temperatureKey, store: UnitPreferences.defaults) private var temperatureUnit: TemperatureUnit = .automatic
    @AppStorage(UnitPreferences.rainKey, store: UnitPreferences.defaults) private var rainUnit: RainUnit = .automatic
    @AppStorage(UnitPreferences.windKey, store: UnitPreferences.defaults) private var windUnit: WindUnit = .automatic

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

                Section {
                    Picker("Temperature", selection: $temperatureUnit) {
                        ForEach(TemperatureUnit.allCases) { Text($0.displayName).tag($0) }
                    }
                    Picker("Rain", selection: $rainUnit) {
                        ForEach(RainUnit.allCases) { Text($0.displayName).tag($0) }
                    }
                    Picker("Wind", selection: $windUnit) {
                        ForEach(WindUnit.allCases) { Text($0.displayName).tag($0) }
                    }
                } header: {
                    Text("Units")
                } footer: {
                    Text("Automatic follows your iPhone's region. Changing a unit refreshes the weather.")
                }
                .disabled(connectorManager.isDeletingLocalData)
                .onChange(of: temperatureUnit) { _, _ in refreshWeatherForUnits() }
                .onChange(of: rainUnit) { _, _ in refreshWeatherForUnits() }
                .onChange(of: windUnit) { _, _ in refreshWeatherForUnits() }

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

    /// Weather values are stored already formatted, so new units need a fetch.
    private func refreshWeatherForUnits() {
        guard connectorManager.connectedSources.contains(.weather) else { return }
        Task { await connectorManager.refresh(.weather) }
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
            if source == .calendar && connectorManager.connectedSources.contains(source) {
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
        case .calendar: return .blue
        }
    }

    private func description(_ source: DataSource) -> String {
        switch source {
        case .weather: return "Temperature, UV index, rain, wind, air quality, and tomorrow's forecast"
        case .appleHealth: return "Steps, activity, workouts, heart rate, and HRV"
        case .oura:
            return connectorManager.ouraConnectionMethod == .direct
                ? "Readiness and sleep from your Oura account"
                : "Oura sleep shared with Apple Health"
        case .calendar: return "Calendars on this iPhone, including Google and Outlook accounts"
        }
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

struct PermissionPrimer: View {
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
            return "Reads your step count, active energy, exercise minutes, walking and running distance, workouts, heart rate, resting and walking heart rate, and heart rate variability from Apple Health. Pick which one each tile shows in Widgets. Nothing is written to your health records. Choose the categories to share on the next iOS screen."
        case .oura where ouraMethod == .direct:
            return "Sign in with Oura to read your readiness and sleep. Oura and a secure sign-in service handle authorization and credential refresh. Access credentials are protected in the iOS Keychain. You can disconnect from Sources at any time."
        case .oura:
            return "Reads sleep records from Apple Health and shows only records written by Oura. First enable Apple Health sharing in the Oura app. This connection does not include Oura Readiness or sleep scores."
        case .calendar:
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
