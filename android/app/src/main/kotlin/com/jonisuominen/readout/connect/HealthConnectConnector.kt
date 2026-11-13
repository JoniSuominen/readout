package com.jonisuominen.readout.connect

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.jonisuominen.readout.core.ConnectorException
import com.jonisuominen.readout.core.DashboardConnector
import com.jonisuominen.readout.core.DashboardMetric
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.DisconnectResult
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.HealthSnapshotBuilder
import com.jonisuominen.readout.core.ReadoutStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** What both Health Connect sources need: a client, permissions, and honest errors. */
abstract class HealthConnectSource(
    protected val context: Context,
    protected val store: ReadoutStore,
    protected val bridge: PermissionBridge,
    protected val now: () -> Instant = Instant::now,
) {
    abstract val source: DataSource
    protected abstract val readPermissions: Set<String>
    protected abstract val deniedMessage: String

    protected fun client(): HealthConnectClient {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            throw ConnectorException.unavailable("Health Connect is not available on this device. Install or update it from Google Play.")
        }
        return HealthConnectClient.getOrCreate(context)
    }

    protected suspend fun requestAccess() {
        val client = client()
        val granted = client.permissionController.getGrantedPermissions()
        var wanted = readPermissions
        // Reading without the app on screen is a separate grant, offered where the
        // phone supports it. Connecting asks for it again if it was declined before.
        val background = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
        if (background == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            wanted = wanted + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        }
        if (!granted.containsAll(wanted)) bridge.requestHealthPermissions(wanted)
        if (client.permissionController.getGrantedPermissions().none { it in readPermissions }) {
            throw ConnectorException.authorizationDenied(deniedMessage)
        }
    }

    /**
     * The permissions granted now. When none of this source's remain, access
     * was revoked: its cached values are removed before the refresh fails, so
     * they do not stay on screen.
     */
    protected suspend fun grantedPermissions(client: HealthConnectClient): Set<String> {
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.none { it in readPermissions }) {
            if (store.dashboard.metrics.any { it.source == source }) {
                store.dashboard.removeMetrics(source)
                store.onWidgetsNeedReload()
            }
            throw ConnectorException.authorizationDenied("Allow Readout in Health Connect to refresh this data.")
        }
        return granted
    }

    /**
     * Runs reads of granted types. A security error then means the app is in
     * the background without the background grant, not a revoked permission.
     */
    protected suspend fun <T> reading(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConnectorException) {
        throw error
    } catch (_: SecurityException) {
        throw ConnectorException.temporarilyUnreadable("Open Readout to refresh health data.")
    } catch (_: Exception) {
        throw ConnectorException.unavailable("Health Connect could not be read. Try again later.")
    }

    /** The name of the app that wrote a record, or null when it cannot be seen. */
    protected fun appName(packageName: String): String? {
        if (packageName.isBlank()) return null
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        } catch (_: PackageManager.NameNotFoundException) {
            knownApps[packageName]
        }
    }

    private companion object {
        /** Apps Android does not let Readout look up by package, by their public names. */
        val knownApps = mapOf(
            "com.ouraring.oura" to "Oura",
            "com.fitbit.FitbitMobile" to "Fitbit",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.garmin.android.apps.connectmobile" to "Garmin Connect",
            "com.strava" to "Strava",
            "com.whoop.android" to "WHOOP",
            "com.withings.wiscale2" to "Withings",
            "com.polar.polarflow" to "Polar Flow",
            "com.google.android.apps.healthdata" to "Health Connect",
        )
    }
}

class HealthConnectConnector(
    context: Context,
    store: ReadoutStore,
    bridge: PermissionBridge,
    now: () -> Instant = Instant::now,
) : HealthConnectSource(context, store, bridge, now), DashboardConnector {
    override val source = DataSource.HEALTH

    override val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    )

    override val deniedMessage = "Health Connect access was not granted."

    override suspend fun authorize() = requestAccess()

    override suspend fun refresh(): List<DashboardMetric> {
        val client = client()
        val granted = grantedPermissions(client)
        val date = now()
        val env = DisplayEnvironment.current
        val midnight = env.startOfDay(date)
        // A range must end after it starts, even in the first instant of the day.
        val today = TimeRangeFilter.between(midnight, if (date.isAfter(midnight)) date else midnight.plusMillis(1))
        val recent = TimeRangeFilter.between(date.minus(Duration.ofDays(30)), date)

        fun <T : Record> allowed(type: KClass<T>) = HealthPermission.getReadPermission(type) in granted

        // The queries run side by side; a widget refresh has little time.
        return reading {
            coroutineScope {
                val steps = async {
                    total(client, StepsRecord.COUNT_TOTAL, today, allowed(StepsRecord::class)) { it.toDouble() }
                }
                val energy = async {
                    total(client, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, today, allowed(ActiveCaloriesBurnedRecord::class)) { it.inKilocalories }
                }
                val exercise = async {
                    total(client, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, today, allowed(ExerciseSessionRecord::class)) { it.seconds / 60.0 }
                }
                val distance = async {
                    total(client, DistanceRecord.DISTANCE_TOTAL, today, allowed(DistanceRecord::class)) { it.inMeters }
                }
                val resting = async {
                    latest(client, RestingHeartRateRecord::class, recent, allowed(RestingHeartRateRecord::class))
                }
                val heartRate = async {
                    latest(client, HeartRateRecord::class, recent, allowed(HeartRateRecord::class))
                }
                val variability = async {
                    latest(client, HeartRateVariabilityRmssdRecord::class, recent, allowed(HeartRateVariabilityRmssdRecord::class))
                }
                val workout = async {
                    latest(client, ExerciseSessionRecord::class, recent, allowed(ExerciseSessionRecord::class))
                }

                val restingRecord = resting.await()
                val heartRecord = heartRate.await()
                val heartSample = heartRecord?.samples?.maxByOrNull { it.time }
                val variabilityRecord = variability.await()
                val workoutRecord = workout.await()
                listOf(
                    dailyTotal(HealthSnapshotBuilder.steps, steps.await(), date, env),
                    HealthSnapshotBuilder.heart(
                        HealthSnapshotBuilder.restingHeartRate,
                        restingRecord?.beatsPerMinute?.toDouble(),
                        restingRecord?.time,
                        restingRecord?.let { appName(it.metadata.dataOrigin.packageName) },
                        date, env,
                    ),
                    HealthSnapshotBuilder.heart(
                        HealthSnapshotBuilder.heartRate,
                        heartSample?.beatsPerMinute?.toDouble(),
                        heartSample?.time,
                        heartRecord?.let { appName(it.metadata.dataOrigin.packageName) },
                        date, env,
                    ),
                    HealthSnapshotBuilder.heart(
                        HealthSnapshotBuilder.heartRateVariability,
                        variabilityRecord?.heartRateVariabilityMillis,
                        variabilityRecord?.time,
                        variabilityRecord?.let { appName(it.metadata.dataOrigin.packageName) },
                        date, env,
                    ),
                    dailyTotal(HealthSnapshotBuilder.activeEnergy, energy.await(), date, env),
                    dailyTotal(HealthSnapshotBuilder.exercise, exercise.await(), date, env),
                    dailyTotal(HealthSnapshotBuilder.distance, distance.await(), date, env),
                    HealthSnapshotBuilder.workout(
                        workoutRecord?.let { Duration.between(it.startTime, it.endTime).seconds.toDouble() },
                        workoutRecord?.let { it.title?.takeIf(String::isNotBlank) ?: workoutName(it.exerciseType) },
                        workoutRecord?.endTime,
                        workoutRecord?.let { appName(it.metadata.dataOrigin.packageName) },
                        date, env,
                    ),
                )
            }
        }
    }

    override suspend fun disconnect(): DisconnectResult {
        // Health Connect owns its grants. People revoke them in Health Connect.
        return DisconnectResult.localOnly
    }

    private class Total(val sum: Double?, val origins: List<String>)

    private fun dailyTotal(kind: HealthSnapshotBuilder.DailyTotal, total: Total, date: Instant, env: DisplayEnvironment) =
        HealthSnapshotBuilder.dailyTotal(kind, total.sum, total.origins, date, env)

    /** Today's sum of one type. A type that is not allowed or has no data is empty, not zero. */
    private suspend fun <T : Any> total(
        client: HealthConnectClient,
        metric: AggregateMetric<T>,
        range: TimeRangeFilter,
        allowed: Boolean,
        value: (T) -> Double,
    ): Total {
        if (!allowed) return Total(null, emptyList())
        val result: AggregationResult = client.aggregate(AggregateRequest(setOf(metric), range))
        val sum = result[metric]?.let(value)
        return Total(sum, if (sum == null) emptyList() else result.dataOrigins.mapNotNull { appName(it.packageName) })
    }

    private suspend fun <T : Record> latest(
        client: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        allowed: Boolean,
    ): T? {
        if (!allowed) return null
        val response = client.readRecords(
            ReadRecordsRequest(recordType = type, timeRangeFilter = range, ascendingOrder = false, pageSize = 1),
        )
        return response.records.firstOrNull()
    }

    private fun workoutName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Skiing"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dance"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Football"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
        else -> "Workout"
    }
}

/**
 * Oura sleep that the Oura app shares with Health Connect. It needs no Oura
 * sign-in or broker, and carries no readiness or sleep score.
 */
class OuraHealthConnectConnector(
    context: Context,
    store: ReadoutStore,
    bridge: PermissionBridge,
    now: () -> Instant = Instant::now,
) : HealthConnectSource(context, store, bridge, now), DashboardConnector {
    override val source = DataSource.OURA

    override val readPermissions: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    override val deniedMessage = "Health Connect sleep access was not granted."

    override suspend fun authorize() = requestAccess()

    override suspend fun refresh(): List<DashboardMetric> {
        val client = client()
        grantedPermissions(client)
        val date = now()
        val sessions = reading {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(date.minus(Duration.ofHours(36)), date),
                ),
            ).records
        }.filter { it.metadata.dataOrigin.packageName.contains("oura", ignoreCase = true) }

        val intervals = sessions.flatMap { session ->
            val asleep = session.stages.filter { it.stage in asleepStages }.map { it.startTime to it.endTime }
            // A session without stages still says when the person slept.
            if (session.stages.isEmpty()) listOf(session.startTime to session.endTime) else asleep
        }
        val origin = sessions.firstOrNull()?.let { appName(it.metadata.dataOrigin.packageName) }
        return listOf(HealthSnapshotBuilder.ouraSleep(intervals, origin))
    }

    override suspend fun disconnect(): DisconnectResult {
        // This connector stores no token or provider account state.
        return DisconnectResult.localOnly
    }

    private companion object {
        val asleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING,
            SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP,
            SleepSessionRecord.STAGE_TYPE_REM,
        )
    }
}
