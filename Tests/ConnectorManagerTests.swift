import Foundation
import XCTest
@testable import WeatherUV

final class ConnectorManagerTests: XCTestCase {
    override func setUp() {
        super.setUp()
        DashboardStore.deleteLocalData()
        clearOuraPreferences()
    }

    override func tearDown() {
        DashboardStore.deleteLocalData()
        clearOuraPreferences()
        super.tearDown()
    }

    @MainActor
    func testForegroundRefreshSkipsFreshSourcesButUpdatesOldOnes() async {
        let health = MockDashboardConnector(source: .appleHealth)
        let calendar = MockDashboardConnector(source: .calendar)
        DashboardStore.connectedSources = [.appleHealth, .calendar]
        DashboardStore.replaceMetrics([health.metric], for: .appleHealth, fetchedAt: .now.addingTimeInterval(-600))
        DashboardStore.replaceMetrics([calendar.metric], for: .calendar)
        let manager = ConnectorManager(connectors: [health, calendar], directOuraConnector: MockDashboardConnector(source: .oura))
        await manager.refreshIfNeeded()
        XCTAssertEqual(health.refreshCount, 1)
        XCTAssertEqual(calendar.refreshCount, 0)
    }

    @MainActor
    func testBackgroundRefreshSkipsSourcesAWidgetJustFetched() async {
        let health = MockDashboardConnector(source: .appleHealth)
        let calendar = MockDashboardConnector(source: .calendar)
        DashboardStore.connectedSources = [.appleHealth, .calendar]
        DashboardStore.replaceMetrics([health.metric], for: .appleHealth, fetchedAt: .now.addingTimeInterval(-3600))
        let manager = ConnectorManager(connectors: [health, calendar], directOuraConnector: MockDashboardConnector(source: .oura))
        // A widget timeline fetches the calendar after the app was suspended.
        DashboardStore.replaceMetrics([calendar.metric], for: .calendar, fetchedAt: .now.addingTimeInterval(-60))
        await manager.refreshStaleSources(olderThan: 600)
        XCTAssertEqual(health.refreshCount, 1)
        XCTAssertEqual(calendar.refreshCount, 0)
        XCTAssertEqual(Set(manager.metrics.map(\.source)), [.appleHealth, .calendar])
    }

    @MainActor
    func testRefreshFailureKeepsCachedDataAndOriginalTimestamp() async {
        let health = MockDashboardConnector(source: .appleHealth)
        let oldDate = Date.now.addingTimeInterval(-600)
        DashboardStore.connectedSources = [.appleHealth]
        DashboardStore.replaceMetrics([health.metric], for: .appleHealth, fetchedAt: oldDate)
        health.refreshError = ConnectorError.unavailable("Health data is unavailable")
        let manager = ConnectorManager(connectors: [health], directOuraConnector: MockDashboardConnector(source: .oura))
        await manager.refresh(.appleHealth)
        XCTAssertEqual(manager.metrics, [health.metric])
        XCTAssertEqual(DashboardStore.updatedAt(for: .appleHealth), oldDate)
        XCTAssertEqual(DashboardStore.error(for: .appleHealth), "Health data is unavailable")
        XCTAssertEqual(manager.activity(for: .appleHealth), .failed("Health data is unavailable"))
        health.refreshError = nil
        await manager.refresh(.appleHealth)
        XCTAssertNil(DashboardStore.error(for: .appleHealth))
        XCTAssertEqual(manager.activity(for: .appleHealth), .idle)
    }

    @MainActor
    func testDeleteDuringAuthorizationCannotRestoreData() async {
        let health = MockDashboardConnector(source: .appleHealth)
        let direct = MockDashboardConnector(source: .oura)
        let started = expectation(description: "Authorization started")
        health.holdAuthorization = true
        health.onAuthorize = { started.fulfill() }
        let manager = ConnectorManager(connectors: [health], directOuraConnector: direct)
        let connecting = Task { await manager.connect(.appleHealth) }
        await fulfillment(of: [started], timeout: 2)
        await manager.deleteLocalData()
        health.resumeAuthorization()
        await connecting.value
        XCTAssertTrue(manager.metrics.isEmpty)
        XCTAssertTrue(manager.connectedSources.isEmpty)
        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        XCTAssertEqual(health.refreshCount, 0)
        XCTAssertEqual(direct.disconnectCount, 1, "Tokens must be cleared even if Oura was never marked connected")
    }

    @MainActor
    func testDisconnectDuringRefreshCannotRestoreSnapshot() async {
        let health = MockDashboardConnector(source: .appleHealth)
        DashboardStore.connectedSources = [.appleHealth]
        DashboardStore.replaceMetrics([health.metric], for: .appleHealth)
        let started = expectation(description: "Refresh started")
        health.holdRefresh = true
        health.onRefresh = { started.fulfill() }
        let manager = ConnectorManager(connectors: [health], directOuraConnector: MockDashboardConnector(source: .oura))
        let refreshing = Task { await manager.refresh(.appleHealth) }
        await fulfillment(of: [started], timeout: 2)
        await manager.disconnect(.appleHealth)
        health.resumeRefresh()
        await refreshing.value
        XCTAssertTrue(manager.metrics.isEmpty)
        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        XCTAssertFalse(manager.connectedSources.contains(.appleHealth))
    }

    @MainActor
    func testCalendarTitleOptOutRejectsRefreshStartedUnderPreviousPreferences() async {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        CalendarPreferences.showEventTitles = true
        let calendar = MockDashboardConnector(source: .calendar)
        calendar.customMetric = privateCalendarMetric()
        calendar.holdRefresh = true
        DashboardStore.connectedSources = [.calendar]
        DashboardStore.replaceMetrics([calendar.metric], for: .calendar)
        let started = expectation(description: "Calendar refresh started")
        calendar.onRefresh = { started.fulfill() }
        let manager = ConnectorManager(connectors: [calendar], directOuraConnector: MockDashboardConnector(source: .oura))
        let refreshing = Task { await manager.refresh(.calendar) }
        await fulfillment(of: [started], timeout: 2)

        CalendarPreferences.showEventTitles = false
        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        calendar.resumeRefresh()
        await refreshing.value

        XCTAssertTrue(manager.metrics.isEmpty, "An old fetch must not restore the opted-out event title")
        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        XCTAssertNil(DashboardStore.updatedAt(for: .calendar))
        XCTAssertEqual(manager.activity(for: .calendar), .idle)
    }

    @MainActor
    func testCalendarSelectionChangeRejectsPendingAuthorizationBeforeFetchingTitle() async {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        CalendarPreferences.showEventTitles = true
        let calendar = MockDashboardConnector(source: .calendar)
        calendar.customMetric = privateCalendarMetric()
        calendar.holdAuthorization = true
        let started = expectation(description: "Calendar authorization started")
        calendar.onAuthorize = { started.fulfill() }
        let manager = ConnectorManager(connectors: [calendar], directOuraConnector: MockDashboardConnector(source: .oura))
        let connecting = Task { await manager.connect(.calendar) }
        await fulfillment(of: [started], timeout: 2)

        CalendarPreferences.selectedCalendarIDs = []
        calendar.resumeAuthorization()
        await connecting.value

        XCTAssertEqual(calendar.authorizeCount, 1)
        XCTAssertEqual(calendar.refreshCount, 0)
        XCTAssertFalse(manager.connectedSources.contains(.calendar))
        XCTAssertTrue(manager.metrics.isEmpty)
        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        XCTAssertEqual(manager.activity(for: .calendar), .idle)
    }

    @MainActor
    func testCalendarPreferenceChangeSynchronouslyClearsPublishedPrivateMetrics() {
        CalendarPreferences.selectedCalendarIDs = ["personal"]
        CalendarPreferences.showEventTitles = true
        let calendar = MockDashboardConnector(source: .calendar)
        calendar.customMetric = privateCalendarMetric()
        let health = MockDashboardConnector(source: .appleHealth)
        DashboardStore.connectedSources = [.calendar, .appleHealth]
        DashboardStore.replaceMetrics([calendar.metric], for: .calendar)
        DashboardStore.replaceMetrics([health.metric], for: .appleHealth)
        let manager = ConnectorManager(connectors: [calendar, health], directOuraConnector: MockDashboardConnector(source: .oura))
        XCTAssertTrue(manager.metrics.contains { $0.privateValue != nil })

        CalendarPreferences.showEventTitles = false
        manager.calendarPreferencesDidChange()

        XCTAssertEqual(manager.metrics, [health.metric])
        XCTAssertEqual(DashboardStore.metrics, [health.metric])
        XCTAssertEqual(calendar.refreshCount, 0, "The private cache must disappear before awaiting another fetch")
        XCTAssertEqual(manager.activity(for: .calendar), .idle)
    }

    @MainActor
    func testDeleteReportsDirectCleanupFailureAndRetryStillUsesDirectConnectorAfterMethodReset() async {
        setOuraMethod(.direct)
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let failure = DisconnectResult(localCredentialsCleared: false, remoteAuthorization: .unconfirmed)
        direct.disconnectResult = failure
        DashboardStore.connectedSources = [.oura]
        DashboardStore.replaceMetrics([direct.metric], for: .oura)
        let manager = ConnectorManager(connectors: [local], directOuraConnector: direct)

        await manager.deleteLocalData()

        XCTAssertEqual(manager.dataDeletionResult, failure)
        XCTAssertEqual(manager.disconnectResults[.oura], failure)
        XCTAssertEqual(manager.ouraConnectionMethod, .appleHealth)
        XCTAssertFalse(manager.isDeletingLocalData)
        XCTAssertTrue(manager.metrics.isEmpty)
        XCTAssertTrue(manager.connectedSources.isEmpty)
        XCTAssertEqual(direct.disconnectCount, 1)
        XCTAssertEqual(local.disconnectCount, 1)

        direct.disconnectResult = .revoked
        await manager.retryOuraDisconnection()

        XCTAssertEqual(direct.disconnectCount, 2)
        XCTAssertEqual(local.disconnectCount, 1, "Retry must clean the direct account, even with Apple Health selected")
        XCTAssertEqual(manager.disconnectResults[.oura], .revoked)
        XCTAssertEqual(manager.dataDeletionResult, .revoked)
    }

    @MainActor
    func testFailedDirectCleanupSurvivesRelaunchAndBlocksReconnectUntilDirectRetrySucceeds() async {
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let failure = DisconnectResult(localCredentialsCleared: false, remoteAuthorization: .revoked)
        direct.disconnectResult = failure
        let first = ConnectorManager(connectors: [local], directOuraConnector: direct)
        await first.deleteLocalData()

        let relaunched = ConnectorManager(connectors: [local], directOuraConnector: direct)
        XCTAssertEqual(relaunched.disconnectResults[.oura], failure)
        await relaunched.connect(.oura)

        XCTAssertEqual(local.authorizeCount, 0)
        XCTAssertEqual(direct.authorizeCount, 0)
        XCTAssertEqual(local.refreshCount, 0)
        XCTAssertFalse(relaunched.connectedSources.contains(.oura))
        XCTAssertEqual(relaunched.activity(for: .oura), .failed(failure.statusMessage))

        direct.disconnectResult = .revoked
        await relaunched.retryOuraDisconnection()
        await relaunched.connect(.oura)
        XCTAssertEqual(direct.disconnectCount, 2)
        XCTAssertEqual(local.authorizeCount, 1)
        XCTAssertTrue(relaunched.connectedSources.contains(.oura))
    }

    @MainActor
    func testFailedDirectCleanupBlocksMethodSwitchWithoutCallingLocalOrDirectDisconnect() async {
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let failure = DisconnectResult(localCredentialsCleared: false, remoteAuthorization: .unconfirmed)
        direct.disconnectResult = failure
        let manager = ConnectorManager(connectors: [local], directOuraConnector: direct)
        await manager.deleteLocalData()
        let localDisconnects = local.disconnectCount
        let directDisconnects = direct.disconnectCount

        await manager.setOuraConnectionMethod(.direct)

        XCTAssertEqual(manager.ouraConnectionMethod, .appleHealth)
        XCTAssertEqual(manager.disconnectResults[.oura], failure)
        XCTAssertEqual(local.disconnectCount, localDisconnects)
        XCTAssertEqual(direct.disconnectCount, directDisconnects)
    }

    @MainActor
    func testMethodSwitchKeepsDirectMethodWhenCurrentAccountCleanupFails() async {
        setOuraMethod(.direct)
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let failure = DisconnectResult(localCredentialsCleared: false, remoteAuthorization: .revoked)
        direct.disconnectResult = failure
        let manager = ConnectorManager(connectors: [local], directOuraConnector: direct)

        await manager.setOuraConnectionMethod(.appleHealth)

        XCTAssertEqual(manager.ouraConnectionMethod, .direct)
        XCTAssertEqual(manager.disconnectResults[.oura], failure)
        XCTAssertEqual(direct.disconnectCount, 1)
        XCTAssertEqual(local.disconnectCount, 0)
        XCTAssertEqual(sharedDefaults.string(forKey: "oura.connectionMethod"), OuraConnectionMethod.direct.rawValue)
    }

    @MainActor
    func testLocalHealthDisconnectionCannotErasePreviousDirectFailure() async {
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let failure = DisconnectResult(localCredentialsCleared: false, remoteAuthorization: .unconfirmed)
        direct.disconnectResult = failure
        let manager = ConnectorManager(connectors: [local], directOuraConnector: direct)
        await manager.deleteLocalData()

        await manager.disconnect(.oura)

        XCTAssertEqual(manager.disconnectResults[.oura], failure)
        XCTAssertEqual(local.disconnectCount, 2)
        let relaunched = ConnectorManager(connectors: [local], directOuraConnector: direct)
        XCTAssertEqual(relaunched.disconnectResults[.oura], failure)
    }

    @MainActor
    func testLocalHealthReconnectAndDisconnectPreserveUnconfirmedDirectRevocation() async {
        let local = MockDashboardConnector(source: .oura)
        let direct = MockDashboardConnector(source: .oura)
        let unconfirmed = DisconnectResult(localCredentialsCleared: true, remoteAuthorization: .unconfirmed)
        direct.disconnectResult = unconfirmed
        let manager = ConnectorManager(connectors: [local], directOuraConnector: direct)
        await manager.deleteLocalData()

        await manager.connect(.oura)
        XCTAssertEqual(local.authorizeCount, 1)
        XCTAssertTrue(manager.connectedSources.contains(.oura))
        XCTAssertEqual(manager.disconnectResults[.oura], unconfirmed)
        await manager.disconnect(.oura)

        XCTAssertEqual(manager.disconnectResults[.oura], unconfirmed)
        let relaunched = ConnectorManager(connectors: [local], directOuraConnector: direct)
        XCTAssertEqual(relaunched.disconnectResults[.oura], unconfirmed)
    }

    private var sharedDefaults: UserDefaults {
        UserDefaults(suiteName: DashboardStore.appGroupID) ?? .standard
    }

    private func clearOuraPreferences() {
        UserDefaults.standard.removeObject(forKey: "oura.disconnectResult")
        sharedDefaults.removeObject(forKey: "oura.connectionMethod")
    }

    private func setOuraMethod(_ method: OuraConnectionMethod) {
        sharedDefaults.set(method.rawValue, forKey: "oura.connectionMethod")
    }

    private func privateCalendarMetric() -> DashboardMetric {
        DashboardMetric(id: "nextEvent", source: .calendar, title: "Up next", value: "Busy",
                        detail: "10:30", symbol: "calendar", tint: "blue", privateValue: "Private medical appointment")
    }
}

@MainActor
private final class MockDashboardConnector: DashboardConnector {
    let source: DataSource
    var authorizeCount = 0
    var refreshCount = 0
    var disconnectCount = 0
    var disconnectResult = DisconnectResult.localOnly
    var customMetric: DashboardMetric?
    var refreshError: Error?
    var holdAuthorization = false
    var holdRefresh = false
    var onAuthorize: (() -> Void)?
    var onRefresh: (() -> Void)?
    private var authorization: CheckedContinuation<Void, Never>?
    private var refreshContinuation: CheckedContinuation<Void, Never>?

    init(source: DataSource) { self.source = source }
    var metric: DashboardMetric {
        customMetric ?? DashboardMetric(id: source == .calendar ? "nextEvent" : "steps", source: source,
                                        title: "Test", value: "42", detail: "Live", symbol: "circle", tint: "blue")
    }
    func authorize() async throws {
        authorizeCount += 1
        if holdAuthorization {
            await withCheckedContinuation { continuation in
                authorization = continuation
                onAuthorize?()
            }
        } else { onAuthorize?() }
    }
    func refresh() async throws -> [DashboardMetric] {
        refreshCount += 1
        let snapshot = metric
        if holdRefresh {
            await withCheckedContinuation { continuation in
                refreshContinuation = continuation
                onRefresh?()
            }
        } else { onRefresh?() }
        if let refreshError { throw refreshError }
        return [snapshot]
    }
    func disconnect() async -> DisconnectResult {
        disconnectCount += 1
        return disconnectResult
    }
    func resumeAuthorization() { authorization?.resume(); authorization = nil }
    func resumeRefresh() { refreshContinuation?.resume(); refreshContinuation = nil }
}
