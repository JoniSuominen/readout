package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
    private fun render(metric: DashboardMetric, mode: WidgetPrivacyMode) =
        WidgetPrivacy.render(listOf(metric), mode, baseNow, testEnv).single()

    private val steps = DashboardMetric(
        "steps", DataSource.HEALTH, "Steps", "8,420", "84% of goal", Symbols.WALK, "mint",
        numericValue = 8420.0, targetValue = 10000.0, observedAt = baseNow, origin = "Pixel Watch",
        expiresAt = baseNow.plusMinutes(600),
    )

    @Test
    fun weatherIsPublicAndStaysVisibleInDiscreetWidgets() {
        val uv = DashboardMetric("uv", DataSource.WEATHER, "UV index", "5", "Moderate", Symbols.SUN, "yellow", numericValue = 5.0, targetValue = 11.0)
        val rendered = render(uv, WidgetPrivacyMode.DISCREET)
        assertEquals("5", rendered.value)
        assertEquals(5.0, rendered.numericValue)
        assertEquals("yellow", rendered.tint)
    }

    @Test
    fun discreetHealthRemovesValuesAndAllIndirectValueChannels() {
        val rendered = render(steps, WidgetPrivacyMode.DISCREET)
        assertEquals("Hidden", rendered.value)
        assertEquals("Steps", rendered.title)
        assertEquals("gray", rendered.tint)
        assertEquals(Symbols.LOCK, rendered.symbol)
        assertNull(rendered.numericValue)
        assertNull(rendered.targetValue)
        assertNull(rendered.gaugeProgress)
        assertNull(rendered.observedAt)
        assertNull(rendered.origin)
        assertFalse(rendered.detail.contains("84"))
        assertEquals("Expiry still applies", steps.expiresAt, rendered.expiresAt)
        assertEquals("Health", render(steps.copy(id = "future"), WidgetPrivacyMode.DISCREET).title)
    }

    @Test
    fun showingHealthValuesPreservesMeasurementButNeverTheRecordingDevice() {
        val rendered = render(steps, WidgetPrivacyMode.SHOW_VALUES)
        assertEquals("8,420", rendered.value)
        assertEquals(0.842, rendered.gaugeProgress!!, 1e-9)
        assertNull("Widgets never receive the device that recorded a value", rendered.origin)
    }

    @Test
    fun calendarTitleNeedsBothSourceAndWidgetOptIn() {
        val event = CalendarEventSummary("personal", "  Therapy  ", baseNow.plusMinutes(30), baseNow.plusMinutes(60), false)
        val optedIn = CalendarSnapshotBuilder.metric(event, showEventTitles = true, now = baseNow, env = testEnv)
        assertEquals("Busy", optedIn.value)
        assertEquals("Therapy", optedIn.privateValue)
        assertEquals("Therapy", render(optedIn, WidgetPrivacyMode.SHOW_VALUES).value)
        assertNull(render(optedIn, WidgetPrivacyMode.SHOW_VALUES).privateValue)
        val discreet = render(optedIn, WidgetPrivacyMode.DISCREET)
        assertEquals("Busy", discreet.value)
        assertNull(discreet.privateValue)
        assertEquals("10:30", discreet.detail)
        assertEquals(event.endsAt, discreet.endsAt)
        assertEquals(event.endsAt, discreet.expiresAt)

        val optedOut = CalendarSnapshotBuilder.metric(event, showEventTitles = false, now = baseNow, env = testEnv)
        assertNull(optedOut.privateValue)
        assertEquals("Busy", render(optedOut, WidgetPrivacyMode.SHOW_VALUES).value)
    }

    @Test
    fun storedValueIsNeverTrustedAsATitle() {
        val legacy = DashboardMetric(
            "nextEvent", DataSource.CALENDAR, "Up next", "Confidential title", "Confidential detail",
            Symbols.CALENDAR, "blue", observedAt = baseNow.plusMinutes(30),
        )
        for (mode in WidgetPrivacyMode.entries) {
            val rendered = render(legacy, mode)
            assertEquals("Busy", rendered.value)
            assertFalse(rendered.detail.contains("Confidential"))
        }
    }

    @Test
    fun noUpcomingEventsRemainsUsefulWithoutExposingAnEventTitle() {
        val empty = CalendarSnapshotBuilder.metric(null, showEventTitles = true, now = baseNow, env = testEnv)
        assertEquals("No upcoming events", empty.value)
        assertEquals("Next 7 days", empty.detail)
        val rendered = render(empty.copy(privateValue = "Stale title"), WidgetPrivacyMode.SHOW_VALUES)
        assertEquals("No upcoming events", rendered.value)
        assertNull(rendered.observedAt)
    }

    @Test
    fun discreetWidgetsKeepAgendaTimesButNeverTitles() {
        val secret = "Confidential medical appointment"
        val events = listOf(CalendarEventSummary("personal", secret, baseNow.plusMinutes(10), baseNow.plusMinutes(40), false))
        val items = CalendarSnapshotBuilder.agenda(events, setOf("personal"), baseNow, showEventTitles = true, zone = utc)
        val metric = CalendarSnapshotBuilder.agendaMetric(items)
        val discreet = render(metric, WidgetPrivacyMode.DISCREET)
        assertEquals(1, discreet.items!!.size)
        assertNull(discreet.items!![0].title)
        assertEquals(items[0].startsAt, discreet.items!![0].startsAt)
        assertFalse(ReadoutJson.encodeToString(DashboardMetric.serializer(), discreet).contains("Confidential"))
        assertEquals(secret, render(metric, WidgetPrivacyMode.SHOW_VALUES).items!![0].title)
    }

    @Test
    fun widgetConfigurationOrdersMetricsAndDefaultsToShowingValues() {
        val metrics = listOf(
            metric("steps", DataSource.HEALTH), metric("temperature", DataSource.WEATHER),
            metric("uv", DataSource.WEATHER), metric("future", DataSource.HEALTH),
        )
        assertEquals(WidgetPrivacyMode.SHOW_VALUES, WidgetInstanceConfiguration.homeDefault.privacyMode)
        assertEquals(
            listOf("uv", "temperature", "steps", "future"),
            WidgetInstanceConfiguration().orderedMetrics(metrics).map { it.id },
        )
        assertEquals(
            listOf("steps", "uv", "temperature", "future"),
            WidgetInstanceConfiguration(primaryMetricID = "steps").orderedMetrics(metrics).map { it.id },
        )
        assertEquals(
            "uv", WidgetInstanceConfiguration(primaryMetricID = "missing").orderedMetrics(metrics).first().id,
        )
        val discreet = WidgetInstanceConfiguration(privacyMode = WidgetPrivacyMode.DISCREET).renderedMetrics(metrics, baseNow, testEnv)
        assertTrue(discreet.filter { it.source == DataSource.HEALTH }.all { it.value == "Hidden" })
    }
}
