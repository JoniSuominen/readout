import EventKit
import Foundation
import XCTest
@testable import Readout

final class CalendarPrivacyTests: XCTestCase {
    override func setUp() {
        super.setUp()
        DashboardStore.deleteLocalData()
    }

    override func tearDown() {
        DashboardStore.deleteLocalData()
        super.tearDown()
    }

    func testNewPreferencesRequireExplicitCalendarAndTitleOptIn() {
        XCTAssertTrue(CalendarPreferences.selectedCalendarIDs.isEmpty)
        XCTAssertFalse(CalendarPreferences.showEventTitles)
        XCTAssertTrue(CalendarSnapshotBuilder.selectedIDs(
            availableIDs: ["personal", "work"], selection: []
        ).isEmpty)
        XCTAssertEqual(CalendarSnapshotBuilder.selectedIDs(
            availableIDs: ["personal", "work"], selection: ["work", "removed"]
        ), ["work"])
    }

    func testNextEventOnlyComesFromSelectedCalendarAndExcludesEndedAndAllDayEvents() {
        let now = Date(timeIntervalSince1970: 10_000)
        let selected = event("Selected meeting", calendar: "personal", startsAt: now.addingTimeInterval(600))
        let unselected = event("Private work meeting", calendar: "work", startsAt: now.addingTimeInterval(100))
        let allDay = event("All day", calendar: "personal", startsAt: now, isAllDay: true)
        let ended = event("Ended", calendar: "personal", startsAt: now.addingTimeInterval(-7200))
        let distant = event("Distant", calendar: "personal", startsAt: now.addingTimeInterval(9 * 86400))
        let events = [unselected, allDay, ended, distant, selected]

        XCTAssertEqual(CalendarSnapshotBuilder.nextEvent(
            in: events, selectedCalendarIDs: ["personal"], now: now
        ), selected)
        XCTAssertNil(CalendarSnapshotBuilder.nextEvent(in: events, selectedCalendarIDs: [], now: now))
        XCTAssertNil(CalendarSnapshotBuilder.nextEvent(in: events, selectedCalendarIDs: ["missing"], now: now))
    }

    func testDefaultSnapshotContainsNoEventTitleEvenInEncodedCache() throws {
        let secret = "Confidential medical appointment"
        let metric = CalendarSnapshotBuilder.metric(for: event(secret), showEventTitles: false)
        XCTAssertEqual(metric.value, "Busy")
        XCTAssertNil(metric.privateValue)
        XCTAssertFalse(metric.detail.contains(secret))
        let encoded = String(decoding: try JSONEncoder().encode(metric), as: UTF8.self)
        XCTAssertFalse(encoded.contains(secret), "A hidden title must never enter the shared snapshot")
    }

    func testOptInStoresPrivateTitleButKeepsPublicValueGeneric() throws {
        let metric = CalendarSnapshotBuilder.metric(for: event("  Private meeting  "), showEventTitles: true)
        XCTAssertEqual(metric.value, "Busy")
        XCTAssertEqual(metric.privateValue, "Private meeting")
        let roundTrip = try JSONDecoder().decode(DashboardMetric.self, from: JSONEncoder().encode(metric))
        XCTAssertEqual(roundTrip.privateValue, "Private meeting")
        XCTAssertEqual(roundTrip.value, "Busy")
        XCTAssertNil(CalendarSnapshotBuilder.metric(for: event("  \n "), showEventTitles: true).privateValue)
    }

    func testNoSelectedCalendarHasHonestEmptyStateAndNoTitle() {
        let metric = CalendarSnapshotBuilder.metric(
            for: nil, showEventTitles: true, emptyDetail: "Choose calendars in Sources"
        )
        XCTAssertEqual(metric.value, "No upcoming events")
        XCTAssertEqual(metric.detail, "Choose calendars in Sources")
        XCTAssertNil(metric.privateValue)
        XCTAssertNil(metric.observedAt)
    }

    func testDisablingTitlesImmediatelyRemovesPrivateCalendarSnapshotAndAdvancesRevision() {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        CalendarPreferences.showEventTitles = true
        seedSnapshot()
        let previousRevision = CalendarPreferences.revision

        CalendarPreferences.showEventTitles = false

        XCTAssertGreaterThan(CalendarPreferences.revision, previousRevision)
        XCTAssertFalse(CalendarPreferences.showEventTitles)
        XCTAssertEqual(DashboardStore.metrics.map(\.id), ["steps"])
        XCTAssertNil(DashboardStore.updatedAt(for: .calendar))
        XCTAssertNotNil(DashboardStore.updatedAt(for: .appleHealth))
    }

    func testDeselectingCalendarsImmediatelyInvalidatesPreviouslyFetchedEvent() {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        seedSnapshot()
        let previousRevision = CalendarPreferences.revision

        CalendarPreferences.selectedCalendarIDs = []

        XCTAssertGreaterThan(CalendarPreferences.revision, previousRevision)
        XCTAssertTrue(CalendarPreferences.selectedCalendarIDs.isEmpty)
        XCTAssertEqual(DashboardStore.metrics.map(\.id), ["steps"])
        XCTAssertNil(DashboardStore.updatedAt(for: .calendar))
    }

    func testResetClearsSelectionTitlesAndSnapshotWithoutResettingRevision() {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        CalendarPreferences.showEventTitles = true
        seedSnapshot()
        let previousRevision = CalendarPreferences.revision

        CalendarPreferences.reset()

        XCTAssertGreaterThan(CalendarPreferences.revision, previousRevision)
        XCTAssertTrue(CalendarPreferences.selectedCalendarIDs.isEmpty)
        XCTAssertFalse(CalendarPreferences.showEventTitles)
        XCTAssertFalse(DashboardStore.metrics.contains { $0.source == .calendar })
    }

    @MainActor
    func testSelectorDoesNotReadCalendarsOrRetainPrivateCacheWithoutFullAccess() async {
        for status in [EKAuthorizationStatus.notDetermined, .restricted, .denied, .writeOnly] {
            seedSnapshot()
            var reads = 0
            let store = CalendarSelectionStore(
                authorizationStatus: { status },
                readCalendars: {
                    reads += 1
                    return [SelectableCalendar(id: "private", title: "Private", accountName: "Work")]
                }
            )

            await store.load()

            XCTAssertEqual(reads, 0)
            XCTAssertTrue(store.calendars.isEmpty)
            XCTAssertNotNil(store.errorMessage)
            XCTAssertFalse(DashboardStore.metrics.contains { $0.source == .calendar })
        }
    }

    @MainActor
    func testSelectorClearsPreviouslyLoadedCalendarNamesWhenPermissionIsRevoked() async {
        var status = EKAuthorizationStatus.fullAccess
        var reads = 0
        let calendars = [
            SelectableCalendar(id: "work", title: "Meetings", accountName: "Work"),
            SelectableCalendar(id: "personal", title: "Home", accountName: "Personal")
        ]
        let store = CalendarSelectionStore(
            authorizationStatus: { status },
            readCalendars: { reads += 1; return calendars }
        )
        await store.load()
        XCTAssertEqual(store.calendars.map(\.id), ["personal", "work"])
        XCTAssertNil(store.errorMessage)

        status = .denied
        await store.load()
        XCTAssertEqual(reads, 1)
        XCTAssertTrue(store.calendars.isEmpty)
        XCTAssertNotNil(store.errorMessage)
    }

    private func event(
        _ title: String,
        calendar: String = "personal",
        startsAt: Date = Date(timeIntervalSince1970: 10_000),
        isAllDay: Bool = false
    ) -> CalendarEventSummary {
        CalendarEventSummary(
            calendarID: calendar, title: title, startsAt: startsAt,
            endsAt: startsAt.addingTimeInterval(3600), isAllDay: isAllDay
        )
    }

    private func seedSnapshot() {
        DashboardStore.replaceMetrics([
            DashboardMetric(id: "steps", source: .appleHealth, title: "Steps", value: "10", detail: "Today", symbol: "figure.walk", tint: "mint")
        ], for: .appleHealth)
        DashboardStore.replaceMetrics([
            CalendarSnapshotBuilder.metric(for: event("Private appointment"), showEventTitles: true)
        ], for: .calendar)
    }
}
