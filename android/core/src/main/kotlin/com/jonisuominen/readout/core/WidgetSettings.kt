package com.jonisuominen.readout.core

import java.time.Instant

/**
 * Every metric a tile can show, including ones whose source is not connected
 * yet. The order is also the dashboard order.
 */
data class MetricDescriptor(
    val id: String,
    val name: String,
    val source: DataSource,
    val symbol: String,
)

object MetricCatalog {
    val all: List<MetricDescriptor> = listOf(
        MetricDescriptor("temperature", "Temperature", DataSource.WEATHER, Symbols.THERMOMETER),
        MetricDescriptor("uv", "UV index", DataSource.WEATHER, Symbols.SUN),
        MetricDescriptor("rain", "Rain today", DataSource.WEATHER, Symbols.DROP),
        MetricDescriptor("tomorrow", "Tomorrow", DataSource.WEATHER, Symbols.CALENDAR),
        MetricDescriptor("feelsLike", "Feels like", DataSource.WEATHER, Symbols.THERMOMETER),
        MetricDescriptor("wind", "Wind", DataSource.WEATHER, Symbols.WIND),
        MetricDescriptor("humidity", "Humidity", DataSource.WEATHER, Symbols.HUMIDITY),
        MetricDescriptor("sun", "Sunrise and sunset", DataSource.WEATHER, Symbols.SUNRISE),
        MetricDescriptor("airQuality", "Air quality", DataSource.WEATHER, Symbols.AIR),
        MetricDescriptor("readiness", "Readiness", DataSource.OURA, Symbols.SPARKLES),
        MetricDescriptor("sleep", "Sleep", DataSource.OURA, Symbols.SLEEP),
        MetricDescriptor("steps", "Steps", DataSource.HEALTH, Symbols.WALK),
        MetricDescriptor("heart", "Resting heart rate", DataSource.HEALTH, Symbols.HEART),
        MetricDescriptor("heartRate", "Heart rate", DataSource.HEALTH, Symbols.HEART),
        MetricDescriptor("hrv", "HRV", DataSource.HEALTH, Symbols.HEART),
        MetricDescriptor("activeEnergy", "Active energy", DataSource.HEALTH, Symbols.FLAME),
        MetricDescriptor("exercise", "Exercise minutes", DataSource.HEALTH, Symbols.RUN),
        MetricDescriptor("distance", "Distance", DataSource.HEALTH, Symbols.DISTANCE),
        MetricDescriptor("workout", "Last workout", DataSource.HEALTH, Symbols.DUMBBELL),
        MetricDescriptor("nextEvent", "Next event", DataSource.CALENDAR, Symbols.CALENDAR),
        MetricDescriptor("agenda", "Agenda", DataSource.CALENDAR, Symbols.LIST),
    )

    private val byID = all.associateBy { it.id }

    /** The refresh button, offered in the editor beside the metrics. */
    val sync = MetricDescriptor(WidgetTile.SYNC_ID, "Refresh and age", DataSource.WEATHER, Symbols.REFRESH)

    fun descriptor(id: String): MetricDescriptor? = byID[id]

    /** Single-metric views set to Automatic show the first available metric. */
    val automaticPriority = listOf(
        "uv", "temperature", "nextEvent", "readiness", "steps", "sleep", "heart", "hrv", "heartRate", "rain", "tomorrow",
        "feelsLike", "wind", "airQuality", "humidity", "sun", "activeEnergy", "exercise", "distance", "workout",
    )
}

enum class WidgetPrivacyMode(val key: String, val displayName: String, val explanation: String) {
    /** Always conceal health measurements and event titles. */
    DISCREET("discreet", "Discreet", "Hide health values and event titles"),

    /** Show authorized values. */
    SHOW_VALUES("showValues", "Show values", "Allow private values");

    companion object {
        fun fromKey(key: String?): WidgetPrivacyMode? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A widget's own settings. Each installed widget has its own, so one widget
 * cannot change another's choices.
 */
data class WidgetInstanceConfiguration(
    /** Tile layout. Null uses the first layout for the widget's size. */
    val designID: String? = null,
    /** Metric for single-metric views. Empty means automatic. */
    val primaryMetricID: String = "",
    val privacyMode: WidgetPrivacyMode = WidgetPrivacyMode.SHOW_VALUES,
) {
    fun orderedMetrics(metrics: List<DashboardMetric>): List<DashboardMetric> {
        val priorities = MetricCatalog.automaticPriority
        // A stable sort preserves source order for equally ranked or new metric types.
        val ordered = metrics.sortedBy { metric ->
            priorities.indexOf(metric.id).let { if (it < 0) priorities.size else it }
        }.toMutableList()
        if (primaryMetricID.isNotEmpty()) {
            val index = ordered.indexOfFirst { it.id == primaryMetricID }
            if (index >= 0) ordered.add(0, ordered.removeAt(index))
        }
        return ordered
    }

    fun renderedMetrics(metrics: List<DashboardMetric>, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): List<DashboardMetric> =
        orderedMetrics(WidgetPrivacy.render(metrics, privacyMode, now, env))

    companion object {
        val homeDefault = WidgetInstanceConfiguration(privacyMode = WidgetPrivacyMode.SHOW_VALUES)
    }
}

/**
 * Project source data into display-safe metrics before drawing a widget or
 * a preview. Concealed measurements must never survive in a gauge, score
 * description, tint, accessibility label, or hidden private title.
 * Weather is public forecast data for a coarse location and is never concealed.
 */
object WidgetPrivacy {
    fun render(
        metrics: List<DashboardMetric>,
        mode: WidgetPrivacyMode,
        now: Instant = Instant.now(),
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): List<DashboardMetric> = metrics.map { render(it, mode, now, env) }

    private fun render(metric: DashboardMetric, mode: WidgetPrivacyMode, now: Instant, env: DisplayEnvironment): DashboardMetric {
        if (metric.source == DataSource.CALENDAR) {
            return if (metric.id == "agenda") renderAgenda(metric, mode) else renderCalendar(metric, mode, now, env)
        }
        if (metric.source != DataSource.WEATHER && mode == WidgetPrivacyMode.DISCREET) {
            return DashboardMetric(
                id = metric.id,
                source = metric.source,
                title = healthTitle(metric.id),
                value = "Hidden",
                detail = "Open app for value",
                symbol = Symbols.LOCK,
                tint = "gray",
                expiresAt = metric.expiresAt,
            )
        }
        return DashboardMetric(
            id = metric.id,
            source = metric.source,
            title = metric.title,
            value = metric.value,
            detail = metric.detail,
            symbol = metric.symbol,
            tint = metric.tint,
            numericValue = metric.numericValue,
            targetValue = metric.targetValue,
            observedAt = metric.observedAt,
            expiresAt = metric.expiresAt,
        )
    }

    private fun renderCalendar(metric: DashboardMetric, mode: WidgetPrivacyMode, now: Instant, env: DisplayEnvironment): DashboardMetric {
        val hasEvent = metric.value != NO_EVENTS
        val title = metric.privateValue?.trim()
        // Only the public sentinel is trusted; a stored value is never a title grant.
        val value = if (mode == WidgetPrivacyMode.SHOW_VALUES && hasEvent && !title.isNullOrEmpty()) {
            title
        } else if (hasEvent) {
            "Busy"
        } else {
            NO_EVENTS
        }
        val startsAt = metric.observedAt
        val detail = when {
            !hasEvent -> "Selected calendars"
            startsAt != null -> EventTiming.startText(startsAt, now, env)
            else -> "Open app for schedule"
        }
        return DashboardMetric(
            id = metric.id,
            source = metric.source,
            title = "Up next",
            value = value,
            detail = detail,
            symbol = Symbols.CALENDAR,
            tint = "blue",
            observedAt = if (hasEvent) metric.observedAt else null,
            endsAt = if (hasEvent) metric.endsAt else null,
            expiresAt = metric.expiresAt,
        )
    }

    /** Times stay; titles survive only in widgets set to show values. */
    private fun renderAgenda(metric: DashboardMetric, mode: WidgetPrivacyMode): DashboardMetric {
        val items = metric.items.orEmpty().map { item ->
            AgendaItem(
                startsAt = item.startsAt,
                endsAt = item.endsAt,
                isAllDay = item.isAllDay,
                title = if (mode == WidgetPrivacyMode.SHOW_VALUES) item.title else null,
            )
        }
        return DashboardMetric(
            id = metric.id,
            source = metric.source,
            title = "Agenda",
            value = if (items.isEmpty()) NO_EVENTS else "Busy",
            detail = if (items.isEmpty()) "Selected calendars" else "",
            symbol = Symbols.LIST,
            tint = "blue",
            observedAt = items.firstOrNull { !it.isAllDay }?.startsAt,
            expiresAt = metric.expiresAt,
            items = items,
        )
    }

    private fun healthTitle(id: String): String = when (id) {
        "readiness" -> "Readiness"
        "sleep" -> "Sleep"
        "steps" -> "Steps"
        "heart" -> "Resting heart rate"
        "heartRate" -> "Heart rate"
        "hrv" -> "HRV"
        "activeEnergy" -> "Active energy"
        "exercise" -> "Exercise minutes"
        "distance" -> "Distance"
        "workout" -> "Last workout"
        else -> "Health"
    }

    const val NO_EVENTS = "No upcoming events"
}
