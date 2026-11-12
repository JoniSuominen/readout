package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {
    private val clock = MutableClock()
    private val kv = InMemoryKeyValueStore()
    private val store = ReadoutStore(kv, clock)

    @Test
    fun newInstallDoesNotShowSampleMetrics() {
        assertTrue(store.dashboard.metrics.isEmpty())
        assertTrue(store.dashboard.connectedSources.isEmpty())
        assertNull(store.dashboard.updatedAt)
        assertEquals(OuraConnectionMethod.HEALTH_CONNECT, store.ouraMethod)
    }

    @Test
    fun metricIsExpiredFromItsExpiryInstant() {
        val metric = metric("steps", DataSource.HEALTH, expiresAt = baseNow.plusMinutes(10))
        assertFalse(metric.isExpired(baseNow.plusMinutes(9)))
        assertTrue(metric.isExpired(baseNow.plusMinutes(10)))
        assertFalse(metric("steps", DataSource.HEALTH).isExpired(baseNow.plusMinutes(100_000)))
    }

    @Test
    fun storeDropsExpiredMetricsOnRead() {
        store.dashboard.replaceMetrics(
            listOf(metric("steps", DataSource.HEALTH, expiresAt = baseNow.plusMinutes(10)), metric("heart", DataSource.HEALTH)),
            DataSource.HEALTH,
        )
        assertEquals(listOf("steps", "heart"), store.dashboard.metrics.map { it.id })
        clock.advanceMinutes(10)
        assertEquals(listOf("heart"), store.dashboard.metrics.map { it.id })
    }

    @Test
    fun cachedMetricsKeepCatalogOrderAcrossSources() {
        store.dashboard.replaceMetrics(listOf(metric("nextEvent", DataSource.CALENDAR)), DataSource.CALENDAR)
        store.dashboard.replaceMetrics(listOf(metric("steps", DataSource.HEALTH)), DataSource.HEALTH)
        store.dashboard.replaceMetrics(
            listOf(metric("uv", DataSource.WEATHER), metric("temperature", DataSource.WEATHER), metric("steps", DataSource.HEALTH)),
            DataSource.WEATHER,
        )
        assertEquals(listOf("temperature", "uv", "steps", "nextEvent"), store.dashboard.metrics.map { it.id })
        assertEquals("Another source's metric is not accepted", 1, store.dashboard.metrics.count { it.id == "steps" })
    }

    @Test
    fun refreshingOneSourceDoesNotMakeAnotherSourcesCacheFresh() {
        store.dashboard.replaceMetrics(listOf(metric("steps", DataSource.HEALTH)), DataSource.HEALTH)
        clock.advanceMinutes(90)
        store.dashboard.replaceMetrics(listOf(metric("uv", DataSource.WEATHER)), DataSource.WEATHER)
        assertEquals(baseNow, store.dashboard.updatedAt(DataSource.HEALTH))
        assertEquals(clock.instant, store.dashboard.updatedAt(DataSource.WEATHER))
        val freshness = store.freshness(store.dashboard.metrics)
        assertEquals(baseNow, freshness.oldestFetchAt)
        assertTrue(freshness.isStale(clock.instant))
        assertFalse(store.freshness(store.dashboard.metrics.filter { it.source == DataSource.WEATHER }).isStale(clock.instant))
    }

    @Test
    fun freshnessStalesAtOneHourAndImmediatelyOnFetchError() {
        val metrics = listOf(metric("steps", DataSource.HEALTH))
        val fresh = DashboardFreshness(metrics, mapOf(DataSource.HEALTH to baseNow), emptyMap())
        assertFalse(fresh.isStale(baseNow.plusMinutes(59)))
        assertTrue(fresh.isStale(baseNow.plusMinutes(60)))
        val failed = DashboardFreshness(metrics, mapOf(DataSource.HEALTH to baseNow), mapOf(DataSource.HEALTH to "Offline"))
        assertTrue(failed.isStale(baseNow))
        assertTrue(failed.hasError)
        val neverFetched = DashboardFreshness(metrics, emptyMap(), emptyMap())
        assertTrue("A measurement date is not a fetch date", neverFetched.isStale(baseNow))
        assertTrue(neverFetched.hasMissingFetch)
        assertFalse("Nothing shown is not stale", DashboardFreshness(emptyList(), emptyMap(), emptyMap()).isStale(baseNow))
    }

    @Test
    fun refreshFailurePreservesLastSuccessfulFetchAndMetrics() {
        store.dashboard.replaceMetrics(listOf(metric("steps", DataSource.HEALTH)), DataSource.HEALTH)
        clock.advanceMinutes(5)
        store.dashboard.recordError("Offline", DataSource.HEALTH)
        assertEquals(1, store.dashboard.metrics.size)
        assertEquals(baseNow, store.dashboard.updatedAt(DataSource.HEALTH))
        assertEquals("Offline", store.dashboard.error(DataSource.HEALTH))
        store.dashboard.replaceMetrics(listOf(metric("steps", DataSource.HEALTH)), DataSource.HEALTH)
        assertNull("A successful fetch clears the error", store.dashboard.error(DataSource.HEALTH))
    }

    @Test
    fun removingAndDeletingDataClearsSourceErrorsAndTimestamps() {
        store.dashboard.replaceMetrics(listOf(metric("steps", DataSource.HEALTH)), DataSource.HEALTH)
        clock.advanceMinutes(5)
        store.dashboard.replaceMetrics(listOf(metric("uv", DataSource.WEATHER)), DataSource.WEATHER)
        store.dashboard.recordError("Offline", DataSource.WEATHER)
        store.dashboard.removeMetrics(DataSource.WEATHER)
        assertNull(store.dashboard.error(DataSource.WEATHER))
        assertNull(store.dashboard.updatedAt(DataSource.WEATHER))
        assertEquals("Removing a source is not a fetch", baseNow, store.dashboard.updatedAt)

        store.dashboard.connectedSources = setOf(DataSource.HEALTH)
        store.units.temperature = TemperatureUnit.FAHRENHEIT
        store.ouraMethod = OuraConnectionMethod.DIRECT
        store.weatherLocation.save(WeatherLocation.of("Helsinki", latitude = 60.1699, longitude = 24.9384, isCurrentLocation = false))
        store.designs.save(store.designs.newDesign(WidgetCanvas.SMALL))
        store.ouraDisconnectResult = DisconnectResult(false, DisconnectResult.RemoteAuthorization.UNCONFIRMED)
        store.deleteLocalData()
        assertTrue(store.dashboard.metrics.isEmpty())
        assertTrue(store.dashboard.connectedSources.isEmpty())
        assertNull(store.dashboard.updatedAt)
        assertEquals(TemperatureUnit.AUTOMATIC, store.units.temperature)
        assertEquals(OuraConnectionMethod.HEALTH_CONNECT, store.ouraMethod)
        assertNull(store.weatherLocation.location)
        assertEquals(WidgetDesign.starters, store.designs.designs)
        assertEquals(
            "A failed cleanup stays visible after deletion",
            DisconnectResult(false, DisconnectResult.RemoteAuthorization.UNCONFIRMED), store.ouraDisconnectResult,
        )
    }

    @Test
    fun snapshotDecodesWithoutOptionalMetadataAndIgnoresUnknownFields() {
        kv.putString(
            "dashboard.metrics",
            """[{"id":"steps","source":"health","title":"Steps","value":"8,420","detail":"","symbol":"walk","tint":"mint","later":true}]""",
        )
        val metric = store.dashboard.metrics.single()
        assertNull(metric.numericValue)
        assertNull(metric.gaugeProgress)
        assertEquals("Health Connect", metric.originLabel)
        kv.putString("dashboard.metrics", "not json")
        assertTrue(store.dashboard.metrics.isEmpty())
    }

    @Test
    fun gaugeUsesNumericMetadataAndClampsExcess() {
        fun gauge(numeric: Double?, target: Double?) =
            metric("steps", DataSource.HEALTH).copy(numericValue = numeric, targetValue = target).gaugeProgress
        assertEquals(0.5, gauge(5000.0, 10000.0)!!, 1e-9)
        assertEquals(1.0, gauge(12000.0, 10000.0)!!, 1e-9)
        assertNull(gauge(5.0, 0.0))
        assertNull(gauge(-1.0, 10.0))
        assertNull(gauge(Double.NaN, 10.0))
        assertNull(gauge(5.0, null))
    }

    @Test
    fun calendarPreferencesRequireOptInAndInvalidateTheSnapshot() {
        var reloads = 0
        store.onWidgetsNeedReload = { reloads++ }
        assertTrue(store.calendar.selectedCalendarIDs.isEmpty())
        assertFalse(store.calendar.showEventTitles)

        store.dashboard.replaceMetrics(listOf(metric("nextEvent", DataSource.CALENDAR, privateValue = "Secret")), DataSource.CALENDAR)
        val revision = store.calendar.revision
        store.calendar.selectedCalendarIDs = setOf("b", "a")
        assertEquals(setOf("a", "b"), store.calendar.selectedCalendarIDs)
        assertEquals(revision + 1, store.calendar.revision)
        assertTrue("A selection change removes the fetched event", store.dashboard.metrics.isEmpty())
        assertEquals(1, reloads)

        store.calendar.selectedCalendarIDs = setOf("a", "b")
        assertEquals("An unchanged selection is not a change", revision + 1, store.calendar.revision)

        store.dashboard.replaceMetrics(listOf(metric("nextEvent", DataSource.CALENDAR, privateValue = "Secret")), DataSource.CALENDAR)
        store.calendar.showEventTitles = true
        assertTrue(store.dashboard.metrics.isEmpty())
        assertEquals(revision + 2, store.calendar.revision)

        store.calendar.reset()
        assertTrue(store.calendar.selectedCalendarIDs.isEmpty())
        assertFalse(store.calendar.showEventTitles)
        assertEquals("Reset advances the revision instead of rewinding it", revision + 3, store.calendar.revision)
    }

    @Test
    fun locationsAreRoundedBeforeTheyAreStoredOrSent() {
        val location = WeatherLocation.of("Home", latitude = 60.169857, longitude = 24.938379, isCurrentLocation = true)
        assertEquals(60.17, location.latitude, 0.0)
        assertEquals(24.94, location.longitude, 0.0)
        store.weatherLocation.save(location)
        assertEquals(location, store.weatherLocation.location)
        assertFalse(kv.getString("weather.location")!!.contains("60.169"))
    }
}
