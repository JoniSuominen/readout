import Foundation
import HealthKit
import EventKit
import WidgetKit

/// Refreshes sources that can be read without opening the app: weather,
/// Apple Health, calendars, and Oura. An Oura account is read with the access
/// token the app saved; only the app signs in or renews that token.
@MainActor
enum WidgetDataRefresher {
    /// Timeline reloads skip sources fetched more recently than this.
    static let passiveMinimumAge: TimeInterval = 10 * 60

    private static var inFlight: Task<Void, Never>?

    /// `userInitiated` comes from the widget's sync button: it refreshes every
    /// eligible source and reports failures. Passive timeline refreshes skip
    /// fresh sources and stay silent, because a locked device makes Health
    /// data briefly unreadable and that is not a failure worth showing.
    static func refresh(userInitiated: Bool) async {
        if let inFlight {
            await inFlight.value
            if !userInitiated { return }
        }
        let task = Task { @MainActor in
            await WidgetDataRefresher.refresh(userInitiated: userInitiated, now: .now) {
                WidgetDataRefresher.connector(for: $0)
            }
        }
        inFlight = task
        await task.value
        inFlight = nil
    }

    static func refresh(
        userInitiated: Bool,
        now: Date,
        connectorFor: @MainActor (DataSource) -> (any DashboardConnector)?
    ) async {
        // Sources fetch side by side; a timeline has only seconds to finish.
        let published = await withTaskGroup(of: Bool.self) { group in
            for source in DataSource.allCases where DashboardStore.connectedSources.contains(source) {
                guard let connector = connectorFor(source) else { continue }
                if !userInitiated, let fetchedAt = DashboardStore.updatedAt(for: source),
                   now.timeIntervalSince(fetchedAt) < passiveMinimumAge {
                    continue
                }
                group.addTask { await WidgetDataRefresher.refresh(source, using: connector, userInitiated: userInitiated) }
            }
            return await group.reduce(false) { $0 || $1 }
        }
        if published && userInitiated {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Returns true when the shared cache changed.
    private static func refresh(
        _ source: DataSource,
        using connector: any DashboardConnector,
        userInitiated: Bool
    ) async -> Bool {
        let calendarRevision = CalendarPreferences.revision
        do {
            let metrics = try await connector.refresh()
            // Disconnecting or changing calendar privacy during the fetch wins.
            guard DashboardStore.connectedSources.contains(source),
                  source != .calendar || calendarRevision == CalendarPreferences.revision else { return false }
            DashboardStore.replaceMetrics(metrics, for: source)
            return true
        } catch {
            guard userInitiated, DashboardStore.connectedSources.contains(source),
                  !isTemporarilyUnreadable(error) else { return false }
            DashboardStore.recordError(error.localizedDescription, for: source)
            return true
        }
    }

    private static func connector(for source: DataSource) -> (any DashboardConnector)? {
        switch source {
        case .weather:
            return WeatherConnector()
        case .appleHealth:
            return HealthKitConnector()
        case .oura:
            return OuraConnectionMethod.stored == .appleHealth ? OuraHealthConnector() : OuraAPIConnector.widgetReader()
        case .calendar:
            // Without full access here, leave the snapshot alone; the app clears
            // it when permission is revoked.
            return EKEventStore.authorizationStatus(for: .event) == .fullAccess ? CalendarConnector() : nil
        }
    }

    private static func isTemporarilyUnreadable(_ error: Error) -> Bool {
        (error as? HKError)?.code == .errorDatabaseInaccessible
    }
}
