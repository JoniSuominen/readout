package com.jonisuominen.readout.core

import java.time.Instant

/**
 * Builds health tiles from plain readings, so the Health Connect code only
 * fetches. A missing reading shows "—", never a sample value.
 */
object HealthSnapshotBuilder {
    const val STEP_GOAL = 10_000.0
    const val EXERCISE_GOAL_MINUTES = 30.0

    /** A total summed since midnight. */
    class DailyTotal(
        val id: String,
        val title: String,
        val symbol: String,
        val tint: String,
        val target: Double?,
        val emptyDetail: String,
        val detail: String,
        val value: (Double, DisplayEnvironment) -> String,
    )

    val steps = DailyTotal(
        "steps", "Steps", Symbols.WALK, "mint", STEP_GOAL,
        "No step data available", "Today · goal 10,000",
    ) { value, env -> env.number(value) }

    val activeEnergy = DailyTotal(
        "activeEnergy", "Active energy", Symbols.FLAME, "red", null,
        "No active energy recorded today", "kcal today",
    ) { value, env -> env.number(value) }

    val exercise = DailyTotal(
        "exercise", "Exercise", Symbols.RUN, "green", EXERCISE_GOAL_MINUTES,
        "No exercise minutes recorded today", "min today · goal 30",
    ) { value, env -> env.number(value) }

    val distance = DailyTotal(
        "distance", "Distance", Symbols.DISTANCE, "mint", null,
        "No distance recorded today", "Walking and running today",
    ) { value, env -> HealthFormat.distance(value, env.locale) }

    /** A latest-sample heart reading. */
    class HeartReading(val id: String, val title: String, val unitLabel: String)

    val restingHeartRate = HeartReading("heart", "Resting heart rate", "bpm")
    val heartRate = HeartReading("heartRate", "Heart rate", "bpm")
    val heartRateVariability = HeartReading("hrv", "HRV", "ms")

    fun dailyTotal(
        total: DailyTotal,
        sum: Double?,
        origins: List<String>,
        now: Instant,
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): DashboardMetric = DashboardMetric(
        id = total.id,
        source = DataSource.HEALTH,
        title = total.title,
        value = sum?.let { total.value(it, env) } ?: "—",
        detail = if (sum == null) total.emptyDetail else total.detail,
        symbol = total.symbol,
        tint = total.tint,
        numericValue = sum,
        targetValue = total.target,
        observedAt = if (sum == null) null else now,
        // Today's total must not be shown as tomorrow's.
        expiresAt = env.startOfNextDay(now),
        // Health Connect merges overlapping samples; name every app that counted.
        origin = HealthFormat.list(origins),
    )

    fun heart(
        reading: HeartReading,
        value: Double?,
        measuredAt: Instant?,
        origin: String?,
        now: Instant,
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): DashboardMetric = DashboardMetric(
        id = reading.id,
        source = DataSource.HEALTH,
        title = reading.title,
        value = value?.let { env.number(it) } ?: "—",
        detail = if (value != null && measuredAt != null) {
            "${reading.unitLabel} · ${sampleTime(measuredAt, now, env)}"
        } else {
            "No measurement found"
        },
        symbol = Symbols.HEART,
        tint = "pink",
        numericValue = value,
        observedAt = if (value == null) null else measuredAt,
        origin = origin,
    )

    fun workout(
        durationSeconds: Double?,
        name: String?,
        endedAt: Instant?,
        origin: String?,
        now: Instant,
        env: DisplayEnvironment = DisplayEnvironment.current,
    ): DashboardMetric = DashboardMetric(
        id = "workout",
        source = DataSource.HEALTH,
        title = "Last workout",
        value = durationSeconds?.let(HealthFormat::duration) ?: "—",
        detail = if (durationSeconds != null && endedAt != null) {
            "${name ?: "Workout"} · ${sampleTime(endedAt, now, env)}"
        } else {
            "No workout found"
        },
        symbol = Symbols.DUMBBELL,
        tint = "green",
        numericValue = durationSeconds,
        observedAt = if (durationSeconds == null) null else endedAt,
        origin = origin,
    )

    /** Oura sleep read through Health Connect: time asleep in the latest session. */
    fun ouraSleep(intervals: List<Pair<Instant, Instant>>, originName: String?): DashboardMetric {
        val asleep = SleepIntervals.totalSeconds(intervals)
        return DashboardMetric(
            id = "sleep",
            source = DataSource.OURA,
            title = "Oura sleep",
            value = if (asleep > 0) HealthFormat.sleepDuration(asleep) else "—",
            detail = if (asleep > 0) "Synced through Health Connect" else "No Oura sleep data found",
            symbol = Symbols.SLEEP,
            tint = "indigo",
            numericValue = if (asleep > 0) asleep else null,
            observedAt = intervals.maxOfOrNull { it.second },
            origin = if (intervals.isEmpty()) null else "${originName ?: "Oura"} via Health Connect",
        )
    }

    /**
     * A clock time today, else a date. Unlike "5 min ago", it stays correct
     * while the widget shows a cached value.
     */
    fun sampleTime(date: Instant, now: Instant, env: DisplayEnvironment): String =
        if (env.day(date) == env.day(now)) env.clock(date) else env.dayAndMonth(date)
}

object SleepIntervals {
    private const val SESSION_GAP_SECONDS = 2 * 60 * 60L

    /**
     * Seconds covered by the intervals, counting overlaps once. A gap of more
     * than two hours starts a new session, and only the latest one counts, so
     * two nights inside the query window are never added together.
     */
    fun totalSeconds(intervals: List<Pair<Instant, Instant>>): Double {
        val sorted = intervals.filter { it.second.isAfter(it.first) }.sortedBy { it.first }
        if (sorted.isEmpty()) return 0.0
        var currentStart = sorted[0].first
        var currentEnd = sorted[0].second
        var total = 0L
        for ((start, end) in sorted.drop(1)) {
            if (!start.isAfter(currentEnd)) {
                if (end.isAfter(currentEnd)) currentEnd = end
            } else {
                if (start.epochSecond - currentEnd.epochSecond > SESSION_GAP_SECONDS) {
                    total = 0
                } else {
                    total += currentEnd.epochSecond - currentStart.epochSecond
                }
                currentStart = start
                currentEnd = end
            }
        }
        return (total + currentEnd.epochSecond - currentStart.epochSecond).toDouble()
    }
}
