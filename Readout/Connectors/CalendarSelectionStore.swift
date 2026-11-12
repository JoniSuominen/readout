import Combine
import EventKit
import Foundation

struct SelectableCalendar: Identifiable, Equatable {
    let id: String
    let title: String
    let accountName: String
}

@MainActor
final class CalendarSelectionStore: ObservableObject {
    @Published private(set) var calendars: [SelectableCalendar] = []
    @Published private(set) var errorMessage: String?

    private let authorizationStatus: () -> EKAuthorizationStatus
    private let readCalendars: () -> [SelectableCalendar]

    init(eventStore: EKEventStore = EKEventStore()) {
        authorizationStatus = { EKEventStore.authorizationStatus(for: .event) }
        readCalendars = {
            eventStore.calendars(for: .event).map {
                SelectableCalendar(
                    id: $0.calendarIdentifier,
                    title: $0.title,
                    accountName: $0.source.title
                )
            }
        }
    }

    /// Injectable reads keep permission-denied behavior testable without
    /// requesting actual access to the test device's calendars.
    init(
        authorizationStatus: @escaping () -> EKAuthorizationStatus,
        readCalendars: @escaping () -> [SelectableCalendar]
    ) {
        self.authorizationStatus = authorizationStatus
        self.readCalendars = readCalendars
    }

    /// The Connect action owns the permission primer and system prompt. Merely
    /// opening the selector must not request access or read calendars without it.
    func load() async {
        calendars = []
        guard authorizationStatus() == .fullAccess else {
            CalendarPreferences.invalidateSnapshot()
            errorMessage = "Connect Calendar and allow full access before choosing calendars. You can change access in Settings."
            return
        }
        calendars = readCalendars().sorted {
            let accountOrder = $0.accountName.localizedStandardCompare($1.accountName)
            if accountOrder != .orderedSame { return accountOrder == .orderedAscending }
            let titleOrder = $0.title.localizedStandardCompare($1.title)
            if titleOrder != .orderedSame { return titleOrder == .orderedAscending }
            return $0.id < $1.id
        }
        errorMessage = nil
    }
}
