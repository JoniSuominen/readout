package com.jonisuominen.readout.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.ZoneId

/**
 * Calendar identifiers form an explicit whitelist. Account and calendar names
 * stay with the system calendar provider; only the selected identifiers are
 * persisted.
 */
class CalendarPreferences(
    private val kv: KeyValueStore,
    private val dashboard: DashboardStore,
    private val reloadWidgets: () -> Unit,
) {
    var selectedCalendarIDs: Set<String>
        get() = kv.getString(SELECTED_IDS_KEY)?.let {
            try {
                ReadoutJson.decodeFromString(ListSerializer(String.serializer()), it).toSet()
            } catch (_: Exception) {
                null
            }
        } ?: emptySet()
        set(value) {
            if (value == selectedCalendarIDs) return
            kv.putString(SELECTED_IDS_KEY, ReadoutJson.encodeToString(ListSerializer(String.serializer()), value.sorted()))
            incrementRevision()
            invalidateSnapshot()
        }

    /**
     * Titles are private unless the user explicitly allows them. The public
     * metric value remains "Busy" even after opting in.
     */
    var showEventTitles: Boolean
        get() = kv.getString(TITLES_KEY) == "true"
        set(value) {
            if (value == showEventTitles) return
            kv.putString(TITLES_KEY, value.toString())
            incrementRevision()
            invalidateSnapshot()
        }

    fun reset() {
        kv.remove(SELECTED_IDS_KEY)
        kv.remove(TITLES_KEY)
        incrementRevision()
        invalidateSnapshot()
    }

    /** A completed fetch may only publish data captured under this revision. */
    val revision: Int get() = kv.getString(REVISION_KEY)?.toIntOrNull() ?: 0

    private fun incrementRevision() = kv.putString(REVISION_KEY, (revision + 1).toString())

    /** Clear private data before refreshing or waiting on a permission prompt. */
    fun invalidateSnapshot() {
        dashboard.removeMetrics(DataSource.CALENDAR)
        reloadWidgets()
    }

    private companion object {
        const val SELECTED_IDS_KEY = "calendar.selectedCalendarIDs"
        const val TITLES_KEY = "calendar.showEventTitles"
        const val REVISION_KEY = "calendar.preferencesRevision"
    }
}

/** The minimum event data used while building a snapshot. This is never stored. */
data class CalendarEventSummary(
    val calendarID: String,
    val title: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val isAllDay: Boolean,
)

object CalendarSnapshotBuilder {
    const val AGENDA_LIMIT = 5
    const val HORIZON_DAYS = 7L

    fun selectedIDs(availableIDs: Collection<String>, selection: Set<String>): Set<String> =
        selection.intersect(availableIDs.toSet())

    fun horizon(now: Instant, zone: ZoneId = DisplayEnvironment.current.zone): Instant =
        now.atZone(zone).plusDays(HORIZON_DAYS).toInstant()

    fun nextEvent(
        events: List<CalendarEventSummary>,
        selectedCalendarIDs: Set<String>,
        now: Instant,
        zone: ZoneId = DisplayEnvironment.current.zone,
    ): CalendarEventSummary? {
        val horizon = horizon(now, zone)
        return events
            .filter {
                it.calendarID in selectedCalendarIDs && !it.isAllDay &&
                    it.endsAt.isAfter(now) && it.startsAt.isBefore(horizon)
            }
            .minByOrNull { it.startsAt }
    }

    /**
     * All-day events in progress come first, then timed events by start.
     * Titles are kept only when the user allows them.
     */
    fun agenda(
        events: List<CalendarEventSummary>,
        selectedCalendarIDs: Set<String>,
        now: Instant,
        showEventTitles: Boolean,
        zone: ZoneId = DisplayEnvironment.current.zone,
    ): List<AgendaItem> {
        val horizon = horizon(now, zone)
        val upcoming = events.filter {
            it.calendarID in selectedCalendarIDs && it.endsAt.isAfter(now) && it.startsAt.isBefore(horizon)
        }
        val allDay = upcoming.filter { it.isAllDay && !it.startsAt.isAfter(now) }.sortedBy { it.endsAt }
        val timed = upcoming.filter { !it.isAllDay }.sortedBy { it.startsAt }
        return (allDay + timed).take(AGENDA_LIMIT).map { event ->
            val title = event.title?.trim()
            AgendaItem(
                startsAt = event.startsAt,
                endsAt = event.endsAt,
                isAllDay = event.isAllDay,
                title = if (showEventTitles && !title.isNullOrEmpty()) title else null,
            )
        }
    }

    fun agendaMetric(items: List<AgendaItem>, emptyDetail: String = "Next 7 days"): DashboardMetric = DashboardMetric(
        id = "agenda",
        source = DataSource.CALENDAR,
        title = "Agenda",
        value = if (items.isEmpty()) WidgetPrivacy.NO_EVENTS else "Busy",
        detail = if (items.isEmpty()) emptyDetail else "",
        symbol = Symbols.LIST,
        tint = "blue",
        observedAt = items.firstOrNull { !it.isAllDay }?.startsAt,
        // Refetch as soon as any listed event ends.
        expiresAt = items.minOfOrNull { it.endsAt },
        items = items,
    )

    fun metric(
        event: CalendarEventSummary?,
        showEventTitles: Boolean,
        now: Instant,
        env: DisplayEnvironment = DisplayEnvironment.current,
        emptyDetail: String = "Next 7 days",
    ): DashboardMetric {
        val eventTitle = event?.title?.trim()
        val privateTitle = if (showEventTitles && !eventTitle.isNullOrEmpty()) eventTitle else null
        // Countdowns are computed when drawn; a stored "in 24 min" would go stale.
        val detail = event?.let { EventTiming.startText(it.startsAt, now, env) } ?: emptyDetail
        return DashboardMetric(
            id = "nextEvent",
            source = DataSource.CALENDAR,
            title = "Up next",
            value = if (event == null) WidgetPrivacy.NO_EVENTS else "Busy",
            detail = detail,
            symbol = Symbols.CALENDAR,
            tint = "blue",
            observedAt = event?.startsAt,
            privateValue = privateTitle,
            endsAt = event?.endsAt,
            expiresAt = event?.endsAt,
        )
    }

    /** Both calendar metrics for one read of the selected calendars. */
    fun metrics(
        events: List<CalendarEventSummary>,
        selectedCalendarIDs: Set<String>,
        showEventTitles: Boolean,
        now: Instant,
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): List<DashboardMetric> = listOf(
        metric(nextEvent(events, selectedCalendarIDs, now, env.zone), showEventTitles, now, env),
        agendaMetric(agenda(events, selectedCalendarIDs, now, showEventTitles, env.zone)),
    )

    fun emptyMetrics(detail: String, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): List<DashboardMetric> = listOf(
        metric(null, false, now, env, emptyDetail = detail),
        agendaMetric(emptyList(), emptyDetail = detail),
    )
}
