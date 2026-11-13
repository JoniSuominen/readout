package com.jonisuominen.readout.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/** Icon keys carried by metrics. The app maps each one to a drawable. */
object Symbols {
    const val SUN = "sun"
    const val MOON = "moon"
    const val CLOUD_SUN = "cloud_sun"
    const val CLOUD_MOON = "cloud_moon"
    const val CLOUD = "cloud"
    const val FOG = "fog"
    const val DRIZZLE = "drizzle"
    const val SLEET = "sleet"
    const val RAIN = "rain"
    const val SNOW = "snow"
    const val HEAVY_RAIN = "heavy_rain"
    const val THUNDER = "thunder"
    const val THERMOMETER = "thermometer"
    const val DROP = "drop"
    const val CALENDAR = "calendar"
    const val WIND = "wind"
    const val HUMIDITY = "humidity"
    const val SUNRISE = "sunrise"
    const val SUNSET = "sunset"
    const val AIR = "air"
    const val SPARKLES = "sparkles"
    const val SLEEP = "sleep"
    const val WALK = "walk"
    const val HEART = "heart"
    const val FLAME = "flame"
    const val RUN = "run"
    const val DISTANCE = "distance"
    const val DUMBBELL = "dumbbell"
    const val LIST = "list"
    const val LOCK = "lock"
    const val REFRESH = "refresh"
    const val REFRESH_ERROR = "refresh_error"
    const val LINK = "link"
    const val RING = "ring"
}

@Serializable
enum class DataSource(val key: String, val displayName: String, val symbol: String) {
    @SerialName("weather") WEATHER("weather", "Weather", Symbols.CLOUD_SUN),
    @SerialName("health") HEALTH("health", "Health Connect", Symbols.HEART),
    @SerialName("oura") OURA("oura", "Oura", Symbols.RING),
    @SerialName("calendar") CALENDAR("calendar", "Calendar", Symbols.CALENDAR);

    companion object {
        fun fromKey(key: String): DataSource? = entries.firstOrNull { it.key == key }
    }
}

/** Instants are stored as milliseconds since the epoch. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

internal val ReadoutJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * One line of the agenda tile. The title is kept only when the user allows
 * event titles; widget privacy projection removes it for discreet widgets.
 */
@Serializable
data class AgendaItem(
    @Serializable(InstantSerializer::class) val startsAt: Instant,
    @Serializable(InstantSerializer::class) val endsAt: Instant,
    val isAllDay: Boolean,
    val title: String? = null,
)

@Serializable
data class DashboardMetric(
    val id: String,
    val source: DataSource,
    val title: String,
    val value: String,
    val detail: String,
    val symbol: String,
    val tint: String,
    // Numeric metadata belongs to the source. Never infer a gauge from a
    // formatted value such as "7h 42m" or "8,420".
    val numericValue: Double? = null,
    val targetValue: Double? = null,
    @Serializable(InstantSerializer::class) val observedAt: Instant? = null,
    /**
     * Calendar title kept only when the user explicitly enables titles.
     * Widget privacy projection must remove it before rendering a discreet widget.
     */
    val privateValue: String? = null,
    /** End of a calendar event, used to show "Now" and to reload when it ends. */
    @Serializable(InstantSerializer::class) val endsAt: Instant? = null,
    /**
     * When the value stops describing the present: midnight for today's
     * steps and forecast, the end time for an event. Cached metrics are
     * dropped from this instant on.
     */
    @Serializable(InstantSerializer::class) val expiresAt: Instant? = null,
    /**
     * The app or device behind the value, such as "Oura" or a watch name.
     * App only: the widget privacy projection drops it.
     */
    val origin: String? = null,
    /** Upcoming events for the agenda tile, soonest first. */
    val items: List<AgendaItem>? = null,
) {
    val originLabel: String
        get() = origin ?: if (source == DataSource.WEATHER) "Open-Meteo" else source.displayName

    fun isExpired(at: Instant): Boolean = expiresAt?.let { !it.isAfter(at) } ?: false

    val displayValue: String get() = privateValue ?: value

    /**
     * A normalized, finite goal or score. Metrics without a meaningful target
     * (sleep duration, heart rate, events) use a text presentation instead.
     */
    val gaugeProgress: Double?
        get() {
            val numeric = numericValue ?: return null
            val target = targetValue ?: return null
            if (!numeric.isFinite() || !target.isFinite() || numeric < 0 || target <= 0) return null
            return minOf(numeric / target, 1.0)
        }

    companion object {
        /** Illustrative data for widget previews and the layout editor only. */
        fun samples(now: Instant = Instant.now()): List<DashboardMetric> {
            fun minutes(value: Long) = now.plusSeconds(value * 60)
            return listOf(
                DashboardMetric("temperature", DataSource.WEATHER, "Now", "14°", "Partly cloudy · 17° / 9°", Symbols.CLOUD_SUN, "orange", numericValue = 14.0),
                DashboardMetric("uv", DataSource.WEATHER, "UV index", "5", "Moderate · peak 6 at 13:00", Symbols.SUN, "yellow", numericValue = 5.0, targetValue = 11.0),
                DashboardMetric("rain", DataSource.WEATHER, "Rain today", "2.4 mm", "60% · most likely 15:00", Symbols.DROP, "blue", numericValue = 2.4),
                DashboardMetric("tomorrow", DataSource.WEATHER, "Tomorrow", "12°", "Rain · 15° / 8°", Symbols.RAIN, "teal", numericValue = 12.0),
                DashboardMetric("feelsLike", DataSource.WEATHER, "Feels like", "12°", "Actual 14°", Symbols.THERMOMETER, "orange", numericValue = 12.0),
                DashboardMetric("wind", DataSource.WEATHER, "Wind", "5 m/s", "from NW · gusts 9 m/s", Symbols.WIND, "teal", numericValue = 5.0),
                DashboardMetric("humidity", DataSource.WEATHER, "Humidity", "62%", "Relative humidity", Symbols.HUMIDITY, "blue", numericValue = 62.0, targetValue = 100.0),
                DashboardMetric("sun", DataSource.WEATHER, "Sunset", "21:48", "Sunrise 05:12", Symbols.SUNSET, "yellow"),
                DashboardMetric("airQuality", DataSource.WEATHER, "Air quality", "18", "Good · PM2.5 4", Symbols.AIR, "green", numericValue = 18.0, targetValue = 100.0),
                DashboardMetric("readiness", DataSource.OURA, "Readiness", "86", "Optimal", Symbols.SPARKLES, "violet", numericValue = 86.0, targetValue = 100.0),
                DashboardMetric("sleep", DataSource.OURA, "Sleep", "7h 42m", "Score 91", Symbols.SLEEP, "indigo"),
                DashboardMetric("steps", DataSource.HEALTH, "Steps", "8,420", "84% of goal", Symbols.WALK, "mint", numericValue = 8420.0, targetValue = 10000.0),
                DashboardMetric("heart", DataSource.HEALTH, "Resting heart rate", "62", "bpm resting", Symbols.HEART, "pink"),
                DashboardMetric("heartRate", DataSource.HEALTH, "Heart rate", "71", "bpm · 14:05", Symbols.HEART, "pink"),
                DashboardMetric("hrv", DataSource.HEALTH, "HRV", "48", "ms · 07:12", Symbols.HEART, "pink"),
                DashboardMetric("activeEnergy", DataSource.HEALTH, "Active energy", "420", "kcal today", Symbols.FLAME, "red", numericValue = 420.0),
                DashboardMetric("exercise", DataSource.HEALTH, "Exercise", "24", "min today · goal 30", Symbols.RUN, "green", numericValue = 24.0, targetValue = 30.0),
                DashboardMetric("distance", DataSource.HEALTH, "Distance", "5.2 km", "Walking and running today", Symbols.DISTANCE, "mint", numericValue = 5200.0),
                DashboardMetric("workout", DataSource.HEALTH, "Last workout", "45m", "Running · 07:30", Symbols.DUMBBELL, "green", numericValue = 2700.0),
                DashboardMetric(
                    "nextEvent", DataSource.CALENDAR, "Up next", "Busy", "", Symbols.CALENDAR, "blue",
                    observedAt = minutes(24), privateValue = "Design sync", endsAt = minutes(54),
                ),
                DashboardMetric(
                    "agenda", DataSource.CALENDAR, "Agenda", "Busy", "", Symbols.LIST, "blue",
                    observedAt = minutes(24),
                    items = listOf(
                        AgendaItem(minutes(24), minutes(54), false, "Design sync"),
                        AgendaItem(minutes(150), minutes(210), false, "Lunch"),
                        AgendaItem(minutes(300), minutes(330), false, "Dentist"),
                    ),
                ),
            )
        }
    }
}
