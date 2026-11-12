import Foundation
import XCTest
@testable import Readout

final class TileLinkTests: XCTestCase {
    func testLinksNameOnlyKnownMetricsAndNothingElse() throws {
        let url = try XCTUnwrap(TileLink.url(for: "steps"))
        XCTAssertEqual(url.absoluteString, "com.jonisuominen.readout://open/steps")
        XCTAssertEqual(TileLink.metricID(from: url), "steps")
        XCTAssertNil(TileLink.url(for: "unknown"))
        XCTAssertNil(TileLink.url(for: WidgetTile.syncID), "The refresh tile is a button, not a link")

        // The Oura sign-in callback shares the scheme and must never be read as a tile.
        XCTAssertNil(TileLink.metricID(from: try XCTUnwrap(URL(string: "com.jonisuominen.readout://oauth/oura?code=1"))))
        XCTAssertNil(TileLink.metricID(from: try XCTUnwrap(URL(string: "com.jonisuominen.readout://open/unknown"))))
        XCTAssertNil(TileLink.metricID(from: try XCTUnwrap(URL(string: "com.jonisuominen.readout://open/steps/extra"))))
        XCTAssertNil(TileLink.metricID(from: try XCTUnwrap(URL(string: "https://open/steps"))))
    }

    func testCalendarAndHealthTilesLeaveForTheirOwnApp() {
        let start = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let event = DashboardMetric(id: "nextEvent", source: .calendar, title: "Up next", value: "Busy", detail: "",
                                    symbol: "calendar", tint: "blue", observedAt: start)
        XCTAssertEqual(
            TileLink.externalURL(for: "nextEvent", metrics: [event], ouraMethod: .appleHealth)?.absoluteString,
            "calshow:800000000"
        )
        XCTAssertEqual(TileLink.externalURL(for: "steps", metrics: [], ouraMethod: .direct)?.scheme, "x-apple-health")
        XCTAssertEqual(TileLink.externalURL(for: "sleep", metrics: [], ouraMethod: .appleHealth)?.scheme, "x-apple-health")
        XCTAssertNil(TileLink.externalURL(for: "sleep", metrics: [], ouraMethod: .direct), "Oura account data stays in Readout")
        XCTAssertNil(TileLink.externalURL(for: "uv", metrics: [], ouraMethod: .direct))
        XCTAssertNil(TileLink.externalURL(for: "unknown", metrics: [], ouraMethod: .direct))
    }
}

final class AgendaTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 10_000)

    private func event(_ title: String, calendar: String = "personal", startsIn: TimeInterval,
                       lasts: TimeInterval = 1_800, isAllDay: Bool = false) -> CalendarEventSummary {
        CalendarEventSummary(calendarID: calendar, title: title, startsAt: now.addingTimeInterval(startsIn),
                             endsAt: now.addingTimeInterval(startsIn + lasts), isAllDay: isAllDay)
    }

    func testAgendaListsTodaysAllDayEventsFirstThenTimedEventsFromSelectedCalendars() {
        let events = [
            event("Later", startsIn: 7_200),
            event("Private work meeting", calendar: "work", startsIn: 60),
            event("Holiday", startsIn: -3_600, lasts: 86_399, isAllDay: true),
            event("Ended", startsIn: -7_200),
            event("Sooner", startsIn: 600),
            event("Next week all day", startsIn: 3 * 86_400, lasts: 86_399, isAllDay: true),
            event("Distant", startsIn: 9 * 86_400)
        ]
        let items = CalendarSnapshotBuilder.agenda(in: events, selectedCalendarIDs: ["personal"], now: now, showEventTitles: true)
        XCTAssertEqual(items.map(\.title), ["Holiday", "Sooner", "Later"])
        XCTAssertEqual(items.map(\.isAllDay), [true, false, false])

        let hidden = CalendarSnapshotBuilder.agenda(in: events, selectedCalendarIDs: ["personal"], now: now, showEventTitles: false)
        XCTAssertEqual(hidden.count, 3)
        XCTAssertTrue(hidden.allSatisfy { $0.title == nil }, "Titles need the explicit opt-in")
        XCTAssertTrue(CalendarSnapshotBuilder.agenda(in: events, selectedCalendarIDs: [], now: now, showEventTitles: true).isEmpty)
    }

    func testAgendaIsCappedAndExpiresWhenItsFirstEventEnds() {
        let events = (0..<9).map { event("Event \($0)", startsIn: TimeInterval(600 * ($0 + 1))) }
        let items = CalendarSnapshotBuilder.agenda(in: events, selectedCalendarIDs: ["personal"], now: now, showEventTitles: true)
        XCTAssertEqual(items.count, CalendarSnapshotBuilder.agendaLimit)

        let metric = CalendarSnapshotBuilder.agendaMetric(for: items)
        XCTAssertEqual(metric.id, "agenda")
        XCTAssertEqual(metric.source, .calendar)
        XCTAssertEqual(metric.expiresAt, now.addingTimeInterval(600 + 1_800))
        XCTAssertEqual(CalendarSnapshotBuilder.agendaMetric(for: []).value, "No upcoming events")
    }

    func testDiscreetWidgetsKeepAgendaTimesButNeverTitles() throws {
        let secret = "Confidential medical appointment"
        let items = CalendarSnapshotBuilder.agenda(in: [event(secret, startsIn: 600)], selectedCalendarIDs: ["personal"],
                                                   now: now, showEventTitles: true)
        let metric = CalendarSnapshotBuilder.agendaMetric(for: items)

        let discreet = try XCTUnwrap(WidgetPrivacy.render([metric], mode: .discreet).first)
        XCTAssertEqual(discreet.items?.count, 1)
        XCTAssertEqual(discreet.items?.first?.startsAt, items.first?.startsAt)
        let encoded = String(decoding: try JSONEncoder().encode(discreet), as: UTF8.self)
        XCTAssertFalse(encoded.contains(secret))

        let shown = try XCTUnwrap(WidgetPrivacy.render([metric], mode: .showValues).first)
        XCTAssertEqual(shown.items?.first?.title, secret)
    }

    func testAgendaTilesStartWideAndTall() throws {
        var medium = WidgetDesign(id: "medium", name: "Medium", canvas: .medium, tiles: [])
        let agenda = try XCTUnwrap(medium.addTile(metricID: "agenda"))
        XCTAssertEqual(agenda.width, 3)
        XCTAssertEqual(agenda.height, 3)
    }
}

final class WeatherExtrasTests: XCTestCase {
    private let today = 1_799_971_200.0

    override func setUp() {
        super.setUp()
        UnitPreferences.reset()
    }

    func testCurrentConditionsAddFeelsLikeWindHumiditySunAndAirQuality() throws {
        let tomorrow = today + 86_400
        let json = """
        {"current":{"time":\(Int(today) + 10 * 3600),"temperature_2m":14.4,"apparent_temperature":12.2,
                    "relative_humidity_2m":61.6,"weather_code":2,"uv_index":2.6,"is_day":1,
                    "wind_speed_10m":5.4,"wind_direction_10m":315,"wind_gusts_10m":9.2},
         "daily":{"time":[\(Int(today)),\(Int(tomorrow))],"weather_code":[2,61],
                  "temperature_2m_max":[17.2,13],"temperature_2m_min":[8.6,7],
                  "precipitation_sum":[0,0],"precipitation_probability_max":[0,0],"uv_index_max":[6.2,4],
                  "sunrise":[\(Int(today) + 6 * 3600),\(Int(tomorrow) + 6 * 3600)],
                  "sunset":[\(Int(today) + 20 * 3600),\(Int(tomorrow) + 20 * 3600)]}}
        """
        let forecast = try JSONDecoder().decode(OpenMeteoForecast.self, from: Data(json.utf8))
        let air = try JSONDecoder().decode(
            OpenMeteoAirQuality.self,
            from: Data(#"{"current":{"european_aqi":18,"us_aqi":40,"pm2_5":4.2}}"#.utf8)
        )
        let now = Date(timeIntervalSince1970: today + 10 * 3600 + 600)
        func clock(_ offset: Double) -> String {
            Date(timeIntervalSince1970: today + offset).formatted(date: .omitted, time: .shortened)
        }

        let finnish = Dictionary(uniqueKeysWithValues: WeatherSnapshotBuilder.metrics(
            from: forecast, airQuality: air, now: now, locale: Locale(identifier: "fi_FI")
        ).map { ($0.id, $0) })
        XCTAssertEqual(finnish["feelsLike"]?.numericValue, 12.2)
        XCTAssertEqual(finnish["wind"]?.value, "5 m/s")
        XCTAssertEqual(finnish["wind"]?.detail, "from NW · gusts 9 m/s")
        XCTAssertEqual(finnish["humidity"]?.value, "62%")
        XCTAssertEqual(finnish["sun"]?.title, "Sunset")
        XCTAssertEqual(finnish["sun"]?.value, clock(20 * 3600))
        XCTAssertEqual(finnish["sun"]?.detail, "Sunrise \(clock(86_400 + 6 * 3600))")
        XCTAssertEqual(finnish["airQuality"]?.value, "18")
        XCTAssertEqual(finnish["airQuality"]?.detail, "Good · PM2.5 4")
        XCTAssertEqual(finnish["airQuality"]?.targetValue, 100)
        XCTAssertTrue(finnish.values.allSatisfy { $0.source == .weather })

        let american = Dictionary(uniqueKeysWithValues: WeatherSnapshotBuilder.metrics(
            from: forecast, airQuality: air, now: now, locale: Locale(identifier: "en_US")
        ).map { ($0.id, $0) })
        XCTAssertEqual(american["wind"]?.value, "12 mph")
        XCTAssertEqual(american["airQuality"]?.value, "40")
        XCTAssertEqual(american["airQuality"]?.targetValue, 300)

        let withoutAir = WeatherSnapshotBuilder.metrics(from: forecast, now: now)
        XCTAssertFalse(withoutAir.contains { $0.id == "airQuality" }, "A failed air quality request leaves the tile empty")
    }

    func testUnitsFollowTheChosenSettingOverTheRegion() {
        let finnish = Locale(identifier: "fi_FI")
        XCTAssertEqual(WeatherFormat.temperature(20, unit: .fahrenheit), "68°")
        XCTAssertEqual(WeatherFormat.temperature(20, unit: .celsius), "20°")
        XCTAssertEqual(WeatherFormat.temperature(-0.2, unit: .celsius), "0°")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .metersPerSecond, locale: Locale(identifier: "en_US")), "5 m/s")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .kilometersPerHour, locale: finnish), "18 km/h")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .milesPerHour, locale: finnish), "11 mph")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .automatic, locale: finnish), "5 m/s")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .automatic, locale: Locale(identifier: "de_DE")), "18 km/h")
        XCTAssertEqual(WeatherFormat.wind(5, unit: .automatic, locale: Locale(identifier: "en_US")), "11 mph")
        XCTAssertTrue(WeatherFormat.precipitation(25.4, locale: finnish, unit: .inches).hasSuffix(" in"))
        XCTAssertTrue(WeatherFormat.precipitation(25.4, locale: Locale(identifier: "en_US"), unit: .millimeters).hasSuffix(" mm"))
        XCTAssertEqual(WeatherFormat.compass(350), "N")
        XCTAssertEqual(WeatherFormat.compass(225), "SW")
        XCTAssertEqual(WeatherFormat.compass(-90), "W")
    }

    func testStoredUnitChoicesAreUsedAndClearedWithLocalData() {
        UnitPreferences.defaults.set(TemperatureUnit.fahrenheit.rawValue, forKey: UnitPreferences.temperatureKey)
        XCTAssertEqual(WeatherFormat.temperature(20), "68°")
        DashboardStore.deleteLocalData()
        XCTAssertEqual(UnitPreferences.temperature, .automatic)
    }

    func testAirQualityBandsAndHealthFormats() {
        XCTAssertEqual(AirQualityLevel.european(19).label, "Good")
        XCTAssertEqual(AirQualityLevel.european(85).label, "Very poor")
        XCTAssertEqual(AirQualityLevel.us(50).label, "Good")
        XCTAssertEqual(AirQualityLevel.us(151).label, "Unhealthy")
        XCTAssertEqual(HealthFormat.duration(seconds: 2_700), "45m")
        XCTAssertEqual(HealthFormat.duration(seconds: 3_900), "1h 5m")
        XCTAssertTrue(HealthFormat.distance(meters: 5_200, locale: Locale(identifier: "fi_FI")).contains("km"))
        XCTAssertTrue(HealthFormat.distance(meters: 5_200, locale: Locale(identifier: "en_US")).contains("mi"))
    }

    func testExtraLargeStarterFitsItsGridWithoutOverlaps() {
        let design = WidgetDesign.starter(for: .extraLarge)
        XCTAssertEqual(design.canvas.columns, 8)
        for tile in design.tiles {
            XCTAssertTrue(design.fits(tile, in: .extraLarge), "\(tile.metricID) is outside the grid")
            XCTAssertTrue(design.canPlace(tile), "\(tile.metricID) overlaps another tile")
        }
    }
}
