package com.jonisuominen.readout.core

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The time zone, region, and clock style values are formatted for. The app
 * installs a provider that follows the phone's 12 or 24 hour setting.
 */
data class DisplayEnvironment(
    val zone: ZoneId,
    val locale: Locale,
    /** Null follows the locale's own convention. */
    val uses24Hour: Boolean? = null,
) {
    /** "14:30" or "2:30 PM". */
    fun clock(instant: Instant): String {
        val time = instant.atZone(zone)
        return when (uses24Hour) {
            true -> DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
            false -> DateTimeFormatter.ofPattern("h:mm a", locale).format(time)
            null -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
        }
    }

    /** "Thu". */
    fun weekday(instant: Instant): String =
        instant.atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** "31 Oct". */
    fun dayAndMonth(instant: Instant): String =
        DateTimeFormatter.ofPattern("d MMM", locale).format(instant.atZone(zone))

    fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    fun startOfDay(instant: Instant): Instant = day(instant).atStartOfDay(zone).toInstant()

    fun startOfNextDay(instant: Instant): Instant = day(instant).plusDays(1).atStartOfDay(zone).toInstant()

    fun number(value: Double, fractionDigits: Int = 0): String {
        val format = NumberFormat.getNumberInstance(locale)
        format.minimumFractionDigits = fractionDigits
        format.maximumFractionDigits = fractionDigits
        return format.format(value)
    }

    companion object {
        @Volatile
        var provider: () -> DisplayEnvironment = {
            DisplayEnvironment(ZoneId.systemDefault(), Locale.getDefault())
        }

        val current: DisplayEnvironment get() = provider()
    }
}

/** Which measurement conventions a region follows. */
object Regions {
    private val fahrenheit = setOf("US", "BS", "BZ", "KY", "PW", "LR", "FM", "MH", "PR", "GU", "VI")
    private val usMeasurement = setOf("US", "LR", "MM")

    fun usesFahrenheit(locale: Locale): Boolean = locale.country in fahrenheit
    fun usesUSMeasurement(locale: Locale): Boolean = locale.country in usMeasurement
    fun usesUKMeasurement(locale: Locale): Boolean = locale.country == "GB"
    fun usesMiles(locale: Locale): Boolean = usesUSMeasurement(locale) || usesUKMeasurement(locale)
}

/**
 * Age of the oldest displayed data in whole minutes. Widgets redraw this
 * label about once a minute instead of running a per-second relative clock.
 */
object UpdatedLabel {
    fun minutes(since: Instant, now: Instant): Int =
        maxOf(0L, (now.toEpochMilli() - since.toEpochMilli()) / 60_000L).toInt()

    /** "0m", "12m", "3h", "2d". */
    fun short(since: Instant, now: Instant): String {
        val elapsed = minutes(since, now)
        return when {
            elapsed < 60 -> "${elapsed}m"
            elapsed < 24 * 60 -> "${elapsed / 60}h"
            else -> "${elapsed / (24 * 60)}d"
        }
    }

    /** "Updated 0 min ago", "Updated 3 h ago". */
    fun long(since: Instant, now: Instant): String {
        val elapsed = minutes(since, now)
        return when {
            elapsed < 60 -> "Updated $elapsed min ago"
            elapsed < 24 * 60 -> "Updated ${elapsed / 60} h ago"
            else -> "Updated ${elapsed / (24 * 60)} d ago"
        }
    }
}

/** When an event happens, computed at display time so it never goes stale. */
object EventTiming {
    /** "14:30" today, "Tomorrow 09:00", or "Thu 09:00" later in the week. */
    fun startText(start: Instant, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): String {
        val time = env.clock(start)
        val startDay = env.day(start)
        val today = env.day(now)
        return when (startDay) {
            today -> time
            today.plusDays(1) -> "Tomorrow $time"
            else -> "${env.weekday(start)} $time"
        }
    }

    fun isOngoing(start: Instant, end: Instant?, now: Instant): Boolean =
        !start.isAfter(now) && (end?.let { now.isBefore(it) } ?: false)

    private fun minutesUntil(start: Instant, now: Instant): Int =
        ceil((start.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()

    /** "in 25m", "in 3h 5m", "in 2d", or "until 15:00" for an event in progress. */
    fun relativeText(start: Instant, end: Instant?, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): String {
        if (end != null && isOngoing(start, end, now)) return "until ${env.clock(end)}"
        val minutes = minutesUntil(start, now)
        return when {
            minutes <= 0 -> "now"
            minutes < 60 -> "in ${minutes}m"
            minutes < 24 * 60 -> {
                val remainder = minutes % 60
                if (remainder == 0) "in ${minutes / 60}h" else "in ${minutes / 60}h ${remainder}m"
            }
            else -> "in ${minutes / (24 * 60)}d"
        }
    }

    /**
     * The single label for a narrow tile: a countdown within the hour,
     * otherwise the start time.
     */
    fun compactText(start: Instant, end: Instant?, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): String {
        if (isOngoing(start, end, now)) return "Now"
        val minutes = minutesUntil(start, now)
        if (minutes in 1..59) return "in ${minutes}m"
        return startText(start, now, env)
    }

    /** The time column of an agenda line: "All day", "Now", "in 25m", "14:30". */
    fun agendaText(item: AgendaItem, now: Instant, env: DisplayEnvironment = DisplayEnvironment.current): String =
        if (item.isAllDay) "All day" else compactText(item.startsAt, item.endsAt, now, env)
}

/**
 * Where a tapped widget tile leads. The widget link carries only the metric
 * id; the app turns it into the app that owns the data.
 */
object TileLink {
    const val SCHEME = "com.jonisuominen.readout"
    const val HOST = "open"

    sealed interface Destination {
        /** Stay in Readout. */
        data object App : Destination

        /** The calendar app, on the event's day. */
        data class Calendar(val day: Instant) : Destination

        /** Health Connect, which owns the health records. */
        data object Health : Destination
    }

    fun uri(metricID: String): String? =
        if (MetricCatalog.descriptor(metricID) == null) null else "$SCHEME://$HOST/$metricID"

    /** The metric a widget link names, or null for any other link. */
    fun metricID(scheme: String?, host: String?, pathSegments: List<String>): String? {
        if (scheme != SCHEME || host != HOST) return null
        val parts = pathSegments.filter { it.isNotEmpty() && it != "/" }
        if (parts.size != 1 || MetricCatalog.descriptor(parts[0]) == null) return null
        return parts[0]
    }

    fun destination(
        metricID: String,
        metrics: List<DashboardMetric>,
        ouraMethod: OuraConnectionMethod,
        now: Instant = Instant.now(),
    ): Destination {
        val descriptor = MetricCatalog.descriptor(metricID) ?: return Destination.App
        return when (descriptor.source) {
            DataSource.CALENDAR -> Destination.Calendar(metrics.firstOrNull { it.id == metricID }?.observedAt ?: now)
            DataSource.HEALTH -> Destination.Health
            DataSource.OURA -> if (ouraMethod == OuraConnectionMethod.HEALTH_CONNECT) Destination.Health else Destination.App
            DataSource.WEATHER -> Destination.App
        }
    }
}

/** WHO UV index categories. */
enum class UVLevel(val label: String, val tint: String) {
    LOW("Low", "green"),
    MODERATE("Moderate", "yellow"),
    HIGH("High", "orange"),
    VERY_HIGH("Very high", "red"),
    EXTREME("Extreme", "violet");

    companion object {
        fun of(index: Double): UVLevel = when {
            index < 2.5 -> LOW
            index < 5.5 -> MODERATE
            index < 7.5 -> HIGH
            index < 10.5 -> VERY_HIGH
            else -> EXTREME
        }
    }
}

/** WMO weather interpretation codes used by Open-Meteo. */
object WeatherCode {
    fun symbol(code: Int, isDay: Boolean = true): String = when (code) {
        0, 1 -> if (isDay) Symbols.SUN else Symbols.MOON
        2 -> if (isDay) Symbols.CLOUD_SUN else Symbols.CLOUD_MOON
        3 -> Symbols.CLOUD
        45, 48 -> Symbols.FOG
        51, 53, 55 -> Symbols.DRIZZLE
        56, 57, 66, 67 -> Symbols.SLEET
        61, 63, 65 -> Symbols.RAIN
        71, 73, 75, 77, 85, 86 -> Symbols.SNOW
        80, 81, 82 -> Symbols.HEAVY_RAIN
        95, 96, 99 -> Symbols.THUNDER
        else -> Symbols.CLOUD
    }

    fun text(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }
}

enum class TemperatureUnit(val key: String, val displayName: String) {
    AUTOMATIC("automatic", "Automatic"),
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    fun resolved(locale: Locale): TemperatureUnit =
        if (this != AUTOMATIC) this else if (Regions.usesFahrenheit(locale)) FAHRENHEIT else CELSIUS
}

enum class RainUnit(val key: String, val displayName: String) {
    AUTOMATIC("automatic", "Automatic"),
    MILLIMETERS("millimeters", "mm"),
    INCHES("inches", "in");

    fun resolved(locale: Locale): RainUnit =
        if (this != AUTOMATIC) this else if (Regions.usesUSMeasurement(locale)) INCHES else MILLIMETERS
}

enum class WindUnit(val key: String, val displayName: String) {
    AUTOMATIC("automatic", "Automatic"),
    METERS_PER_SECOND("metersPerSecond", "m/s"),
    KILOMETERS_PER_HOUR("kilometersPerHour", "km/h"),
    MILES_PER_HOUR("milesPerHour", "mph");

    fun resolved(locale: Locale): WindUnit {
        if (this != AUTOMATIC) return this
        if (Regions.usesMiles(locale)) return MILES_PER_HOUR
        if (locale.country in metersPerSecondRegions) return METERS_PER_SECOND
        return KILOMETERS_PER_HOUR
    }

    companion object {
        /**
         * Regions whose forecasts give wind in m/s; elsewhere it is km/h, or mph
         * where miles are used.
         */
        val metersPerSecondRegions = setOf("CN", "DK", "FI", "JP", "KR", "NO", "PL", "RU", "SE")
    }
}

/** The unit choices in effect for one fetch. */
data class UnitSelection(
    val temperature: TemperatureUnit = TemperatureUnit.AUTOMATIC,
    val rain: RainUnit = RainUnit.AUTOMATIC,
    val wind: WindUnit = WindUnit.AUTOMATIC,
)

/**
 * Unit choices live beside the cached values: weather is formatted when it is
 * fetched. Automatic follows the phone's region.
 */
class UnitPreferences(private val kv: KeyValueStore) {
    var temperature: TemperatureUnit
        get() = TemperatureUnit.entries.firstOrNull { it.key == kv.getString(TEMPERATURE_KEY) } ?: TemperatureUnit.AUTOMATIC
        set(value) = kv.putString(TEMPERATURE_KEY, value.key)

    var rain: RainUnit
        get() = RainUnit.entries.firstOrNull { it.key == kv.getString(RAIN_KEY) } ?: RainUnit.AUTOMATIC
        set(value) = kv.putString(RAIN_KEY, value.key)

    var wind: WindUnit
        get() = WindUnit.entries.firstOrNull { it.key == kv.getString(WIND_KEY) } ?: WindUnit.AUTOMATIC
        set(value) = kv.putString(WIND_KEY, value.key)

    val selection: UnitSelection get() = UnitSelection(temperature, rain, wind)

    fun reset() {
        listOf(TEMPERATURE_KEY, RAIN_KEY, WIND_KEY).forEach(kv::remove)
    }

    companion object {
        const val TEMPERATURE_KEY = "units.temperature"
        const val RAIN_KEY = "units.rain"
        const val WIND_KEY = "units.wind"
    }
}

/**
 * Air quality index bands: the European index, or the US index where the
 * US measurement system is used.
 */
data class AirQualityLevel(val label: String, val tint: String) {
    companion object {
        fun european(index: Double): AirQualityLevel = when {
            index < 20 -> AirQualityLevel("Good", "green")
            index < 40 -> AirQualityLevel("Fair", "mint")
            index < 60 -> AirQualityLevel("Moderate", "yellow")
            index < 80 -> AirQualityLevel("Poor", "orange")
            index < 100 -> AirQualityLevel("Very poor", "red")
            else -> AirQualityLevel("Extremely poor", "violet")
        }

        fun us(index: Double): AirQualityLevel = when {
            index <= 50 -> AirQualityLevel("Good", "green")
            index <= 100 -> AirQualityLevel("Moderate", "yellow")
            index <= 150 -> AirQualityLevel("Unhealthy for sensitive groups", "orange")
            index <= 200 -> AirQualityLevel("Unhealthy", "red")
            index <= 300 -> AirQualityLevel("Very unhealthy", "violet")
            else -> AirQualityLevel("Hazardous", "violet")
        }
    }
}

object WeatherFormat {
    /** "14°", in the chosen scale. */
    fun temperature(celsius: Double, unit: TemperatureUnit = TemperatureUnit.AUTOMATIC, locale: Locale = Locale.getDefault()): String {
        val value = if (unit.resolved(locale) == TemperatureUnit.FAHRENHEIT) celsius * 9 / 5 + 32 else celsius
        // Avoid "-0°" for values that round to zero.
        val rounded = if (abs(value) < 0.5) 0L else value.roundToLong()
        return "$rounded°"
    }

    /** "5 m/s", "18 km/h", or "11 mph". */
    fun wind(metersPerSecond: Double, unit: WindUnit = WindUnit.AUTOMATIC, locale: Locale = Locale.getDefault()): String {
        val speed = maxOf(0.0, metersPerSecond)
        return when (unit.resolved(locale)) {
            WindUnit.KILOMETERS_PER_HOUR -> "${(speed * 3.6).roundToInt()} km/h"
            WindUnit.MILES_PER_HOUR -> "${(speed * 2.23694).roundToInt()} mph"
            else -> "${speed.roundToInt()} m/s"
        }
    }

    /** "N", "NE", … for the direction the wind blows from. */
    fun compass(degrees: Double): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((degrees % 360) + 360) % 360
        return points[(normalized / 45).roundToInt() % points.size]
    }

    fun precipitation(millimeters: Double, unit: RainUnit = RainUnit.AUTOMATIC, locale: Locale = Locale.getDefault()): String {
        val env = DisplayEnvironment(ZoneId.of("UTC"), locale)
        if (unit.resolved(locale) == RainUnit.INCHES) {
            val inches = millimeters / 25.4
            return "${env.number(inches, if (inches < 1) 2 else 1)} in"
        }
        return "${env.number(millimeters, if (millimeters < 10) 1 else 0)} mm"
    }
}

object HealthFormat {
    /** "5.2 km" or "3.2 mi", following the phone's region. */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String {
        val env = DisplayEnvironment(ZoneId.of("UTC"), locale)
        val safe = maxOf(0.0, meters)
        return if (Regions.usesMiles(locale)) {
            "${env.number(safe / 1609.344, 1)} mi"
        } else {
            "${env.number(safe / 1000, 1)} km"
        }
    }

    /** "45m" or "1h 5m". */
    fun duration(seconds: Double): String {
        val minutes = (maxOf(0.0, seconds) / 60).roundToInt()
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }

    /** "7h 42m", "8h", or "45m": a sleep total without zero parts. */
    fun sleepDuration(seconds: Double): String {
        val minutes = (maxOf(0.0, seconds) / 60).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "${rest}m"
            rest == 0 -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    /** "Fitbit", "Fitbit and Oura", "Fitbit, Oura, and Samsung Health". */
    fun list(names: List<String>): String? {
        val sorted = names.filter { it.isNotBlank() }.distinct().sorted()
        return when (sorted.size) {
            0 -> null
            1 -> sorted[0]
            2 -> "${sorted[0]} and ${sorted[1]}"
            else -> sorted.dropLast(1).joinToString(", ") + ", and " + sorted.last()
        }
    }
}
