import Foundation
import WidgetKit

/// Calendar identifiers form an explicit whitelist. Account and calendar names
/// remain in EventKit; only the selected identifiers are persisted.
enum CalendarPreferences {
    private static let selectedIDsKey = "calendar.selectedCalendarIDs"
    private static let titlesKey = "calendar.showEventTitles"
    private static let revisionKey = "calendar.preferencesRevision"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: DashboardStore.appGroupID) ?? .standard
    }

    static var selectedCalendarIDs: Set<String> {
        get { Set(defaults.stringArray(forKey: selectedIDsKey) ?? []) }
        set {
            guard newValue != selectedCalendarIDs else { return }
            defaults.set(newValue.sorted(), forKey: selectedIDsKey)
            incrementRevision()
            invalidateSnapshot()
        }
    }

    /// Titles are private unless the user explicitly allows them. The public
    /// metric value remains "Busy" even after opting in.
    static var showEventTitles: Bool {
        get { defaults.bool(forKey: titlesKey) }
        set {
            guard newValue != showEventTitles else { return }
            defaults.set(newValue, forKey: titlesKey)
            incrementRevision()
            invalidateSnapshot()
        }
    }

    static func reset() {
        defaults.removeObject(forKey: selectedIDsKey)
        defaults.removeObject(forKey: titlesKey)
        incrementRevision()
        invalidateSnapshot()
    }

    /// A completed fetch may only publish data captured under this revision.
    static var revision: Int { defaults.integer(forKey: revisionKey) }

    private static func incrementRevision() {
        defaults.set(revision + 1, forKey: revisionKey)
    }

    /// Clear private data before refreshing or waiting on a permission prompt.
    static func invalidateSnapshot() {
        DashboardStore.removeMetrics(for: .calendar)
        WidgetCenter.shared.reloadAllTimelines()
    }
}

/// The minimum event data used while building a snapshot. This is never stored.
struct CalendarEventSummary: Equatable {
    let calendarID: String
    let title: String?
    let startsAt: Date
    let endsAt: Date
    let isAllDay: Bool
}

enum CalendarSnapshotBuilder {
    static func selectedIDs(availableIDs: [String], selection: Set<String>) -> Set<String> {
        selection.intersection(availableIDs)
    }

    static func nextEvent(
        in events: [CalendarEventSummary],
        selectedCalendarIDs: Set<String>,
        now: Date
    ) -> CalendarEventSummary? {
        let horizon = Calendar.current.date(byAdding: .day, value: 7, to: now) ?? now
        return events
            .filter {
                selectedCalendarIDs.contains($0.calendarID) && !$0.isAllDay &&
                $0.endsAt > now && $0.startsAt < horizon
            }
            .min { $0.startsAt < $1.startsAt }
    }

    static let agendaLimit = 5

    /// All-day events in progress come first, then timed events by start.
    /// Titles are kept only when the user allows them.
    static func agenda(
        in events: [CalendarEventSummary],
        selectedCalendarIDs: Set<String>,
        now: Date,
        showEventTitles: Bool
    ) -> [AgendaItem] {
        let horizon = Calendar.current.date(byAdding: .day, value: 7, to: now) ?? now
        let upcoming = events.filter {
            selectedCalendarIDs.contains($0.calendarID) && $0.endsAt > now && $0.startsAt < horizon
        }
        let allDay = upcoming.filter { $0.isAllDay && $0.startsAt <= now }.sorted { $0.endsAt < $1.endsAt }
        let timed = upcoming.filter { !$0.isAllDay }.sorted { $0.startsAt < $1.startsAt }
        return (allDay + timed).prefix(agendaLimit).map { event in
            let title = event.title?.trimmingCharacters(in: .whitespacesAndNewlines)
            return AgendaItem(
                startsAt: event.startsAt,
                endsAt: event.endsAt,
                isAllDay: event.isAllDay,
                title: showEventTitles && title?.isEmpty == false ? title : nil
            )
        }
    }

    static func agendaMetric(for items: [AgendaItem], emptyDetail: String = "Next 7 days") -> DashboardMetric {
        DashboardMetric(
            id: "agenda",
            source: .calendar,
            title: "Agenda",
            value: items.isEmpty ? "No upcoming events" : "Busy",
            detail: items.isEmpty ? emptyDetail : "",
            symbol: "list.bullet",
            tint: "blue",
            observedAt: items.first { !$0.isAllDay }?.startsAt,
            // Refetch as soon as any listed event ends.
            expiresAt: items.map(\.endsAt).min(),
            items: items
        )
    }

    static func metric(
        for event: CalendarEventSummary?,
        showEventTitles: Bool,
        emptyDetail: String = "Next 7 days"
    ) -> DashboardMetric {
        let eventTitle = event?.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let privateTitle = showEventTitles && eventTitle?.isEmpty == false ? eventTitle : nil
        let detail: String
        if let event {
            // Countdowns are computed when drawn; a stored "in 24 min" would go stale.
            detail = EventTiming.startText(event.startsAt, now: .now)
        } else {
            detail = emptyDetail
        }
        return DashboardMetric(
            id: "nextEvent",
            source: .calendar,
            title: "Up next",
            value: event == nil ? "No upcoming events" : "Busy",
            detail: detail,
            symbol: "calendar",
            tint: "blue",
            observedAt: event?.startsAt,
            privateValue: privateTitle,
            endsAt: event?.endsAt,
            expiresAt: event?.endsAt
        )
    }
}
