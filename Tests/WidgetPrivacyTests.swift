import Foundation
import XCTest
@testable import Readout

final class WidgetPrivacyTests: XCTestCase {
    func testNewLockWidgetKeepsHealthValuesPrivate() {
        let configuration = LockWidgetIntent().instanceConfiguration
        XCTAssertEqual(configuration, .lockDefault)
        XCTAssertNil(configuration.designID)
        XCTAssertEqual(configuration.primaryMetricID, "")
        XCTAssertEqual(configuration.privacyMode, .discreet)

        let rendered = configuration.renderedMetrics([healthMetric("readiness")])
        XCTAssertEqual(rendered.first?.value, "Hidden")
        XCTAssertNil(rendered.first?.gaugeProgress)
    }

    func testNewHomeWidgetShowsHealthValuesByDefault() {
        let configuration = HomeWidgetIntent().instanceConfiguration
        XCTAssertEqual(configuration, .homeDefault)
        XCTAssertNil(configuration.designID)
        XCTAssertEqual(configuration.privacyMode, .showValues)

        let original = healthMetric("readiness")
        let rendered = configuration.renderedMetrics([original])
        XCTAssertEqual(rendered.first?.value, original.value)
        XCTAssertEqual(rendered.first?.numericValue, original.numericValue)
    }

    func testLockWidgetRequiresAnExplicitOptInToShowHealthValues() {
        var intent = LockWidgetIntent()
        intent.privacyMode = .showValues

        let original = healthMetric("readiness")
        let configuration = intent.instanceConfiguration
        XCTAssertEqual(configuration.privacyMode, .showValues)
        XCTAssertEqual(configuration.renderedMetrics([original]).first?.value, original.value)
        XCTAssertEqual(LockWidgetIntent().instanceConfiguration.privacyMode, .discreet)
    }

    func testUnspecifiedInstancePrivacyIsConservative() {
        XCTAssertEqual(WidgetInstanceConfiguration().privacyMode, .discreet)
        XCTAssertEqual(WidgetInstanceConfiguration(designID: "layout", primaryMetricID: "steps").privacyMode, .discreet)
    }

    func testIntentParametersDescribeOnlyTheConfiguredWidget() {
        let design = WidgetDesign.starter(for: .medium)
        var home = HomeWidgetIntent()
        home.design = WidgetDesignEntity(design)
        home.privacyMode = .discreet
        var lock = LockWidgetIntent()
        lock.primaryMetric = .sleep
        lock.privacyMode = .showValues

        XCTAssertEqual(home.instanceConfiguration, WidgetInstanceConfiguration(designID: design.id, privacyMode: .discreet))
        XCTAssertEqual(lock.instanceConfiguration, WidgetInstanceConfiguration(primaryMetricID: "sleep", privacyMode: .showValues))
        XCTAssertEqual(HomeWidgetIntent().instanceConfiguration, .homeDefault)
        XCTAssertEqual(LockWidgetIntent().instanceConfiguration, .lockDefault)
    }

    func testEachLockWidgetKeepsItsOwnPrimaryMetric() {
        let uv = WidgetInstanceConfiguration(primaryMetricID: "uv", privacyMode: .showValues)
        let sleep = WidgetInstanceConfiguration(primaryMetricID: "sleep", privacyMode: .discreet)
        let metrics = [
            healthMetric("steps", source: .appleHealth),
            calendarMetric(),
            weatherMetric("uv"),
            healthMetric("sleep")
        ]
        XCTAssertEqual(uv.orderedMetrics(metrics).first?.id, "uv")
        XCTAssertEqual(sleep.orderedMetrics(metrics).first?.id, "sleep")
        XCTAssertEqual(sleep.renderedMetrics(metrics).first?.value, "Hidden")
    }

    func testAutomaticAndMissingPrimaryFallBackToUVFirstPriority() {
        let metrics = [healthMetric("steps", source: .appleHealth), calendarMetric(), weatherMetric("temperature"), weatherMetric("uv")]
        XCTAssertEqual(WidgetInstanceConfiguration().orderedMetrics(metrics).map(\.id), ["uv", "temperature", "nextEvent", "steps"])
        let missing = WidgetInstanceConfiguration(primaryMetricID: "removedMetric")
        XCTAssertEqual(missing.orderedMetrics(metrics).first?.id, "uv")
        XCTAssertTrue(missing.renderedMetrics([]).isEmpty)
    }

    func testMetricChoicesUseStableCacheIDsAndAutomaticNeedsNoPrimary() {
        let choices: [(WidgetMetricChoice, String)] = [
            (.automatic, ""), (.uv, "uv"), (.temperature, "temperature"), (.rain, "rain"), (.tomorrow, "tomorrow"),
            (.readiness, "readiness"), (.sleep, "sleep"), (.steps, "steps"), (.heart, "heart"),
            (.heartRate, "heartRate"), (.hrv, "hrv"), (.walkingHeartRate, "walkingHeartRate"), (.nextEvent, "nextEvent"),
            (.feelsLike, "feelsLike"), (.wind, "wind"), (.humidity, "humidity"), (.sun, "sun"), (.airQuality, "airQuality"),
            (.activeEnergy, "activeEnergy"), (.exercise, "exercise"), (.distance, "distance"), (.workout, "workout")
        ]
        for (choice, expectedID) in choices {
            XCTAssertEqual(choice.metricID, expectedID)
            if choice != .automatic {
                XCTAssertNotNil(MetricCatalog.descriptor(for: choice.metricID), "Missing catalog entry for \(choice)")
            }
        }
    }

    func testWeatherIsPublicAndStaysVisibleInDiscreetWidgets() {
        let uv = weatherMetric("uv")
        let rendered = WidgetPrivacy.render([uv], mode: .discreet)
        XCTAssertEqual(rendered.first?.value, uv.value)
        XCTAssertEqual(rendered.first?.detail, uv.detail)
        XCTAssertEqual(rendered.first?.gaugeProgress, uv.gaugeProgress)
        XCTAssertNil(rendered.first?.privateValue)
    }

    func testDiscreetHealthRemovesValuesAndAllIndirectValueChannels() throws {
        let metrics = [
            healthMetric("readiness"), healthMetric("sleep"),
            healthMetric("steps", source: .appleHealth), healthMetric("heart", source: .appleHealth),
            healthMetric("heartRate", source: .appleHealth), healthMetric("hrv", source: .appleHealth),
            healthMetric("walkingHeartRate", source: .appleHealth)
        ]
        let expectedTitles = [
            "readiness": "Readiness", "sleep": "Sleep", "steps": "Steps", "heart": "Resting heart rate",
            "heartRate": "Heart rate", "hrv": "HRV", "walkingHeartRate": "Walking heart rate"
        ]
        let rendered = WidgetPrivacy.render(metrics, mode: .discreet)
        XCTAssertEqual(rendered.count, metrics.count)

        for (original, safe) in zip(metrics, rendered) {
            XCTAssertEqual(safe.id, original.id)
            XCTAssertEqual(safe.source, original.source)
            XCTAssertEqual(safe.title, expectedTitles[original.id])
            XCTAssertEqual(safe.value, "Hidden")
            XCTAssertEqual(safe.detail, "Open app for value")
            XCTAssertEqual(safe.tint, "gray")
            XCTAssertNil(safe.numericValue)
            XCTAssertNil(safe.targetValue)
            XCTAssertNil(safe.observedAt)
            XCTAssertNil(safe.privateValue)
            XCTAssertNil(safe.gaugeProgress)
        }
        let encoded = String(decoding: try JSONEncoder().encode(rendered), as: UTF8.self)
        XCTAssertFalse(encoded.contains("SECRET"), "Redacted widget data must not carry hidden values in another field")
        XCTAssertEqual(metrics.first?.value, "SECRET health value", "Rendering must not alter the source snapshot")
    }

    func testDiscreetUnknownHealthMetricAlsoHidesValue() {
        let original = healthMetric("futureHealthMetric")
        let rendered = WidgetPrivacy.render([original], mode: .discreet)
        XCTAssertEqual(rendered.first?.value, "Hidden")
        XCTAssertFalse(rendered.first?.title.contains("SECRET") ?? true)
        XCTAssertNil(rendered.first?.gaugeProgress)
        XCTAssertNil(rendered.first?.privateValue)
    }

    func testShowingHealthValuesPreservesMeasurementAndGaugeMetadata() {
        let original = healthMetric("readiness")
        let rendered = WidgetPrivacy.render([original], mode: .showValues)
        XCTAssertEqual(rendered.first?.value, original.value)
        XCTAssertEqual(rendered.first?.detail, original.detail)
        XCTAssertEqual(rendered.first?.numericValue, original.numericValue)
        XCTAssertEqual(rendered.first?.targetValue, original.targetValue)
        XCTAssertEqual(rendered.first?.observedAt, original.observedAt)
        XCTAssertEqual(rendered.first?.tint, original.tint)
        XCTAssertEqual(rendered.first?.gaugeProgress, original.gaugeProgress)
    }

    func testCalendarTitleNeedsBothSourceAndWidgetOptIn() {
        let optedIn = calendarMetric(privateValue: "Confidential strategy meeting")
        let discreet = WidgetPrivacy.render([optedIn], mode: .discreet)
        XCTAssertEqual(discreet.first?.value, "Busy")
        XCTAssertNil(discreet.first?.privateValue)

        let shown = WidgetPrivacy.render([optedIn], mode: .showValues)
        XCTAssertEqual(shown.first?.value, "Confidential strategy meeting")
        XCTAssertNil(shown.first?.privateValue)
        XCTAssertFalse(shown.first?.detail.contains("SECRET") ?? true)

        let sourceNotOptedIn = WidgetPrivacy.render([calendarMetric()], mode: .showValues)
        XCTAssertEqual(sourceNotOptedIn.first?.value, "Busy")
        XCTAssertNil(sourceNotOptedIn.first?.privateValue)
    }

    func testEmptyCalendarOptInDoesNotExposeLegacyTitle() {
        for privateValue in ["", "   "] {
            let shown = WidgetPrivacy.render([calendarMetric(privateValue: privateValue)], mode: .showValues)
            XCTAssertEqual(shown.first?.value, "Busy")
            XCTAssertNil(shown.first?.privateValue)
        }
    }

    func testNoUpcomingEventsRemainsUsefulWithoutExposingAnEventTitle() {
        let metric = calendarMetric(value: "No upcoming events")
        for mode in [WidgetPrivacyMode.discreet, .showValues] {
            let rendered = WidgetPrivacy.render([metric], mode: mode)
            XCTAssertEqual(rendered.first?.value, "No upcoming events")
            XCTAssertNil(rendered.first?.privateValue)
            XCTAssertFalse(rendered.first?.detail.contains("SECRET") ?? true)
        }
    }

    func testCalendarTimeMetadataNeverReusesLegacyConfidentialDetails() {
        let original = calendarMetric(observedAt: Date(timeIntervalSince1970: 1_800_000_000))
        for mode in [WidgetPrivacyMode.discreet, .showValues] {
            let rendered = WidgetPrivacy.render([original], mode: mode)
            XCTAssertEqual(rendered.first?.title, "Up next")
            XCTAssertEqual(rendered.first?.value, "Busy")
            XCTAssertFalse(rendered.first?.detail.contains("SECRET") ?? true)
            XCTAssertNil(rendered.first?.privateValue)
        }
    }

    func testPrePrivacyCacheDecodesAndCannotLeakLegacyTitlesOrDetails() throws {
        let json = """
        [
          {"id":"readiness","source":"Oura","title":"SECRET health title","value":"86","detail":"SECRET health detail","symbol":"sparkles","tint":"violet"},
          {"id":"nextEvent","source":"Google Calendar","title":"SECRET calendar title","value":"SECRET calendar event","detail":"SECRET calendar location","symbol":"calendar","tint":"blue"}
        ]
        """
        let oldMetrics = try JSONDecoder().decode([DashboardMetric].self, from: Data(json.utf8))
        XCTAssertNil(oldMetrics.first?.privateValue)

        let discreet = WidgetPrivacy.render(oldMetrics, mode: .discreet)
        XCTAssertEqual(discreet.map(\.title), ["Readiness", "Up next"])
        XCTAssertEqual(discreet.map(\.value), ["Hidden", "Busy"])
        let encoded = String(decoding: try JSONEncoder().encode(discreet), as: UTF8.self)
        XCTAssertFalse(encoded.contains("SECRET"))

        let shownCalendar = WidgetPrivacy.render(oldMetrics.filter { $0.source == .calendar }, mode: .showValues)
        XCTAssertEqual(shownCalendar.first?.value, "Busy")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(shownCalendar), as: UTF8.self).contains("SECRET"))
    }

    private func weatherMetric(_ id: String) -> DashboardMetric {
        DashboardMetric(
            id: id, source: .weather, title: "UV index", value: "5", detail: "Moderate · peak 6 at 13:00",
            symbol: "sun.max.fill", tint: "yellow", numericValue: 5, targetValue: 11, privateValue: "never shown"
        )
    }

    private func healthMetric(_ id: String, source: DataSource = .oura) -> DashboardMetric {
        DashboardMetric(
            id: id, source: source, title: "SECRET health title", value: "SECRET health value",
            detail: "SECRET health detail", symbol: "heart.fill", tint: "pink",
            numericValue: 86, targetValue: 100, observedAt: Date(timeIntervalSince1970: 1_800_000_000),
            privateValue: "SECRET hidden health value", origin: "SECRET device name"
        )
    }

    func testWidgetsNeverReceiveTheDeviceThatRecordedAValue() {
        for mode in WidgetPrivacyMode.allCases {
            let rendered = WidgetPrivacy.render([healthMetric("hrv", source: .appleHealth)], mode: mode)
            XCTAssertNil(rendered.first?.origin)
        }
    }

    private func calendarMetric(
        value: String = "SECRET legacy calendar event",
        privateValue: String? = nil,
        observedAt: Date? = nil
    ) -> DashboardMetric {
        DashboardMetric(
            id: "nextEvent", source: .calendar, title: "SECRET calendar title", value: value,
            detail: "SECRET calendar location", symbol: "calendar", tint: "blue",
            observedAt: observedAt, privateValue: privateValue
        )
    }
}
