package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTest {
    private fun event(
        title: String,
        calendar: String = "personal",
        startsInMinutes: Long,
        lastsMinutes: Long = 30,
        isAllDay: Boolean = false,
    ) = CalendarEventSummary(calendar, title, baseNow.plusMinutes(startsInMinutes), baseNow.plusMinutes(startsInMinutes + lastsMinutes), isAllDay)

    private val events = listOf(
        event("Later", startsInMinutes = 120),
        event("Private work meeting", calendar = "work", startsInMinutes = 1),
        event("Holiday", startsInMinutes = -60, lastsMinutes = 1439, isAllDay = true),
        event("Ended", startsInMinutes = -120),
        event("Sooner", startsInMinutes = 10),
        event("Next week all day", startsInMinutes = 3 * 1440, lastsMinutes = 1439, isAllDay = true),
        event("Distant", startsInMinutes = 9 * 1440),
    )

    @Test
    fun nextEventOnlyComesFromSelectedCalendarAndExcludesEndedAndAllDayEvents() {
        assertEquals("Sooner", CalendarSnapshotBuilder.nextEvent(events, setOf("personal"), baseNow, utc)?.title)
        assertEquals("Private work meeting", CalendarSnapshotBuilder.nextEvent(events, setOf("personal", "work"), baseNow, utc)?.title)
        assertNull(CalendarSnapshotBuilder.nextEvent(events, emptySet(), baseNow, utc))
        val ongoing = listOf(event("Running", startsInMinutes = -10))
        assertEquals("An event in progress is still next", "Running", CalendarSnapshotBuilder.nextEvent(ongoing, setOf("personal"), baseNow, utc)?.title)
        assertEquals(setOf("a"), CalendarSnapshotBuilder.selectedIDs(listOf("a", "c"), setOf("a", "b")))
    }

    @Test
    fun agendaListsTodaysAllDayEventsFirstThenTimedEventsFromSelectedCalendars() {
        val items = CalendarSnapshotBuilder.agenda(events, setOf("personal"), baseNow, showEventTitles = true, zone = utc)
        assertEquals(listOf("Holiday", "Sooner", "Later"), items.map { it.title })
        assertEquals(listOf(true, false, false), items.map { it.isAllDay })
        val hidden = CalendarSnapshotBuilder.agenda(events, setOf("personal"), baseNow, showEventTitles = false, zone = utc)
        assertEquals(3, hidden.size)
        assertTrue("Titles need the explicit opt-in", hidden.all { it.title == null })
        assertTrue(CalendarSnapshotBuilder.agenda(events, emptySet(), baseNow, showEventTitles = true, zone = utc).isEmpty())
    }

    @Test
    fun agendaIsCappedAndExpiresWhenItsFirstEventEnds() {
        val many = (0 until 9).map { event("Event $it", startsInMinutes = 10L * (it + 1)) }
        val items = CalendarSnapshotBuilder.agenda(many, setOf("personal"), baseNow, showEventTitles = true, zone = utc)
        assertEquals(CalendarSnapshotBuilder.AGENDA_LIMIT, items.size)
        val metric = CalendarSnapshotBuilder.agendaMetric(items)
        assertEquals("agenda", metric.id)
        assertEquals(DataSource.CALENDAR, metric.source)
        assertEquals(baseNow.plusMinutes(40), metric.expiresAt)
        assertEquals("No upcoming events", CalendarSnapshotBuilder.agendaMetric(emptyList()).value)
    }

    @Test
    fun emptyStatesAreHonestAndCarryNoTitle() {
        val empty = CalendarSnapshotBuilder.emptyMetrics("Choose calendars in Sources", baseNow, testEnv)
        assertEquals(listOf("nextEvent", "agenda"), empty.map { it.id })
        assertTrue(empty.all { it.value == "No upcoming events" && it.detail == "Choose calendars in Sources" })
        assertTrue(empty.all { it.privateValue == null && it.observedAt == null })
        val both = CalendarSnapshotBuilder.metrics(events, setOf("personal"), showEventTitles = false, now = baseNow, env = testEnv)
        assertEquals("10:10", both[0].detail)
        assertNull(both[0].privateValue)
        assertEquals(3, both[1].items!!.size)
    }
}
