package com.jonisuominen.readout.connect

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jonisuominen.readout.core.CalendarEventSummary
import com.jonisuominen.readout.core.CalendarSnapshotBuilder
import com.jonisuominen.readout.core.ConnectorException
import com.jonisuominen.readout.core.DashboardConnector
import com.jonisuominen.readout.core.DashboardMetric
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.DisconnectResult
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.ReadoutStore
import com.jonisuominen.readout.core.SnapshotGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

data class SelectableCalendar(val id: String, val title: String, val accountName: String)

/**
 * Reads the calendars synced to this phone through the system calendar
 * provider, whatever account they come from: Google, Outlook, Exchange, or
 * CalDAV. Events are never changed.
 */
class DeviceCalendarConnector(
    private val context: Context,
    private val store: ReadoutStore,
    private val bridge: PermissionBridge,
    private val now: () -> Instant = Instant::now,
) : DashboardConnector, SnapshotGuard {
    override val source = DataSource.CALENDAR

    val hasAccess: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun authorize() {
        if (hasAccess) return
        if (!bridge.requestPermission(Manifest.permission.READ_CALENDAR)) {
            store.calendar.invalidateSnapshot()
            throw ConnectorException.authorizationDenied("Calendar access was not granted.")
        }
    }

    override fun clearSnapshotIfAccessUnavailable(): Boolean {
        if (hasAccess) return false
        if (store.dashboard.metrics.none { it.source == DataSource.CALENDAR }) return false
        store.calendar.invalidateSnapshot()
        return true
    }

    override suspend fun refresh(): List<DashboardMetric> {
        if (!hasAccess) {
            store.calendar.invalidateSnapshot()
            throw ConnectorException.authorizationDenied("Allow calendar access in Settings to refresh events.")
        }
        val date = now()
        val env = DisplayEnvironment.current
        val selectedIDs = store.calendar.selectedCalendarIDs
        if (selectedIDs.isEmpty()) {
            return CalendarSnapshotBuilder.emptyMetrics("Choose calendars in Sources", date, env)
        }
        val showTitles = store.calendar.showEventTitles
        return withContext(Dispatchers.IO) {
            val available = CalendarSnapshotBuilder.selectedIDs(queryCalendars().map { it.id }, selectedIDs)
            if (available.isEmpty()) {
                CalendarSnapshotBuilder.emptyMetrics("Selected calendars are unavailable", date, env)
            } else {
                val events = queryEvents(available, date, CalendarSnapshotBuilder.horizon(date, env.zone), env.zone, showTitles)
                CalendarSnapshotBuilder.metrics(events, selectedIDs, showTitles, date, env)
            }
        }
    }

    override suspend fun disconnect(): DisconnectResult {
        // Android has no per-app revocation call. People revoke access in Settings.
        return DisconnectResult.localOnly
    }

    /** Every calendar on the phone, for the selection list. Needs calendar access. */
    suspend fun calendars(): List<SelectableCalendar> = withContext(Dispatchers.IO) {
        if (!hasAccess) emptyList() else queryCalendars()
    }

    private fun queryCalendars(): List<SelectableCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val calendars = ArrayList<SelectableCalendar>()
        try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendars += SelectableCalendar(
                        id = cursor.getLong(0).toString(),
                        title = cursor.getString(1).orEmpty().ifBlank { "Calendar" },
                        accountName = cursor.getString(2).orEmpty(),
                    )
                }
            }
        } catch (_: SecurityException) {
            throw ConnectorException.authorizationDenied("Allow calendar access in Settings to refresh events.")
        }
        return calendars
    }

    private fun queryEvents(
        calendarIDs: Set<String>,
        start: Instant,
        end: Instant,
        zone: ZoneId,
        includeTitles: Boolean,
    ): List<CalendarEventSummary> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            // All-day events sit on UTC midnights; widen the window so today's are included.
            ContentUris.appendId(builder, start.toEpochMilli() - DAY_MILLIS)
            ContentUris.appendId(builder, end.toEpochMilli() + DAY_MILLIS)
            builder.build()
        }
        // Titles are read only when the person allows them.
        val projection = listOfNotNull(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.TITLE.takeIf { includeTitles },
        ).toTypedArray()
        // Only ever the selected calendars: the ids are the whitelist.
        val ids = calendarIDs.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return emptyList()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})"
        val events = ArrayList<CalendarEventSummary>()
        try {
            context.contentResolver.query(
                uri, projection, selection, ids.map { it.toString() }.toTypedArray(),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(4) == CalendarContract.Events.STATUS_CANCELED) continue
                    val isAllDay = cursor.getInt(3) != 0
                    var begin = Instant.ofEpochMilli(cursor.getLong(1))
                    var finish = Instant.ofEpochMilli(cursor.getLong(2))
                    if (isAllDay) {
                        // Stored as UTC midnights; the day itself is what matters locally.
                        begin = begin.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant()
                        finish = finish.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant()
                    }
                    if (!finish.isAfter(start) || !begin.isBefore(end)) continue
                    events += CalendarEventSummary(
                        calendarID = cursor.getLong(0).toString(),
                        title = if (includeTitles) cursor.getString(5) else null,
                        startsAt = begin,
                        endsAt = finish,
                        isAllDay = isAllDay,
                    )
                }
            }
        } catch (_: SecurityException) {
            throw ConnectorException.authorizationDenied("Allow calendar access in Settings to refresh events.")
        }
        return events
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
