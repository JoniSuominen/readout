package com.jonisuominen.readout.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import com.jonisuominen.readout.container
import com.jonisuominen.readout.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Redraws placed widgets from the cache. It never fetches. */
object WidgetUpdater {
    /** Bumped on every redraw request, so a running widget session reads the cache again. */
    val revision = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val TICK_MILLIS = 60_000L
    private const val EXPIRED_REFRESH_INTERVAL_MILLIS = 5 * 60_000L

    @Volatile
    private var lastExpiredRefresh = -EXPIRED_REFRESH_INTERVAL_MILLIS

    private val receivers = listOf(
        SmallWidgetReceiver::class.java,
        MediumWidgetReceiver::class.java,
        LargeWidgetReceiver::class.java,
    )

    /** `onDone` runs once the redraw has been handed to the launcher, or at once when no widget is placed. */
    fun requestUpdate(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        if (!hasWidgets(app)) {
            onDone()
            return
        }
        scope.launch {
            try {
                updateNow(app)
            } finally {
                onDone()
            }
        }
    }

    suspend fun updateNow(context: Context) {
        val app = context.applicationContext
        revision.update { it + 1 }
        try {
            SmallWidget().updateAll(app)
            MediumWidget().updateAll(app)
            LargeWidget().updateAll(app)
        } catch (_: Exception) {
            // A launcher that is restarting takes the next update instead.
        }
    }

    /**
     * Asks for a fetch when a shown value has expired, at most once every few
     * minutes: offline, an expired value stays expired however often this asks.
     */
    fun refreshExpiredValues(context: Context) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastExpiredRefresh < EXPIRED_REFRESH_INTERVAL_MILLIS) return
        val dashboard = app.container.store.dashboard
        if (dashboard.expiredSources().none { it in dashboard.connectedSources }) return
        lastExpiredRefresh = now
        RefreshWorker.refreshExpired(app)
    }

    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return receivers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /**
     * Ages and countdowns are computed when a widget is drawn, so widgets are
     * redrawn about once a minute. The alarm never wakes the phone: it only
     * fires while the phone is already awake.
     */
    fun ensureTicking(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        if (!hasWidgets(app)) {
            alarms.cancel(tickIntent(app))
            return
        }
        alarms.setRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + TICK_MILLIS,
            TICK_MILLIS,
            tickIntent(app),
        )
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetTickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Nobody sees a widget while the screen is off.
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return
        WidgetUpdater.refreshExpiredValues(context)
        // Keep the process alive until the redraw has been handed over.
        val pending = goAsync()
        WidgetUpdater.requestUpdate(context) { pending.finish() }
    }
}
