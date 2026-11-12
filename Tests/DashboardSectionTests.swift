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
        XCTAssertEqual(sections.map(\.category), [.weather, .recovery, .activity, .heart, .calendar])
        XCTAssertEqual(sections[0].metrics.map(\.id), ["temperature", "feelsLike", "uv", "tomorrow"])
    }

    func testUnknownMetricFallsBackToItsSourceAfterKnownTiles() {
        let sections = DashboardSection.sections(for: [metric("pollen", .weather), metric("rain", .weather)])
        XCTAssertEqual(sections.map(\.category), [.weather])
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
}
