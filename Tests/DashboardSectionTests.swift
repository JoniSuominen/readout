import XCTest
@testable import Readout

final class DashboardSectionTests: XCTestCase {
    private func metric(_ id: String, _ source: DataSource, origin: String? = nil) -> DashboardMetric {
        DashboardMetric(id: id, source: source, title: id, value: "1", detail: "", symbol: "circle", tint: "blue", origin: origin)
    }

    func testEveryCatalogMetricBelongsToExactlyOneCategory() {
        let assigned = DashboardCategory.allCases.flatMap(\.metricIDs)
        XCTAssertEqual(assigned.count, Set(assigned).count, "A metric is listed in two categories")
        XCTAssertEqual(Set(assigned), Set(MetricCatalog.all.map(\.id)))
    }

    func testSectionsGroupByTypeAndOrderRelatedTilesTogether() {
        let sections = DashboardSection.sections(for: [
            metric("temperature", .weather), metric("uv", .weather), metric("tomorrow", .weather),
            metric("feelsLike", .weather), metric("sleep", .oura), metric("steps", .appleHealth),
            metric("hrv", .appleHealth), metric("nextEvent", .calendar)
        ])
        XCTAssertEqual(sections.map(\.id), ["weather", "recovery", "activity", "heart", "calendar"])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["temperature", "feelsLike", "uv", "tomorrow"])
    }

    func testUnknownMetricFallsBackToItsSourceAfterKnownTiles() {
        let sections = DashboardSection.sections(for: [metric("pollen", .weather), metric("rain", .weather)])
        XCTAssertEqual(sections.map(\.id), ["weather"])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["rain", "pollen"])
    }

    func testOriginIsNamedOnceUnlessTheSectionMixesOrigins() {
        let weather = DashboardSection.sections(for: [metric("temperature", .weather), metric("uv", .weather)])[0]
        XCTAssertEqual(weather.headerOrigin, "Open-Meteo")
        XCTAssertFalse(weather.showsOriginOnTiles)

        let heart = DashboardSection.sections(for: [
            metric("heart", .appleHealth, origin: "Watch"), metric("hrv", .appleHealth, origin: "Oura")
        ])[0]
        XCTAssertNil(heart.headerOrigin)
        XCTAssertTrue(heart.showsOriginOnTiles)

        // "Calendar" under a Calendar heading would only repeat the title.
        let calendar = DashboardSection.sections(for: [metric("nextEvent", .calendar)])[0]
        XCTAssertNil(calendar.headerOrigin)
        XCTAssertFalse(calendar.showsOriginOnTiles)
        XCTAssertEqual(calendar.sources, [.calendar])
    }
    // MARK: Today layout

    func testStandardLayoutHidesOnlyDuplicateOuraActivity() {
        let layout = TodayLayout.standard
        XCTAssertFalse(layout.isShown("ouraSteps"))
        XCTAssertFalse(layout.isShown("ouraActiveCalories"))
        XCTAssertTrue(layout.isShown("uv"))
        XCTAssertTrue(layout.isShown("ouraSpO2"))
        let sections = DashboardSection.sections(for: [metric("steps", .appleHealth), metric("ouraSteps", .oura)])
        XCTAssertEqual(sections.flatMap(\.metrics).map(\.id), ["steps"])
    }

    func testHiddenTilesAndEmptyGroupsAreLeftOut() {
        var layout = TodayLayout.standard
        layout.setShown("uv", false)
        layout.setShown("ouraSteps", true)
        let sections = DashboardSection.sections(for: [
            metric("uv", .weather), metric("rain", .weather), metric("ouraSteps", .oura)
        ], layout: layout)
        XCTAssertEqual(sections.map(\.id), ["weather", "activity"])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["rain"])
        // Choosing a catalog default again stores nothing.
        layout.setShown("uv", true)
        XCTAssertNil(layout.visibility["uv"])
    }

    func testGroupsAndTilesFollowTheLayoutOrder() {
        var layout = TodayLayout.standard
        let weather = layout.groups.firstIndex { $0.id == "weather" }!
        layout.moveGroups(from: IndexSet(integer: weather), to: layout.groups.count)
        layout.moveMetric("uv", toGroup: "recovery")
        let rainIndex = layout.groups.first { $0.id == "weather" }!.metricIDs.firstIndex(of: "rain")!
        layout.moveMetrics(inGroup: "weather", from: IndexSet(integer: rainIndex), to: 0)
        let sections = DashboardSection.sections(for: [
            metric("temperature", .weather), metric("rain", .weather), metric("uv", .weather), metric("sleep", .oura)
        ], layout: layout)
        XCTAssertEqual(sections.map(\.id), ["recovery", "weather"])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["sleep", "uv"])
        XCTAssertEqual(sections[1].metrics.map(\.id), ["rain", "temperature"])
    }

    func testCustomGroupsCanBeRenamedAndDeletedWithoutLosingTiles() {
        var layout = TodayLayout.standard
        let mine = layout.addGroup(title: "  Morning  ")
        XCTAssertEqual(mine.title, "Morning")
        layout.moveMetric("uv", toGroup: mine.id)
        layout.moveMetric("readiness", toGroup: mine.id)
        layout.renameGroup(mine.id, to: "Before work")
        let sections = DashboardSection.sections(for: [metric("uv", .weather), metric("readiness", .oura)], layout: layout)
        XCTAssertEqual(sections.map(\.title), ["Before work"])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["uv", "readiness"])

        layout.deleteGroup(mine.id)
        XCTAssertNil(layout.groups.first { $0.id == mine.id })
        XCTAssertEqual(layout.group(containing: "uv")?.id, "weather")
        XCTAssertEqual(layout.group(containing: "readiness")?.id, "recovery")
    }

    func testNormalizeAddsNewMetricsOnceAndKeepsAtLeastOneGroup() {
        var layout = TodayLayout(groups: [TodayGroup(id: "mine", title: "Mine", symbol: "star", metricIDs: ["uv", "uv"])],
                                 visibility: [:])
        layout = layout.normalized()
        let all = layout.groups.flatMap(\.metricIDs)
        XCTAssertEqual(all.count, Set(all).count)
        XCTAssertEqual(Set(all), Set(MetricCatalog.all.map(\.id)))
        layout.deleteGroup("mine")
        XCTAssertEqual(layout.groups.count, 1, "The last group stays")
    }

    func testLayoutRoundTripsThroughJSON() throws {
        var layout = TodayLayout.standard
        layout.setShown("humidity", false)
        _ = layout.addGroup(title: "Extra")
        let decoded = try JSONDecoder().decode(TodayLayout.self, from: JSONEncoder().encode(layout))
        XCTAssertEqual(decoded, layout)
    }
}
