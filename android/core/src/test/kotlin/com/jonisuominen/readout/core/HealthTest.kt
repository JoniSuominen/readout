package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthTest {
    @Test
    fun dailyTotalsExpireAtMidnightAndNeverInventAValue() {
        val steps = HealthSnapshotBuilder.dailyTotal(HealthSnapshotBuilder.steps, 8420.4, listOf("Fitbit", "Pixel Watch"), baseNow, testEnv)
        assertEquals("8,420", steps.value)
        assertEquals("Today · goal 10,000", steps.detail)
        assertEquals(10_000.0, steps.targetValue)
        assertEquals(java.time.Instant.parse("2026-06-16T00:00:00Z"), steps.expiresAt)
        assertEquals("Fitbit and Pixel Watch", steps.origin)
        assertEquals(baseNow, steps.observedAt)

        val empty = HealthSnapshotBuilder.dailyTotal(HealthSnapshotBuilder.exercise, null, emptyList(), baseNow, testEnv)
        assertEquals("—", empty.value)
        assertEquals("No exercise minutes recorded today", empty.detail)
        assertNull(empty.numericValue)
        assertNull(empty.observedAt)
        assertNull(empty.origin)

        assertEquals("5.2 km", HealthSnapshotBuilder.dailyTotal(HealthSnapshotBuilder.distance, 5200.0, emptyList(), baseNow, DisplayEnvironment(utc, java.util.Locale.forLanguageTag("en-IE"), true)).value)
    }

    @Test
    fun heartAndWorkoutShowAClockTimeTodayAndADateBefore() {
        val today = HealthSnapshotBuilder.heart(HealthSnapshotBuilder.heartRate, 71.4, baseNow.plusMinutes(-55), "Pixel Watch", baseNow, testEnv)
        assertEquals("71", today.value)
        assertEquals("bpm · 09:05", today.detail)
        assertEquals("Pixel Watch", today.origin)
        val older = HealthSnapshotBuilder.heart(HealthSnapshotBuilder.heartRateVariability, 48.0, baseNow.plusMinutes(-3 * 1440), null, baseNow, testEnv)
        assertEquals("ms · 12 Jun", older.detail)
        val none = HealthSnapshotBuilder.heart(HealthSnapshotBuilder.restingHeartRate, null, null, null, baseNow, testEnv)
        assertEquals("—", none.value)
        assertEquals("No measurement found", none.detail)

        val workout = HealthSnapshotBuilder.workout(2700.0, "Running", baseNow.plusMinutes(-150), "Strava", baseNow, testEnv)
        assertEquals("45m", workout.value)
        assertEquals("Running · 07:30", workout.detail)
        assertEquals("No workout found", HealthSnapshotBuilder.workout(null, null, null, null, baseNow, testEnv).detail)
    }

    @Test
    fun sleepCountsOverlapsOnceAndOnlyTheLatestSession() {
        fun interval(startMinutes: Long, endMinutes: Long) = baseNow.plusMinutes(startMinutes) to baseNow.plusMinutes(endMinutes)
        assertEquals(0.0, SleepIntervals.totalSeconds(emptyList()), 0.0)
        assertEquals(90 * 60.0, SleepIntervals.totalSeconds(listOf(interval(0, 60), interval(30, 90))), 0.0)
        assertEquals(
            "A short awake gap is not asleep time",
            100 * 60.0, SleepIntervals.totalSeconds(listOf(interval(70, 110), interval(0, 60))), 0.0,
        )
        assertEquals(
            "Two nights in the window are never added together",
            400 * 60.0,
            SleepIntervals.totalSeconds(listOf(interval(0, 480), interval(1440, 1640), interval(1650, 1850))), 0.0,
        )

        val metric = HealthSnapshotBuilder.ouraSleep(listOf(interval(0, 462)), "Oura")
        assertEquals("7h 42m", metric.value)
        assertEquals(DataSource.OURA, metric.source)
        assertEquals("Oura via Health Connect", metric.origin)
        assertEquals("—", HealthSnapshotBuilder.ouraSleep(emptyList(), null).value)
        assertNull(HealthSnapshotBuilder.ouraSleep(emptyList(), null).origin)
    }
}
