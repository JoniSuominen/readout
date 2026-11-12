package com.jonisuominen.readout.core

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

val utc: ZoneId = ZoneId.of("UTC")
val testEnv = DisplayEnvironment(utc, Locale.UK, uses24Hour = true)
val usEnv = DisplayEnvironment(utc, Locale.US, uses24Hour = false)

/** 2026-06-15T10:00:00Z, a Monday. */
val baseNow: Instant = Instant.parse("2026-06-15T10:00:00Z")

fun Instant.plusMinutes(minutes: Long): Instant = plusSeconds(minutes * 60)

class MutableClock(var instant: Instant = baseNow) : () -> Instant {
    override fun invoke(): Instant = instant
    fun advanceMinutes(minutes: Long) {
        instant = instant.plusMinutes(minutes)
    }
}

fun metric(
    id: String,
    source: DataSource,
    value: String = "1",
    expiresAt: Instant? = null,
    observedAt: Instant? = null,
    privateValue: String? = null,
) = DashboardMetric(
    id = id, source = source, title = id, value = value, detail = "detail", symbol = Symbols.HEART, tint = "pink",
    numericValue = 1.0, observedAt = observedAt, privateValue = privateValue, expiresAt = expiresAt,
)

class FakeConnector(
    override val source: DataSource,
    var metrics: List<DashboardMetric> = listOf(metric(defaultID(source), source)),
) : DashboardConnector {
    var authorizeCalls = 0
    var refreshCalls = 0
    var disconnectCalls = 0
    var refreshError: Exception? = null
    var authorizeError: Exception? = null
    var disconnectResult = DisconnectResult.localOnly
    var beforeAuthorizeReturns: suspend () -> Unit = {}
    var beforeRefreshReturns: suspend () -> Unit = {}

    override suspend fun authorize() {
        authorizeCalls++
        beforeAuthorizeReturns()
        authorizeError?.let { throw it }
    }

    override suspend fun refresh(): List<DashboardMetric> {
        refreshCalls++
        beforeRefreshReturns()
        refreshError?.let { throw it }
        return metrics
    }

    override suspend fun disconnect(): DisconnectResult {
        disconnectCalls++
        return disconnectResult
    }

    companion object {
        fun defaultID(source: DataSource) = when (source) {
            DataSource.WEATHER -> "temperature"
            DataSource.HEALTH -> "steps"
            DataSource.OURA -> "sleep"
            DataSource.CALENDAR -> "nextEvent"
        }
    }
}
