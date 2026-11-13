package com.jonisuominen.readout.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherTest {
    private val midnight: Long = Instant.parse("2026-06-15T00:00:00Z").epochSecond
    private val day = 86_400L

    private fun hourly(uv: (Int) -> Double, rain: (Int) -> Double, temperature: (Int) -> Double): String {
        val hours = 0 until 48
        return """{"time":[${hours.joinToString(",") { (midnight + it * 3600L).toString() }}],
            "temperature_2m":[${hours.joinToString(",") { temperature(it).toString() }}],
            "uv_index":[${hours.joinToString(",") { uv(it).toString() }}],
            "precipitation_probability":[${hours.joinToString(",") { rain(it).toString() }}]}"""
    }

    private fun forecastJSON(
        nowOffsetSeconds: Long = 10 * 3600L,
        currentUV: Double = 4.2,
        isDay: Int = 1,
        rainSum: Double = 2.4,
        rainChance: Double = 60.0,
    ) = """{
        "current":{"time":${midnight + nowOffsetSeconds},"temperature_2m":14.4,"apparent_temperature":12.1,
          "relative_humidity_2m":62.4,"weather_code":2,"uv_index":$currentUV,"is_day":$isDay,
          "wind_speed_10m":5.2,"wind_direction_10m":315,"wind_gusts_10m":9.4},
        "hourly":${hourly(
        uv = { if (it % 24 == 13) 6.4 else if (it % 24 in 8..18) 3.0 else 0.0 },
        rain = { if (it == 15) 70.0 else 10.0 },
        temperature = { if (it < 24) 14.0 else 12.0 },
    )},
        "daily":{"time":[$midnight,${midnight + day}],"weather_code":[2,61],
          "temperature_2m_max":[17.2,15.1],"temperature_2m_min":[9.3,8.2],
          "precipitation_sum":[$rainSum,5.5],"precipitation_probability_max":[$rainChance,80],
          "uv_index_max":[6.4,7.2],
          "sunrise":[${midnight + 4 * 3600},${midnight + day + 4 * 3600}],
          "sunset":[${midnight + 21 * 3600},${midnight + day + 21 * 3600}]}
    }"""

    private fun decode(json: String) = ReadoutJson.decodeFromString(OpenMeteoForecast.serializer(), json)

    @Test
    fun forecastBuildsTemperatureUVRainAndTomorrow() {
        val metrics = WeatherSnapshotBuilder.metrics(decode(forecastJSON()), null, baseNow, UnitSelection(), testEnv)
            .associateBy { it.id }
        val temperature = metrics.getValue("temperature")
        assertEquals("14°", temperature.value)
        assertEquals("Partly cloudy · 17° / 9°", temperature.detail)
        assertEquals(Symbols.CLOUD_SUN, temperature.symbol)
        assertEquals(Instant.ofEpochSecond(midnight + day), temperature.expiresAt)

        val uv = metrics.getValue("uv")
        assertEquals("4", uv.value)
        assertEquals("Moderate · peak 6 at 13:00", uv.detail)
        assertEquals("yellow", uv.tint)
        assertEquals(11.0, uv.targetValue)

        val rain = metrics.getValue("rain")
        assertEquals("2.4 mm", rain.value)
        assertEquals("60% · most likely 15:00", rain.detail)

        val tomorrow = metrics.getValue("tomorrow")
        assertEquals("12°", tomorrow.value)
        assertEquals("Rain · 15° / 8° · 5.5 mm", tomorrow.detail)
        assertEquals(Symbols.RAIN, tomorrow.symbol)
    }

    @Test
    fun currentConditionsAddFeelsLikeWindHumiditySunAndAirQuality() {
        val air = OpenMeteoAirQuality(OpenMeteoAirQuality.Current(europeanAQI = 18.2, usAQI = 40.0, pm25 = 4.4))
        val metrics = WeatherSnapshotBuilder.metrics(decode(forecastJSON()), air, baseNow, UnitSelection(), testEnv)
        assertEquals(
            listOf("temperature", "uv", "rain", "tomorrow", "feelsLike", "wind", "humidity", "sun", "airQuality"),
            metrics.map { it.id },
        )
        val byID = metrics.associateBy { it.id }
        assertEquals("12°", byID.getValue("feelsLike").value)
        assertEquals("Actual 14°", byID.getValue("feelsLike").detail)
        assertEquals("12 mph", byID.getValue("wind").value)
        assertEquals("from NW · gusts 21 mph", byID.getValue("wind").detail)
        assertEquals("62%", byID.getValue("humidity").value)
        assertEquals("Sunset", byID.getValue("sun").title)
        assertEquals("21:00", byID.getValue("sun").value)
        assertEquals("Sunrise 04:00", byID.getValue("sun").detail)
        assertEquals("18", byID.getValue("airQuality").value)
        assertEquals("Good · PM2.5 4", byID.getValue("airQuality").detail)
        assertEquals(100.0, byID.getValue("airQuality").targetValue)

        val us = WeatherSnapshotBuilder.metrics(decode(forecastJSON()), air, baseNow, UnitSelection(), usEnv).associateBy { it.id }
        assertEquals("40", us.getValue("airQuality").value)
        assertEquals(300.0, us.getValue("airQuality").targetValue)
        assertEquals("58°", us.getValue("temperature").value)
        assertEquals("0.09 in", us.getValue("rain").value)
        assertEquals("9:00 PM", us.getValue("sun").value)
        assertTrue(metrics.all { it.source == DataSource.WEATHER })
    }

    @Test
    fun nightUVPointsToTomorrowAndDryDaysSaySo() {
        val night = baseNow.plusMinutes(12 * 60)
        val metrics = WeatherSnapshotBuilder.metrics(
            decode(forecastJSON(nowOffsetSeconds = 22 * 3600L, currentUV = 0.0, isDay = 0, rainSum = 0.0, rainChance = 5.0)),
            null, night, UnitSelection(), testEnv,
        ).associateBy { it.id }
        assertEquals("Low · tomorrow 7", metrics.getValue("uv").detail)
        assertEquals("Dry today", metrics.getValue("rain").detail)
        assertEquals(Symbols.CLOUD_MOON, metrics.getValue("temperature").symbol)
        assertEquals("Sunrise", metrics.getValue("sun").title)
    }

    @Test
    fun clockTimesFollowTheChosenPlaceNotThePhone() {
        // The same instants, for a city three hours ahead of the phone's UTC clock.
        val json = forecastJSON().replaceFirst("{", """{"utc_offset_seconds":10800,""")
        val metrics = WeatherSnapshotBuilder.metrics(decode(json), null, baseNow, UnitSelection(), testEnv).associateBy { it.id }
        assertEquals("00:00", metrics.getValue("sun").value)
        assertEquals("Sunrise 07:00", metrics.getValue("sun").detail)
        assertEquals("Moderate · peak 6 at 16:00", metrics.getValue("uv").detail)
        assertEquals("60% · most likely 18:00", metrics.getValue("rain").detail)
        val absurd = forecastJSON().replaceFirst("{", """{"utc_offset_seconds":999999,""")
        assertEquals(
            "21:00",
            WeatherSnapshotBuilder.metrics(decode(absurd), null, baseNow, UnitSelection(), testEnv).first { it.id == "sun" }.value,
        )
    }

    @Test
    fun missingValuesAreLeftOutInsteadOfInvented() {
        val json = """{"current":{"time":$midnight},"daily":{"time":[$midnight]}}"""
        assertTrue(WeatherSnapshotBuilder.metrics(decode(json), null, baseNow, UnitSelection(), testEnv).isEmpty())
    }

    @Test
    fun storedUnitChoicesAreUsed() {
        val units = UnitSelection(TemperatureUnit.FAHRENHEIT, RainUnit.INCHES, WindUnit.KILOMETERS_PER_HOUR)
        val metrics = WeatherSnapshotBuilder.metrics(decode(forecastJSON()), null, baseNow, units, testEnv).associateBy { it.id }
        assertEquals("58°", metrics.getValue("temperature").value)
        assertEquals("0.09 in", metrics.getValue("rain").value)
        assertEquals("19 km/h", metrics.getValue("wind").value)
    }

    @Test
    fun clientSendsOnlyRoundedCoordinatesAndParsesPlaces() = runTest {
        val urls = ArrayList<String>()
        val client = OpenMeteoClient { request ->
            urls += request.url
            when {
                request.url.contains("geocoding") -> HttpResponse(
                    200,
                    """{"results":[{"name":"Helsinki","latitude":60.16952,"longitude":24.93545,"country":"Finland","admin1":"Uusimaa"},
                        {"name":"Nowhere","latitude":1,"longitude":2}]}""".toByteArray(),
                )
                request.url.contains("air-quality") -> HttpResponse(200, """{"current":{"european_aqi":18}}""".toByteArray())
                else -> HttpResponse(200, forecastJSON().toByteArray())
            }
        }
        client.forecast(60.17, 24.94)
        assertTrue(urls[0].startsWith("https://api.open-meteo.com/v1/forecast?latitude=60.17&longitude=24.94&"))
        assertTrue(urls[0].contains("timeformat=unixtime"))
        assertEquals(18.0, client.airQuality(60.17, 24.94).current.europeanAQI)

        val places = client.searchPlaces(" Hel ")
        assertTrue(urls.last().contains("name=Hel&"))
        assertEquals("Helsinki", places[0].name)
        assertEquals("Uusimaa, Finland", places[0].detail)
        assertEquals(60.17, places[0].latitude, 0.0)
        assertNull(places[1].detail)
        val before = urls.size
        assertTrue(client.searchPlaces("H").isEmpty())
        assertEquals("One letter is not searched", before, urls.size)
    }

    @Test
    fun connectorKeepsTheLastCoordinateWhenANewFixFails() = runTest {
        val store = ReadoutStore(InMemoryKeyValueStore()) { baseNow }
        val client = OpenMeteoClient { request ->
            if (request.url.contains("air-quality")) HttpResponse(500, ByteArray(0)) else HttpResponse(200, forecastJSON().toByteArray())
        }
        var fails = false
        val locator = object : DeviceLocating {
            override suspend fun authorizedCoordinate() = currentCoordinate()
            override suspend fun currentCoordinate(): DeviceCoordinate {
                if (fails) throw ConnectorException.authorizationDenied("No location")
                return DeviceCoordinate(60.169857, 24.938379)
            }
        }
        val connector = WeatherConnector(store, client, locator) { baseNow }
        connector.authorize()
        assertEquals(60.17, store.weatherLocation.location!!.latitude, 0.0)
        assertTrue("Air quality failing does not fail the forecast", connector.refresh().any { it.id == "temperature" })
        fails = true
        assertTrue(connector.refresh().isNotEmpty())

        store.weatherLocation.clear()
        assertThrows(ConnectorException::class.java) { kotlinx.coroutines.runBlocking { connector.refresh() } }
        val background = WeatherConnector(store, client, null) { baseNow }
        assertThrows(ConnectorException::class.java) { kotlinx.coroutines.runBlocking { background.refresh() } }

        store.weatherLocation.save(WeatherLocation.of("Oslo", latitude = 59.91, longitude = 10.75, isCurrentLocation = false))
        connector.authorize()
        assertEquals("A chosen city needs no location permission", "Oslo", store.weatherLocation.location!!.name)
        assertTrue(connector.refresh().isNotEmpty())
        assertEquals("Oslo", store.weatherLocation.location!!.name)
        assertEquals(DisconnectResult.localOnly, connector.disconnect())
        assertNull(store.weatherLocation.location)
    }
}
