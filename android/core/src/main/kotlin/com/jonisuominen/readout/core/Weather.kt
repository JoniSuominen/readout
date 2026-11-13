package com.jonisuominen.readout.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A forecast location. Coordinates are rounded to about 1 km; the forecast
 * grid is coarser than that, so no precision is lost.
 */
@Serializable
data class WeatherLocation(
    val name: String,
    val detail: String? = null,
    val latitude: Double,
    val longitude: Double,
    val isCurrentLocation: Boolean,
) {
    val id: String get() = "$latitude,$longitude,$name"

    companion object {
        /** Builds a location with its coordinates rounded. Always use this for new locations. */
        fun of(name: String, detail: String? = null, latitude: Double, longitude: Double, isCurrentLocation: Boolean) =
            WeatherLocation(name, detail, rounded(latitude), rounded(longitude), isCurrentLocation)

        private fun rounded(value: Double): Double = (value * 100).roundToLong() / 100.0
    }
}

class WeatherLocationStore(private val kv: KeyValueStore) {
    /** Null until a city is chosen or the device location is first found. */
    val location: WeatherLocation?
        get() = kv.getString(LOCATION_KEY)?.let {
            try {
                ReadoutJson.decodeFromString(WeatherLocation.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }

    fun save(location: WeatherLocation) =
        kv.putString(LOCATION_KEY, ReadoutJson.encodeToString(WeatherLocation.serializer(), location))

    fun clear() = kv.remove(LOCATION_KEY)

    private companion object {
        const val LOCATION_KEY = "weather.location"
    }
}

data class DeviceCoordinate(val latitude: Double, val longitude: Double)

/**
 * Finds the device location. Asking for permission needs the app on screen;
 * background work reuses the last stored coordinate when no fix is allowed.
 */
interface DeviceLocating {
    /** Asks for location permission if needed, then finds the device. */
    suspend fun authorizedCoordinate(): DeviceCoordinate

    /** Finds the device without prompting. Throws when permission is missing. */
    suspend fun currentCoordinate(): DeviceCoordinate
}

class WeatherConnector(
    private val store: ReadoutStore,
    private val client: OpenMeteoClient = OpenMeteoClient(),
    private val locator: DeviceLocating? = null,
    private val now: () -> Instant = Instant::now,
) : DashboardConnector {
    override val source = DataSource.WEATHER

    override suspend fun authorize() {
        val location = store.weatherLocation.location
        if (location != null && !location.isCurrentLocation) return
        updateCurrentLocation(prompt = true)
    }

    override suspend fun refresh(): List<DashboardMetric> {
        if (locator != null && store.weatherLocation.location?.isCurrentLocation != false) {
            try {
                updateCurrentLocation(prompt = false)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Keep forecasting for the last known coordinate when a new fix fails.
                if (store.weatherLocation.location == null) throw error
            }
        }
        val location = store.weatherLocation.location
            ?: throw ConnectorException.unavailable("Choose a weather location in Sources.")
        val env = DisplayEnvironment.current
        return coroutineScope {
            // Air quality is a separate service; the forecast still shows without it.
            val airQuality = async {
                try {
                    client.airQuality(location.latitude, location.longitude)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    null
                }
            }
            val forecast = client.forecast(location.latitude, location.longitude)
            WeatherSnapshotBuilder.metrics(forecast, airQuality.await(), now(), store.units.selection, env)
        }
    }

    override suspend fun disconnect(): DisconnectResult {
        store.weatherLocation.clear()
        return DisconnectResult.localOnly
    }

    private suspend fun updateCurrentLocation(prompt: Boolean) {
        val locator = locator
            ?: throw ConnectorException.unavailable("Open the app to find your location, or choose a city.")
        val coordinate = if (prompt) locator.authorizedCoordinate() else locator.currentCoordinate()
        store.weatherLocation.save(
            WeatherLocation.of(
                name = "Current location",
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                isCurrentLocation = true,
            ),
        )
    }
}

/**
 * Free Open-Meteo forecast and geocoding APIs. No account or key is needed;
 * requests contain only the rounded coordinate or the search text.
 */
class OpenMeteoClient(private val transport: HttpTransport = UrlConnectionTransport()) {
    suspend fun forecast(latitude: Double, longitude: Double): OpenMeteoForecast {
        val query = queryString(
            listOf(
                "latitude" to coordinate(latitude),
                "longitude" to coordinate(longitude),
                "current" to "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,uv_index,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m",
                "hourly" to "temperature_2m,uv_index,precipitation_probability",
                "daily" to "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,uv_index_max,sunrise,sunset",
                "wind_speed_unit" to "ms",
                "timezone" to "auto",
                "timeformat" to "unixtime",
                "forecast_days" to "2",
            ),
        )
        return decode(OpenMeteoForecast.serializer(), get("https://api.open-meteo.com/v1/forecast?$query"))
    }

    suspend fun airQuality(latitude: Double, longitude: Double): OpenMeteoAirQuality {
        val query = queryString(
            listOf(
                "latitude" to coordinate(latitude),
                "longitude" to coordinate(longitude),
                "current" to "european_aqi,us_aqi,pm2_5",
                "timezone" to "auto",
                "timeformat" to "unixtime",
            ),
        )
        return decode(OpenMeteoAirQuality.serializer(), get("https://air-quality-api.open-meteo.com/v1/air-quality?$query"))
    }

    suspend fun searchPlaces(query: String): List<WeatherLocation> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val parameters = queryString(listOf("name" to trimmed, "count" to "8", "format" to "json"))
        val response = decode(GeocodingResponse.serializer(), get("https://geocoding-api.open-meteo.com/v1/search?$parameters"))
        return response.results.orEmpty().map { place ->
            val detail = listOfNotNull(place.admin1, place.country).joinToString(", ")
            WeatherLocation.of(
                name = place.name,
                detail = detail.ifEmpty { null },
                latitude = place.latitude,
                longitude = place.longitude,
                isCurrentLocation = false,
            )
        }
    }

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private suspend fun get(url: String): String {
        val response = try {
            transport.send(HttpRequest(url, timeoutMillis = 15_000))
        } catch (error: java.io.IOException) {
            throw ConnectorException.unavailable("The weather service could not be reached. Check your connection.")
        }
        if (response.status !in 200..299) {
            throw ConnectorException.unavailable("The weather service is unavailable. Try again later.")
        }
        return response.text
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T = try {
        ReadoutJson.decodeFromString(serializer, text)
    } catch (_: kotlinx.serialization.SerializationException) {
        throw ConnectorException.unavailable("The weather service sent an unexpected reply. Try again later.")
    }

    @Serializable
    private class GeocodingResponse(val results: List<Place>? = null) {
        @Serializable
        class Place(
            val name: String,
            val latitude: Double,
            val longitude: Double,
            val country: String? = null,
            val admin1: String? = null,
        )
    }
}

/**
 * The subset of an Open-Meteo forecast response used for the weather tiles,
 * requested with `timeformat=unixtime`.
 */
@Serializable
data class OpenMeteoForecast(
    val current: Current,
    val hourly: Hourly? = null,
    val daily: Daily,
    /** The location's offset from UTC, sent because the request sets `timezone=auto`. */
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
) {
    @Serializable
    data class Current(
        val time: Double,
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("weather_code") val weatherCode: Double? = null,
        @SerialName("uv_index") val uvIndex: Double? = null,
        @SerialName("is_day") val isDay: Double? = null,
        @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Double? = null,
        /** Meters per second: the request sets `wind_speed_unit=ms`. */
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
        @SerialName("wind_direction_10m") val windDirection: Double? = null,
        @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    )

    @Serializable
    data class Hourly(
        val time: List<Double>,
        @SerialName("temperature_2m") val temperature: List<Double?>? = null,
        @SerialName("uv_index") val uvIndex: List<Double?>? = null,
        @SerialName("precipitation_probability") val precipitationProbability: List<Double?>? = null,
    )

    @Serializable
    data class Daily(
        val time: List<Double>,
        @SerialName("weather_code") val weatherCode: List<Double?>? = null,
        @SerialName("temperature_2m_max") val temperatureMax: List<Double?>? = null,
        @SerialName("temperature_2m_min") val temperatureMin: List<Double?>? = null,
        @SerialName("precipitation_sum") val precipitationSum: List<Double?>? = null,
        @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Double?>? = null,
        @SerialName("uv_index_max") val uvIndexMax: List<Double?>? = null,
        val sunrise: List<Double?>? = null,
        val sunset: List<Double?>? = null,
    )
}

/** The subset of an Open-Meteo air quality response used for its tile. */
@Serializable
data class OpenMeteoAirQuality(val current: Current) {
    @Serializable
    data class Current(
        @SerialName("european_aqi") val europeanAQI: Double? = null,
        @SerialName("us_aqi") val usAQI: Double? = null,
        @SerialName("pm2_5") val pm25: Double? = null,
    )
}

object WeatherSnapshotBuilder {
    private const val DAY_SECONDS = 24 * 60 * 60L

    private class Hour(
        val start: Instant,
        val temperature: Double?,
        val uvIndex: Double?,
        val precipitationProbability: Double?,
    )

    private class SunEvent(val isSunrise: Boolean, val date: Instant)

    fun metrics(
        forecast: OpenMeteoForecast,
        airQuality: OpenMeteoAirQuality? = null,
        now: Instant,
        units: UnitSelection = UnitSelection(),
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): List<DashboardMetric> {
        val locale = env.locale
        // Sunrise, the UV peak, and rain hours are read as the place's own clock
        // times: a city chosen in another time zone is not shifted to the phone's.
        val place = forecast.utcOffsetSeconds
            ?.takeIf { it in -18 * 3600..18 * 3600 }
            ?.let { env.copy(zone = ZoneOffset.ofTotalSeconds(it)) }
            ?: env
        val current = forecast.current
        val daily = forecast.daily
        val observedAt = instant(current.time)
        val todayStart = daily.time.firstOrNull()?.let(::instant) ?: now
        val tomorrowStart = if (daily.time.size > 1) instant(daily.time[1]) else todayStart.plusSeconds(DAY_SECONDS)
        val tomorrowEnd = tomorrowStart.plusSeconds(DAY_SECONDS)
        val hours = forecast.hourly?.let { hourly ->
            hourly.time.mapIndexed { index, time ->
                Hour(
                    start = instant(time),
                    temperature = element(hourly.temperature, index),
                    uvIndex = element(hourly.uvIndex, index),
                    precipitationProbability = element(hourly.precipitationProbability, index),
                )
            }
        }.orEmpty()
        val today = hours.filter { !it.start.isBefore(todayStart) && it.start.isBefore(tomorrowStart) }
        // An hour that has started is still upcoming until it ends.
        val upcomingToday = today.filter { it.start.plusSeconds(3600).isAfter(now) }

        fun temperature(celsius: Double) = WeatherFormat.temperature(celsius, units.temperature, locale)
        fun highLow(high: Double?, low: Double?): String? =
            if (high != null && low != null) "${temperature(high)} / ${temperature(low)}" else null

        val metrics = ArrayList<DashboardMetric>()

        val todayCode = (current.weatherCode ?: element(daily.weatherCode, 0))?.toInt() ?: -1
        current.temperature?.let { value ->
            metrics += DashboardMetric(
                id = "temperature",
                source = DataSource.WEATHER,
                title = "Now",
                value = temperature(value),
                detail = joined(WeatherCode.text(todayCode), highLow(element(daily.temperatureMax, 0), element(daily.temperatureMin, 0))),
                symbol = WeatherCode.symbol(todayCode, isDay = (current.isDay ?: 1.0) >= 1),
                tint = "orange",
                numericValue = value,
                observedAt = observedAt,
                // "Today" and "tomorrow" shift at the location's midnight.
                expiresAt = tomorrowStart,
            )
        }

        current.uvIndex?.let { uv ->
            val level = UVLevel.of(uv)
            val peak = upcomingToday.filter { it.uvIndex != null }.maxByOrNull { it.uvIndex ?: 0.0 }
            val peakValue = peak?.uvIndex
            val todayMax = element(daily.uvIndexMax, 0) ?: today.mapNotNull { it.uvIndex }.maxOrNull()
            val tomorrowMax = element(daily.uvIndexMax, 1)
            val detail = when {
                peak != null && peakValue != null && peakValue >= 0.5 && Math.round(peakValue) > Math.round(uv) ->
                    "${level.label} · peak ${peakValue.roundToInt()} at ${place.clock(peak.start)}"
                uv < 0.5 && tomorrowMax != null -> "${level.label} · tomorrow ${tomorrowMax.roundToInt()}"
                todayMax != null -> "${level.label} · max ${todayMax.roundToInt()} today"
                else -> level.label
            }
            metrics += DashboardMetric(
                id = "uv",
                source = DataSource.WEATHER,
                title = "UV index",
                value = "${uv.roundToInt()}",
                detail = detail,
                symbol = Symbols.SUN,
                tint = level.tint,
                numericValue = uv,
                targetValue = 11.0,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        val rain = element(daily.precipitationSum, 0)
        val rainChance = element(daily.precipitationProbabilityMax, 0)
        if (rain != null || rainChance != null) {
            val amount = rain ?: 0.0
            val detail = if (amount < 0.1 && (rainChance ?: 0.0) < 20) {
                "Dry today"
            } else {
                val likely = upcomingToday
                    .filter { (it.precipitationProbability ?: 0.0) >= 30 }
                    .maxByOrNull { it.precipitationProbability ?: 0.0 }
                joined(
                    rainChance?.let { "${it.roundToInt()}%" },
                    likely?.let { "most likely ${place.clock(it.start)}" },
                )
            }
            metrics += DashboardMetric(
                id = "rain",
                source = DataSource.WEATHER,
                title = "Rain today",
                value = WeatherFormat.precipitation(amount, units.rain, locale),
                detail = detail,
                symbol = Symbols.DROP,
                tint = "blue",
                numericValue = amount,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        val tomorrowTemperatures = hours
            .filter { !it.start.isBefore(tomorrowStart) && it.start.isBefore(tomorrowEnd) }
            .mapNotNull { it.temperature }
        val tomorrowHigh = element(daily.temperatureMax, 1)
        val tomorrowLow = element(daily.temperatureMin, 1)
        val tomorrowAverage = if (tomorrowTemperatures.isEmpty()) {
            if (tomorrowHigh != null && tomorrowLow != null) (tomorrowHigh + tomorrowLow) / 2 else null
        } else {
            tomorrowTemperatures.sum() / tomorrowTemperatures.size
        }
        tomorrowAverage?.let { average ->
            val code = element(daily.weatherCode, 1)?.toInt() ?: -1
            val tomorrowRain = element(daily.precipitationSum, 1)
                ?.takeIf { it >= 0.1 }
                ?.let { WeatherFormat.precipitation(it, units.rain, locale) }
            metrics += DashboardMetric(
                id = "tomorrow",
                source = DataSource.WEATHER,
                title = "Tomorrow",
                value = temperature(average),
                detail = joined(WeatherCode.text(code), highLow(tomorrowHigh, tomorrowLow), tomorrowRain),
                symbol = WeatherCode.symbol(code),
                tint = "teal",
                numericValue = average,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        current.apparentTemperature?.let { feelsLike ->
            metrics += DashboardMetric(
                id = "feelsLike",
                source = DataSource.WEATHER,
                title = "Feels like",
                value = temperature(feelsLike),
                detail = current.temperature?.let { "Actual ${temperature(it)}" } ?: "",
                symbol = Symbols.THERMOMETER,
                tint = "orange",
                numericValue = feelsLike,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        current.windSpeed?.let { wind ->
            val gusts = current.windGusts
                ?.takeIf { it >= wind + 1 }
                ?.let { "gusts ${WeatherFormat.wind(it, units.wind, locale)}" }
            metrics += DashboardMetric(
                id = "wind",
                source = DataSource.WEATHER,
                title = "Wind",
                value = WeatherFormat.wind(wind, units.wind, locale),
                detail = joined(current.windDirection?.let { "from ${WeatherFormat.compass(it)}" }, gusts),
                symbol = Symbols.WIND,
                tint = "teal",
                numericValue = wind,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        current.humidity?.let { humidity ->
            metrics += DashboardMetric(
                id = "humidity",
                source = DataSource.WEATHER,
                title = "Humidity",
                value = "${humidity.roundToInt()}%",
                detail = "Relative humidity",
                symbol = Symbols.HUMIDITY,
                tint = "blue",
                numericValue = humidity,
                targetValue = 100.0,
                observedAt = observedAt,
                expiresAt = tomorrowStart,
            )
        }

        sunMetric(daily, now, observedAt, tomorrowStart, place)?.let { metrics += it }
        airQualityMetric(airQuality, locale, observedAt, tomorrowStart)?.let { metrics += it }

        return metrics
    }

    /** The next sunrise or sunset as the value, the one after it as the detail. */
    private fun sunMetric(
        daily: OpenMeteoForecast.Daily,
        now: Instant,
        observedAt: Instant,
        expiresAt: Instant,
        env: DisplayEnvironment,
    ): DashboardMetric? {
        val events = ArrayList<SunEvent>()
        for (day in 0..1) {
            element(daily.sunrise, day)?.let { events += SunEvent(true, instant(it)) }
            element(daily.sunset, day)?.let { events += SunEvent(false, instant(it)) }
        }
        val upcoming = events.filter { it.date.isAfter(now) }.sortedBy { it.date }
        val next = upcoming.firstOrNull() ?: return null
        val following = upcoming.getOrNull(1)
        return DashboardMetric(
            id = "sun",
            source = DataSource.WEATHER,
            title = if (next.isSunrise) "Sunrise" else "Sunset",
            value = env.clock(next.date),
            detail = following?.let { "${if (it.isSunrise) "Sunrise" else "Sunset"} ${env.clock(it.date)}" } ?: "",
            symbol = if (next.isSunrise) Symbols.SUNRISE else Symbols.SUNSET,
            tint = "yellow",
            observedAt = observedAt,
            expiresAt = expiresAt,
        )
    }

    private fun airQualityMetric(
        airQuality: OpenMeteoAirQuality?,
        locale: Locale,
        observedAt: Instant,
        expiresAt: Instant,
    ): DashboardMetric? {
        val current = airQuality?.current ?: return null
        val usesUSIndex = Regions.usesUSMeasurement(locale)
        val index = (if (usesUSIndex) current.usAQI ?: current.europeanAQI else current.europeanAQI ?: current.usAQI)
            ?: return null
        val isUS = if (usesUSIndex) current.usAQI != null else current.europeanAQI == null
        val level = if (isUS) AirQualityLevel.us(index) else AirQualityLevel.european(index)
        return DashboardMetric(
            id = "airQuality",
            source = DataSource.WEATHER,
            title = "Air quality",
            value = "${index.roundToInt()}",
            detail = joined(level.label, current.pm25?.let { "PM2.5 ${it.roundToInt()}" }),
            symbol = Symbols.AIR,
            tint = level.tint,
            numericValue = index,
            targetValue = if (isUS) 300.0 else 100.0,
            observedAt = observedAt,
            expiresAt = expiresAt,
        )
    }

    private fun instant(seconds: Double): Instant = Instant.ofEpochMilli((seconds * 1000).roundToLong())

    private fun element(values: List<Double?>?, index: Int): Double? = values?.getOrNull(index)

    private fun joined(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotEmpty() && it != "Unknown" }.joinToString(" · ")
}
