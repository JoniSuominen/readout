import EventKit
import Foundation

final class CalendarConnector: DashboardConnector {
    let source: DataSource = .calendar

    private let eventStore: EKEventStore

    init(eventStore: EKEventStore = EKEventStore()) {
        self.eventStore = eventStore
    }

    func authorize() async throws {
        let granted = try await eventStore.requestFullAccessToEvents()
        guard granted else {
            CalendarPreferences.invalidateSnapshot()
            throw ConnectorError.authorizationDenied("Calendar access was not granted.")
        }
    }

    @discardableResult
    func clearSnapshotIfAccessUnavailable() -> Bool {
        guard EKEventStore.authorizationStatus(for: .event) != .fullAccess else { return false }
        guard DashboardStore.metrics.contains(where: { $0.source == .calendar }) else { return false }
        CalendarPreferences.invalidateSnapshot()
        return true
    }

    func refresh() async throws -> [DashboardMetric] {
        guard EKEventStore.authorizationStatus(for: .event) == .fullAccess else {
            CalendarPreferences.invalidateSnapshot()
            throw ConnectorError.authorizationDenied("Allow full calendar access in Settings to refresh events.")
        }

        let selectedIDs = CalendarPreferences.selectedCalendarIDs
        guard !selectedIDs.isEmpty else {
            return Self.emptyMetrics("Choose calendars in Sources")
        }
        let availableCalendars = eventStore.calendars(for: .event)
        let availableSelectedIDs = CalendarSnapshotBuilder.selectedIDs(
            availableIDs: availableCalendars.map(\.calendarIdentifier),
            selection: selectedIDs
        )
        let calendars = availableCalendars.filter { availableSelectedIDs.contains($0.calendarIdentifier) }
        guard !calendars.isEmpty else {
            return Self.emptyMetrics("Selected calendars are unavailable")
        }

        let now = Date.now
        let end = Calendar.current.date(byAdding: .day, value: 7, to: now) ?? now
        // Never pass nil: EventKit interprets that as every available calendar.
        let predicate = eventStore.predicateForEvents(withStart: now, end: end, calendars: calendars)
        let events = eventStore.events(matching: predicate).map {
            CalendarEventSummary(
                calendarID: $0.calendar.calendarIdentifier,
                title: $0.title,
                startsAt: $0.startDate,
                endsAt: $0.endDate,
                isAllDay: $0.isAllDay
            )
        }
        let nextEvent = CalendarSnapshotBuilder.nextEvent(
            in: events,
            selectedCalendarIDs: selectedIDs,
            now: now
        )
        let agenda = CalendarSnapshotBuilder.agenda(
            in: events,
            selectedCalendarIDs: selectedIDs,
            now: now,
            showEventTitles: CalendarPreferences.showEventTitles
        )
        return [
            CalendarSnapshotBuilder.metric(for: nextEvent, showEventTitles: CalendarPreferences.showEventTitles),
            CalendarSnapshotBuilder.agendaMetric(for: agenda)
        ]
    }

    private static func emptyMetrics(_ detail: String) -> [DashboardMetric] {
        [
            CalendarSnapshotBuilder.metric(for: nil, showEventTitles: false, emptyDetail: detail),
            CalendarSnapshotBuilder.agendaMetric(for: [], emptyDetail: detail)
        ]
    }

    func disconnect() async -> DisconnectResult {
        // EventKit has no per-app revocation API. Users revoke access in Settings.
        return .localOnly
    }
}
