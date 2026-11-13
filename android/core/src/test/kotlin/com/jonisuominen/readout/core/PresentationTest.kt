package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PresentationTest {
    @Test
    fun updatedLabelUsesWholeMinutesThenHoursAndDays() {
        assertEquals("0m", UpdatedLabel.short(baseNow, baseNow.plusSeconds(59)))
        assertEquals("12m", UpdatedLabel.short(baseNow, baseNow.plusMinutes(12)))
        assertEquals("3h", UpdatedLabel.short(baseNow, baseNow.plusMinutes(3 * 60 + 59)))
        assertEquals("2d", UpdatedLabel.short(baseNow, baseNow.plusMinutes(2 * 24 * 60 + 5)))
        assertEquals("0m", UpdatedLabel.short(baseNow, baseNow.minusSeconds(120)))
        assertEquals("Updated 5 min ago", UpdatedLabel.long(baseNow, baseNow.plusMinutes(5)))
        assertEquals("Updated 3 h ago", UpdatedLabel.long(baseNow, baseNow.plusMinutes(190)))
        assertEquals("Updated 1 d ago", UpdatedLabel.long(baseNow, baseNow.plusMinutes(24 * 60)))
    }

    @Test
    fun eventTimingSaysWhenTheEventHappens() {
        val start = baseNow.plusMinutes(25)
        val end = start.plusMinutes(30)
        assertEquals("10:25", EventTiming.startText(start, baseNow, testEnv))
        assertEquals("Tomorrow 09:00", EventTiming.startText(baseNow.plusMinutes(23 * 60), baseNow, testEnv))
        assertEquals("Thu 10:00", EventTiming.startText(baseNow.plusMinutes(3 * 24 * 60), baseNow, testEnv))
        assertEquals("in 25m", EventTiming.relativeText(start, end, baseNow, testEnv))
        assertEquals("in 3h 5m", EventTiming.relativeText(baseNow.plusMinutes(185), null, baseNow, testEnv))
        assertEquals("in 2h", EventTiming.relativeText(baseNow.plusMinutes(120), null, baseNow, testEnv))
        assertEquals("in 2d", EventTiming.relativeText(baseNow.plusMinutes(2 * 24 * 60 + 30), null, baseNow, testEnv))
        assertEquals("until 10:55", EventTiming.relativeText(start, end, start.plusMinutes(1), testEnv))
        assertEquals("now", EventTiming.relativeText(start, null, start.plusMinutes(1), testEnv))
        assertEquals("in 25m", EventTiming.compactText(start, end, baseNow, testEnv))
        assertEquals("Now", EventTiming.compactText(start, end, start, testEnv))
        assertEquals("12:00", EventTiming.compactText(baseNow.plusMinutes(120), null, baseNow, testEnv))
        assertTrue(EventTiming.isOngoing(start, end, start.plusMinutes(29)))
        assertFalse(EventTiming.isOngoing(start, end, end))
        assertFalse(EventTiming.isOngoing(start, null, start.plusMinutes(1)))
        assertEquals("All day", EventTiming.agendaText(AgendaItem(start, end, true, null), baseNow, testEnv))
    }

    @Test
    fun twelveHourClockFollowsTheSetting() {
        assertEquals("10:25 AM", usEnv.clock(baseNow.plusMinutes(25)))
        assertEquals("10:25", testEnv.clock(baseNow.plusMinutes(25)))
    }

    @Test
    fun uvLevelsFollowWHOCategoriesForTheRoundedValue() {
        assertEquals(UVLevel.LOW, UVLevel.of(2.4))
        assertEquals(UVLevel.MODERATE, UVLevel.of(2.5))
        assertEquals(UVLevel.HIGH, UVLevel.of(5.5))
        assertEquals(UVLevel.VERY_HIGH, UVLevel.of(7.5))
        assertEquals(UVLevel.EXTREME, UVLevel.of(10.5))
    }

    @Test
    fun unitsFollowTheChosenSettingOverTheRegion() {
        assertEquals("14°", WeatherFormat.temperature(14.2, TemperatureUnit.AUTOMATIC, Locale.UK))
        assertEquals("58°", WeatherFormat.temperature(14.2, TemperatureUnit.AUTOMATIC, Locale.US))
        assertEquals("14°", WeatherFormat.temperature(14.2, TemperatureUnit.CELSIUS, Locale.US))
        assertEquals("0°", WeatherFormat.temperature(-0.4, TemperatureUnit.CELSIUS, Locale.UK))
        assertEquals("-3°", WeatherFormat.temperature(-3.4, TemperatureUnit.CELSIUS, Locale.UK))

        val finland = Locale.forLanguageTag("fi-FI")
        assertEquals("5 m/s", WeatherFormat.wind(5.2, WindUnit.AUTOMATIC, finland))
        assertEquals("19 km/h", WeatherFormat.wind(5.2, WindUnit.AUTOMATIC, Locale.GERMANY))
        assertEquals("12 mph", WeatherFormat.wind(5.2, WindUnit.AUTOMATIC, Locale.US))
        assertEquals("12 mph", WeatherFormat.wind(5.2, WindUnit.AUTOMATIC, Locale.UK))
        assertEquals("5 m/s", WeatherFormat.wind(5.2, WindUnit.METERS_PER_SECOND, Locale.US))
        assertEquals("0 m/s", WeatherFormat.wind(-2.0, WindUnit.METERS_PER_SECOND, Locale.US))

        assertEquals("2.4 mm", WeatherFormat.precipitation(2.42, RainUnit.AUTOMATIC, Locale.UK))
        assertEquals("12 mm", WeatherFormat.precipitation(12.4, RainUnit.AUTOMATIC, Locale.UK))
        assertEquals("0.10 in", WeatherFormat.precipitation(2.54, RainUnit.AUTOMATIC, Locale.US))
        assertEquals("1.5 in", WeatherFormat.precipitation(38.1, RainUnit.INCHES, Locale.UK))
        assertEquals("2.5 mm", WeatherFormat.precipitation(2.54, RainUnit.MILLIMETERS, Locale.US))
    }

    @Test
    fun compassNamesTheDirectionTheWindBlowsFrom() {
        assertEquals("N", WeatherFormat.compass(0.0))
        assertEquals("N", WeatherFormat.compass(350.0))
        assertEquals("NE", WeatherFormat.compass(45.0))
        assertEquals("NW", WeatherFormat.compass(-45.0))
        assertEquals("S", WeatherFormat.compass(540.0))
    }

    @Test
    fun airQualityBandsAndHealthFormats() {
        assertEquals("Good", AirQualityLevel.european(19.9).label)
        assertEquals("Fair", AirQualityLevel.european(20.0).label)
        assertEquals("Extremely poor", AirQualityLevel.european(120.0).label)
        assertEquals("Good", AirQualityLevel.us(50.0).label)
        assertEquals("Moderate", AirQualityLevel.us(51.0).label)
        assertEquals("Hazardous", AirQualityLevel.us(301.0).label)

        assertEquals("5.2 km", HealthFormat.distance(5200.0, Locale.forLanguageTag("en-IE")))
        assertEquals("3.2 mi", HealthFormat.distance(5200.0, Locale.US))
        assertEquals("45m", HealthFormat.duration(2700.0))
        assertEquals("1h 5m", HealthFormat.duration(3900.0))
        assertEquals("7h 42m", HealthFormat.sleepDuration(7 * 3600.0 + 42 * 60))
        assertEquals("8h", HealthFormat.sleepDuration(8 * 3600.0))
        assertEquals("45m", HealthFormat.sleepDuration(45 * 60.0))
        assertNull(HealthFormat.list(emptyList()))
        assertEquals("Fitbit", HealthFormat.list(listOf("Fitbit", "Fitbit")))
        assertEquals("Fitbit and Oura", HealthFormat.list(listOf("Oura", "Fitbit")))
        assertEquals("Fitbit, Oura, and Samsung Health", HealthFormat.list(listOf("Samsung Health", "Oura", "Fitbit")))
    }

    @Test
    fun linksNameOnlyKnownMetricsAndNothingElse() {
        assertEquals("com.jonisuominen.readout://open/steps", TileLink.uri("steps"))
        assertEquals("steps", TileLink.metricID("com.jonisuominen.readout", "open", listOf("steps")))
        assertNull(TileLink.uri("unknown"))
        assertNull("The refresh tile is a button, not a link", TileLink.uri(WidgetTile.SYNC_ID))
        // The Oura sign-in callback shares the scheme and must never be read as a tile.
        assertNull(TileLink.metricID("com.jonisuominen.readout", "oauth", listOf("oura")))
        assertNull(TileLink.metricID("com.jonisuominen.readout", "open", listOf("unknown")))
        assertNull(TileLink.metricID("com.jonisuominen.readout", "open", listOf("steps", "extra")))
        assertNull(TileLink.metricID("https", "open", listOf("steps")))
    }

    @Test
    fun calendarAndHealthTilesLeaveForTheirOwnApp() {
        val start = baseNow.plusMinutes(90)
        val event = metric("nextEvent", DataSource.CALENDAR, observedAt = start)
        assertEquals(
            TileLink.Destination.Calendar(start),
            TileLink.destination("nextEvent", listOf(event), OuraConnectionMethod.HEALTH_CONNECT, baseNow),
        )
        assertEquals(TileLink.Destination.Health, TileLink.destination("steps", emptyList(), OuraConnectionMethod.DIRECT))
        assertEquals(TileLink.Destination.Health, TileLink.destination("sleep", emptyList(), OuraConnectionMethod.HEALTH_CONNECT))
        assertEquals(
            "Oura account data stays in Readout",
            TileLink.Destination.App, TileLink.destination("sleep", emptyList(), OuraConnectionMethod.DIRECT),
        )
        assertEquals(TileLink.Destination.App, TileLink.destination("uv", emptyList(), OuraConnectionMethod.DIRECT))
        assertEquals(TileLink.Destination.App, TileLink.destination("unknown", emptyList(), OuraConnectionMethod.DIRECT))
    }
}
