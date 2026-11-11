import Foundation
import SwiftUI
import XCTest
@testable import WeatherUV

final class WidgetDesignTests: XCTestCase {
    override func setUp() {
        super.setUp()
        DashboardStore.deleteLocalData()
    }

    override func tearDown() {
        DashboardStore.deleteLocalData()
        super.tearDown()
    }

    func testStarterLayoutsFitTheirGridWithoutOverlapsAndIncludeRefresh() {
        XCTAssertEqual(Set(WidgetDesign.starters.map(\.canvas)), Set(WidgetCanvas.allCases))
        for design in WidgetDesign.starters {
            for tile in design.tiles {
                XCTAssertTrue(design.canPlace(tile), "\(design.canvas) \(tile.metricID) does not fit")
                XCTAssertTrue(tile.isSync || MetricCatalog.descriptor(for: tile.metricID) != nil)
            }
            XCTAssertTrue(design.tiles.contains { $0.isSync }, "\(design.canvas) starter needs a refresh tile")
            XCTAssertEqual(Set(design.tiles.map(\.metricID)).count, design.tiles.count)
        }
    }

    func testPlacementRejectsOverlapsAndTilesOutsideTheGrid() throws {
        var design = WidgetDesign(id: "test", name: "Test", canvas: .medium, tiles: [])
        let steps = try XCTUnwrap(design.addTile(metricID: "steps"))
        XCTAssertEqual([steps.column, steps.row, steps.width, steps.height], [0, 0, 1, 1])
        var wide = WidgetTile(metricID: "uv", column: 0, row: 0, width: 2)
        XCTAssertFalse(design.canPlace(wide))
        wide.column = 1
        XCTAssertTrue(design.canPlace(wide))
        XCTAssertFalse(design.canPlace(WidgetTile(metricID: "uv", column: 3, row: 0, width: 2)))
        XCTAssertFalse(design.canPlace(WidgetTile(metricID: "uv", column: 0, row: 4)))
        XCTAssertFalse(design.canPlace(WidgetTile(metricID: "uv", column: -1, row: 0)))
        XCTAssertFalse(design.canPlace(WidgetTile(metricID: "uv", column: 1, row: 1, width: 0)))
    }

    func testMoveIsRefusedWhenTheTargetIsTaken() throws {
        var design = WidgetDesign(id: "test", name: "Test", canvas: .small, tiles: [])
        let first = try XCTUnwrap(design.addTile(metricID: "steps"))
        let second = try XCTUnwrap(design.addTile(metricID: "uv"))
        XCTAssertEqual(second.column, 1)

        var moved = second
        moved.column = 0
        XCTAssertFalse(design.update(moved))
        moved.row = 2
        XCTAssertTrue(design.update(moved))
        XCTAssertEqual(design.tiles.first { $0.id == second.id }?.row, 2)
        XCTAssertEqual(design.tiles.first { $0.id == first.id }?.row, 0)
    }

    func testLayoutsSavedBeforeAppearancesLoadAsSystemAndKeepAChosenStyle() throws {
        let legacy = Data(#"{"id":"old","name":"Old","canvas":"small","tiles":[]}"#.utf8)
        let loaded = try JSONDecoder().decode(WidgetDesign.self, from: legacy)
        XCTAssertEqual(loaded.appearance, .system)

        let unknown = Data(#"{"id":"new","name":"New","canvas":"small","tiles":[],"appearance":"neon"}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(WidgetDesign.self, from: unknown).appearance, .system)

        var styled = WidgetDesign(id: "styled", name: "Styled", canvas: .medium, tiles: [])
        styled.appearance = .transparent
        let copy = try JSONDecoder().decode(WidgetDesign.self, from: JSONEncoder().encode(styled))
        XCTAssertEqual(copy, styled)
        XCTAssertEqual(copy.appearance, .transparent)
    }

    func testGradientAppearancesKeepTextAndTintsReadable() {
        let gradients = WidgetAppearance.allCases.filter(\.isGradient)
        XCTAssertFalse(gradients.isEmpty)
        for appearance in gradients {
            for stop in appearance.gradientStops {
                XCTAssertGreaterThanOrEqual(WidgetRGB.white.contrast(with: stop), 7, "primary text on \(appearance)")
                let secondary = WidgetRGB.white.blended(over: stop, opacity: WidgetAppearance.gradientSecondaryOpacity)
                XCTAssertGreaterThanOrEqual(secondary.contrast(with: stop), 4.5, "secondary text on \(appearance)")
                for name in TileTint.names {
                    XCTAssertGreaterThanOrEqual(TileTint.brightComponents(name).contrast(with: stop), 4.5, "\(name) on \(appearance)")
                }
            }
        }
    }

    func testNudgeJumpsOverTilesAndStopsAtTheEdge() throws {
        let design = WidgetDesign(id: "test", name: "Test", canvas: .medium, tiles: [
            WidgetTile(metricID: "steps", column: 0, row: 0),
            WidgetTile(metricID: "uv", column: 1, row: 0)
        ])
        let steps = design.tiles[0]
        let right = try XCTUnwrap(design.placement(of: steps, stepping: 1, 0))
        XCTAssertEqual([right.column, right.row], [2, 0], "skips the tile at (1, 0)")
        XCTAssertEqual(design.placement(of: steps, stepping: 0, 1)?.row, 1)
        XCTAssertNil(design.placement(of: steps, stepping: -1, 0), "already at the left edge")
        XCTAssertNil(design.placement(of: steps, stepping: 0, 0))
    }

    func testEveryAppearanceHasAName() {
        let names = WidgetAppearance.allCases.map(\.displayName)
        XCTAssertEqual(Set(names).count, names.count)
        XCTAssertTrue(names.allSatisfy { !$0.isEmpty })
        XCTAssertNil(WidgetAppearance.system.colorScheme)
        XCTAssertEqual(WidgetAppearance.light.colorScheme, .light)
        XCTAssertEqual(WidgetAppearance.dark.colorScheme, .dark)
    }

    func testNearestPlacementSnapsPastTakenCellsAndGivesUpWhenFarAway() throws {
        let design = WidgetDesign(id: "test", name: "Test", canvas: .medium, tiles: [
            WidgetTile(metricID: "steps", column: 1, row: 0),
            WidgetTile(metricID: "uv", column: 3, row: 0)
        ])
        let moving = WidgetTile(metricID: "sleep", column: 0, row: 1)
        let pitch = CGSize(width: 70, height: 30)

        // Dropped on the taken cell (1, 0): the closest free cell below it wins.
        let snapped = try XCTUnwrap(design.nearestPlacement(of: moving, column: 1, row: 0, pitch: pitch, maxDistance: 60))
        XCTAssertEqual([snapped.column, snapped.row], [1, 1])

        // A cell that is already free stays where it was dropped.
        let exact = try XCTUnwrap(design.nearestPlacement(of: moving, column: 2, row: 0, pitch: pitch, maxDistance: 60))
        XCTAssertEqual([exact.column, exact.row], [2, 0])

        XCTAssertNil(design.nearestPlacement(of: moving, column: 20, row: 20, pitch: pitch, maxDistance: 60))
    }

    func testPlacementsListOnlyFreeSpotsAndIgnoreTheTilesOwnCells() {
        let design = WidgetDesign(id: "test", name: "Test", canvas: .small, tiles: [
            WidgetTile(metricID: "steps", column: 0, row: 0, width: 2, height: 2),
            WidgetTile(metricID: "uv", column: 0, row: 2)
        ])
        let wide = design.tiles[0]
        let spots = design.placements(for: wide).map { [$0.column, $0.row] }
        XCTAssertTrue(spots.contains([0, 0]), "its own position stays valid")
        XCTAssertFalse(spots.contains([0, 1]), "would cover the tile at (0, 2)")
        XCTAssertTrue(design.placements(for: WidgetTile(metricID: "x", column: 0, row: 0, width: 3)).isEmpty)
    }

    func testResizeGrowsAsFarAsFreeSpaceAllowsAndNeverBelowOneCell() {
        var design = WidgetDesign(id: "test", name: "Test", canvas: .medium, tiles: [
            WidgetTile(metricID: "steps", column: 0, row: 0),
            WidgetTile(metricID: "uv", column: 3, row: 0)
        ])
        let steps = design.tiles[0]
        let grown = design.resizeTile(id: steps.id, width: 4, height: 2)
        XCTAssertEqual(grown?.width, 3)
        XCTAssertEqual(grown?.height, 2)

        let shrunk = design.resizeTile(id: steps.id, width: 0, height: -3)
        XCTAssertEqual(shrunk?.width, 1)
        XCTAssertEqual(shrunk?.height, 1)
    }

    func testAddingFailsWhenTheGridIsFullAndEventsPreferWideTiles() throws {
        var medium = WidgetDesign(id: "medium", name: "Medium", canvas: .medium, tiles: [])
        let event = try XCTUnwrap(medium.addTile(metricID: "nextEvent"))
        XCTAssertEqual(event.width, 3)

        var small = WidgetDesign(id: "small", name: "Small", canvas: .small, tiles: [])
        for _ in 0..<(WidgetCanvas.small.columns * WidgetCanvas.small.rows) {
            XCTAssertNotNil(small.addTile(metricID: "steps"))
        }
        XCTAssertNil(small.addTile(metricID: "uv"))
    }

    func testLayoutChosenForAnotherSizeShowsOnlyTilesThatFit() {
        let large = WidgetDesign.starter(for: .large)
        let visible = large.visibleTiles(on: .medium)
        XCTAssertFalse(visible.isEmpty)
        XCTAssertLessThan(visible.count, large.tiles.count)
        XCTAssertTrue(visible.allSatisfy {
            $0.endRow <= WidgetCanvas.medium.rows && $0.endColumn <= WidgetCanvas.medium.columns
        })
    }

    func testStoreKeepsStartersOnFirstSaveAndFallsBackAfterDeletion() {
        XCTAssertEqual(WidgetDesignStore.designs, WidgetDesign.starters)

        var custom = WidgetDesignStore.newDesign(for: .medium)
        custom.name = "Morning"
        WidgetDesignStore.save(custom)
        XCTAssertEqual(WidgetDesignStore.designs(for: .small).count, 1, "Saving one layout keeps the other starters")
        XCTAssertEqual(WidgetDesignStore.designs(for: .medium).count, 2)
        XCTAssertEqual(WidgetDesignStore.resolvedDesign(id: custom.id, for: .medium), custom)

        WidgetDesignStore.delete(id: custom.id)
        XCTAssertEqual(WidgetDesignStore.resolvedDesign(id: custom.id, for: .medium).id, WidgetDesign.starter(for: .medium).id)
        for design in WidgetDesignStore.designs(for: .medium) {
            WidgetDesignStore.delete(id: design.id)
        }
        XCTAssertTrue(WidgetDesignStore.designs(for: .medium).isEmpty)
        XCTAssertEqual(WidgetDesignStore.resolvedDesign(id: nil, for: .medium), WidgetDesign.starter(for: .medium))
    }

    func testNewLayoutsGetTheirOwnIdentities() {
        let starter = WidgetDesign.starter(for: .large)
        let copy = WidgetDesignStore.newDesign(for: .large)
        XCTAssertNotEqual(copy.id, starter.id)
        XCTAssertTrue(Set(copy.tiles.map(\.id)).isDisjoint(with: Set(starter.tiles.map(\.id))))
    }
}

final class PresentationTests: XCTestCase {
    func testUpdatedLabelUsesWholeMinutesThenHoursAndDays() {
        let fetched = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(59)), "0m")
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(60)), "1m")
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(59 * 60 + 59)), "59m")
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(3 * 3600)), "3h")
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(49 * 3600)), "2d")
        XCTAssertEqual(UpdatedLabel.short(since: fetched, now: fetched.addingTimeInterval(-30)), "0m")
        XCTAssertEqual(UpdatedLabel.long(since: fetched, now: fetched.addingTimeInterval(120)), "Updated 2 min ago")
    }

    func testEventTimingSaysWhenTheEventHappens() throws {
        let now = try XCTUnwrap(Calendar.current.date(bySettingHour: 12, minute: 0, second: 0, of: Date()))
        let today = now.addingTimeInterval(2 * 3600)
        XCTAssertEqual(EventTiming.startText(today, now: now), today.formatted(date: .omitted, time: .shortened))
        XCTAssertTrue(EventTiming.startText(now.addingTimeInterval(24 * 3600), now: now).hasPrefix("Tomorrow "))
        let later = now.addingTimeInterval(3 * 24 * 3600)
        XCTAssertTrue(EventTiming.startText(later, now: now).hasPrefix(later.formatted(.dateTime.weekday(.abbreviated))))

        XCTAssertEqual(EventTiming.relativeText(start: now.addingTimeInterval(25 * 60), end: nil, now: now), "in 25m")
        XCTAssertEqual(EventTiming.relativeText(start: now.addingTimeInterval(24 * 60 + 1), end: nil, now: now), "in 25m")
        XCTAssertEqual(EventTiming.relativeText(start: now.addingTimeInterval(2 * 3600), end: nil, now: now), "in 2h")
        XCTAssertEqual(EventTiming.relativeText(start: now.addingTimeInterval(3 * 3600 + 5 * 60), end: nil, now: now), "in 3h 5m")

        let end = now.addingTimeInterval(30 * 60)
        XCTAssertEqual(
            EventTiming.relativeText(start: now.addingTimeInterval(-60), end: end, now: now),
            "until \(end.formatted(date: .omitted, time: .shortened))"
        )
        XCTAssertEqual(EventTiming.compactText(start: now.addingTimeInterval(-60), end: end, now: now), "Now")
        XCTAssertEqual(EventTiming.compactText(start: now.addingTimeInterval(10 * 60), end: nil, now: now), "in 10m")
        XCTAssertEqual(EventTiming.compactText(start: today, end: nil, now: now), EventTiming.startText(today, now: now))
    }

    func testUVLevelsFollowWHOCategoriesForTheRoundedValue() {
        XCTAssertEqual(UVLevel(index: 0).label, "Low")
        XCTAssertEqual(UVLevel(index: 2.4).label, "Low")
        XCTAssertEqual(UVLevel(index: 2.5).label, "Moderate")
        XCTAssertEqual(UVLevel(index: 5.4).label, "Moderate")
        XCTAssertEqual(UVLevel(index: 6).label, "High")
        XCTAssertEqual(UVLevel(index: 8).label, "Very high")
        XCTAssertEqual(UVLevel(index: 11).label, "Extreme")
    }

    func testPrecipitationUsesMillimetersOrInchesByRegion() {
        XCTAssertTrue(WeatherFormat.precipitation(2.4, locale: Locale(identifier: "fi_FI")).hasSuffix(" mm"))
        XCTAssertTrue(WeatherFormat.precipitation(25.4, locale: Locale(identifier: "en_US")).hasSuffix(" in"))
    }
}

final class WeatherSnapshotTests: XCTestCase {
    private let today = 1_799_971_200.0

    func testForecastBuildsTemperatureUVRainAndTomorrow() throws {
        let tomorrow = today + 86_400
        let hours = (0..<48).map { String(Int(today) + $0 * 3600) }
        // Today's UV peaks at 13:00; tomorrow alternates 8° and 12°.
        let uv = (0..<48).map { $0 == 13 ? "6.2" : ($0 >= 8 && $0 <= 17 ? "3" : "0") }
        let temperature = (0..<48).map { $0 < 24 ? "14" : ($0 % 2 == 0 ? "8" : "12") }
        let rainChance = (0..<48).map { $0 == 15 ? "70" : "10" }
        let json = """
        {"current":{"time":\(Int(today) + 10 * 3600),"temperature_2m":14.4,"weather_code":2,"uv_index":2.6,"is_day":1},
         "hourly":{"time":[\(hours.joined(separator: ","))],
                   "temperature_2m":[\(temperature.joined(separator: ","))],
                   "uv_index":[\(uv.joined(separator: ","))],
                   "precipitation_probability":[\(rainChance.joined(separator: ","))]},
         "daily":{"time":[\(Int(today)),\(Int(tomorrow))],"weather_code":[2,61],
                  "temperature_2m_max":[17.2,13],"temperature_2m_min":[8.6,7],
                  "precipitation_sum":[2.4,3.1],"precipitation_probability_max":[70,80],"uv_index_max":[6.2,4]}}
        """
        let forecast = try JSONDecoder().decode(OpenMeteoForecast.self, from: Data(json.utf8))
        let now = Date(timeIntervalSince1970: today + 10 * 3600 + 600)
        let metrics = Dictionary(uniqueKeysWithValues: WeatherSnapshotBuilder.metrics(from: forecast, now: now).map { ($0.id, $0) })

        XCTAssertEqual(Set(metrics.keys), ["temperature", "uv", "rain", "tomorrow"])
        XCTAssertTrue(metrics.values.allSatisfy { $0.source == .weather })
        XCTAssertTrue(metrics.values.allSatisfy { $0.expiresAt == Date(timeIntervalSince1970: tomorrow) },
                      "Today's forecast expires at the location's midnight")

        let current = try XCTUnwrap(metrics["temperature"])
        XCTAssertEqual(current.value, WeatherFormat.temperature(14.4))
        XCTAssertEqual(current.symbol, "cloud.sun.fill")
        XCTAssertTrue(current.detail.hasPrefix("Partly cloudy"))

        let uvMetric = try XCTUnwrap(metrics["uv"])
        XCTAssertEqual(uvMetric.value, "3")
        XCTAssertEqual(uvMetric.tint, "yellow")
        XCTAssertEqual(uvMetric.targetValue, 11)
        let peak = Date(timeIntervalSince1970: today + 13 * 3600).formatted(date: .omitted, time: .shortened)
        XCTAssertEqual(uvMetric.detail, "Moderate · peak 6 at \(peak)")

        let rain = try XCTUnwrap(metrics["rain"])
        XCTAssertEqual(rain.numericValue, 2.4)
        let likely = Date(timeIntervalSince1970: today + 15 * 3600).formatted(date: .omitted, time: .shortened)
        XCTAssertEqual(rain.detail, "70% · most likely \(likely)")

        let next = try XCTUnwrap(metrics["tomorrow"])
        XCTAssertEqual(next.numericValue ?? 0, 10, accuracy: 0.001, "Tomorrow averages its hourly temperatures")
        XCTAssertEqual(next.symbol, "cloud.rain.fill")
        XCTAssertTrue(next.detail.hasPrefix("Rain"))
    }

    func testNightUVPointsToTomorrowAndDryDaysSaySo() throws {
        let json = """
        {"current":{"time":\(Int(today) + 22 * 3600),"temperature_2m":-0.3,"weather_code":0,"uv_index":0,"is_day":0},
         "daily":{"time":[\(Int(today)),\(Int(today) + 86_400)],"weather_code":[0,1],
                  "temperature_2m_max":[3,4],"temperature_2m_min":[-2,-1],
                  "precipitation_sum":[0,0],"precipitation_probability_max":[5,0],"uv_index_max":[2,3.4]}}
        """
        let forecast = try JSONDecoder().decode(OpenMeteoForecast.self, from: Data(json.utf8))
        let now = Date(timeIntervalSince1970: today + 22 * 3600)
        let metrics = Dictionary(uniqueKeysWithValues: WeatherSnapshotBuilder.metrics(from: forecast, now: now).map { ($0.id, $0) })

        XCTAssertEqual(metrics["uv"]?.detail, "Low · tomorrow 3")
        XCTAssertEqual(metrics["rain"]?.detail, "Dry today")
        XCTAssertEqual(metrics["temperature"]?.symbol, "moon.stars.fill")
        XCTAssertFalse(metrics["temperature"]?.value.contains("-") ?? true, "Values that round to zero must not show -0°")
        XCTAssertEqual(metrics["tomorrow"]?.numericValue, 1.5, "Without hourly data, tomorrow averages its high and low")
    }

    func testLocationsAreRoundedBeforeTheyAreStoredOrSent() {
        let location = WeatherLocation(name: "Here", latitude: 60.169_52, longitude: 24.935_45, isCurrentLocation: true)
        XCTAssertEqual(location.latitude, 60.17, accuracy: 0.000_001)
        XCTAssertEqual(location.longitude, 24.94, accuracy: 0.000_001)
    }
}

final class MetricExpiryTests: XCTestCase {
    override func setUp() {
        super.setUp()
        DashboardStore.deleteLocalData()
    }

    override func tearDown() {
        DashboardStore.deleteLocalData()
        super.tearDown()
    }

    func testMetricIsExpiredFromItsExpiryInstant() {
        let expiry = Date(timeIntervalSince1970: 5000)
        let steps = metric("steps", expiresAt: expiry)
        XCTAssertFalse(steps.isExpired(at: expiry.addingTimeInterval(-1)))
        XCTAssertTrue(steps.isExpired(at: expiry))
        XCTAssertFalse(metric("heart", expiresAt: nil).isExpired(at: .distantFuture))
    }

    func testEventExpiresAtItsEndInEveryPrivacyMode() {
        let end = Date(timeIntervalSince1970: 4600)
        let event = CalendarEventSummary(calendarID: "c", title: "Sync", startsAt: Date(timeIntervalSince1970: 1000), endsAt: end, isAllDay: false)
        let snapshot = CalendarSnapshotBuilder.metric(for: event, showEventTitles: true)
        XCTAssertEqual(snapshot.expiresAt, end)
        for mode in [WidgetPrivacyMode.discreet, .showValues] {
            XCTAssertEqual(WidgetPrivacy.render([snapshot], mode: mode).first?.expiresAt, end)
        }
        XCTAssertNil(CalendarSnapshotBuilder.metric(for: nil, showEventTitles: false).expiresAt)
        XCTAssertEqual(WidgetPrivacy.render([metric("steps", expiresAt: end)], mode: .discreet).first?.expiresAt, end)
    }

    func testStoreDropsExpiredMetricsOnRead() {
        DashboardStore.replaceMetrics([
            metric("steps", expiresAt: Date.now.addingTimeInterval(-60)),
            metric("heart", expiresAt: Date.now.addingTimeInterval(3600))
        ], for: .appleHealth)
        XCTAssertEqual(DashboardStore.metrics.map(\.id), ["heart"])
    }

    private func metric(_ id: String, expiresAt: Date?) -> DashboardMetric {
        DashboardMetric(id: id, source: .appleHealth, title: id, value: "1", detail: "Today",
                        symbol: "circle", tint: "mint", expiresAt: expiresAt)
    }
}

final class WidgetRefreshTests: XCTestCase {
    override func setUp() {
        super.setUp()
        DashboardStore.deleteLocalData()
    }

    override func tearDown() {
        DashboardStore.deleteLocalData()
        super.tearDown()
    }

    @MainActor
    func testPassiveRefreshSkipsFreshSourcesAndStaysSilentOnFailure() async {
        let now = Date.now
        DashboardStore.connectedSources = [.weather, .appleHealth]
        DashboardStore.replaceMetrics([sample("uv", .weather)], for: .weather, fetchedAt: now.addingTimeInterval(-60))
        DashboardStore.replaceMetrics([sample("steps", .appleHealth)], for: .appleHealth, fetchedAt: now.addingTimeInterval(-3600))
        let weather = StubConnector(source: .weather, result: .success([sample("uv", .weather)]))
        let health = StubConnector(source: .appleHealth, result: .failure(ConnectorError.unavailable("Locked")))

        await WidgetDataRefresher.refresh(userInitiated: false, now: now) { $0 == .weather ? weather : health }

        XCTAssertEqual(weather.refreshCount, 0)
        XCTAssertEqual(health.refreshCount, 1)
        XCTAssertNil(DashboardStore.error(for: .appleHealth))
        XCTAssertEqual(DashboardStore.updatedAt(for: .appleHealth), now.addingTimeInterval(-3600))
    }

    @MainActor
    func testSyncButtonRefreshesEverySourceAndReportsFailures() async {
        DashboardStore.connectedSources = [.weather, .appleHealth]
        DashboardStore.replaceMetrics([sample("uv", .weather)], for: .weather)
        let weather = StubConnector(source: .weather, result: .success([sample("uv", .weather, value: "7")]))
        let health = StubConnector(source: .appleHealth, result: .failure(ConnectorError.unavailable("Health unavailable")))

        await WidgetDataRefresher.refresh(userInitiated: true, now: .now) { $0 == .weather ? weather : health }

        XCTAssertEqual(weather.refreshCount, 1)
        XCTAssertEqual(DashboardStore.metrics.first { $0.id == "uv" }?.value, "7")
        XCTAssertEqual(DashboardStore.error(for: .appleHealth), "Health unavailable")
    }

    @MainActor
    func testSourceDisconnectedDuringRefreshIsNotPublished() async {
        DashboardStore.connectedSources = [.appleHealth]
        let health = StubConnector(source: .appleHealth, result: .success([sample("steps", .appleHealth)]))
        health.onRefresh = { DashboardStore.connectedSources = [] }

        await WidgetDataRefresher.refresh(userInitiated: true, now: .now) { _ in health }

        XCTAssertTrue(DashboardStore.metrics.isEmpty)
        XCTAssertNil(DashboardStore.updatedAt(for: .appleHealth))
    }

    @MainActor
    func testCalendarPrivacyChangeDuringRefreshDiscardsTheFetchedEvent() async {
        DashboardStore.connectedSources = [.calendar]
        let calendar = StubConnector(source: .calendar, result: .success([sample("nextEvent", .calendar)]))
        calendar.onRefresh = { CalendarPreferences.showEventTitles = true }

        await WidgetDataRefresher.refresh(userInitiated: true, now: .now) { _ in calendar }

        XCTAssertEqual(calendar.refreshCount, 1)
        XCTAssertFalse(DashboardStore.metrics.contains { $0.source == .calendar })
        XCTAssertNil(DashboardStore.updatedAt(for: .calendar))
    }

    private func sample(_ id: String, _ source: DataSource, value: String = "1") -> DashboardMetric {
        DashboardMetric(id: id, source: source, title: id, value: value, detail: "Test", symbol: "circle", tint: "blue")
    }
}

@MainActor
private final class StubConnector: DashboardConnector {
    let source: DataSource
    let result: Result<[DashboardMetric], Error>
    var refreshCount = 0
    var onRefresh: (() -> Void)?

    init(source: DataSource, result: Result<[DashboardMetric], Error>) {
        self.source = source
        self.result = result
    }

    func authorize() async throws {}

    func refresh() async throws -> [DashboardMetric] {
        refreshCount += 1
        onRefresh?()
        return try result.get()
    }

    func disconnect() async -> DisconnectResult { .localOnly }
}
