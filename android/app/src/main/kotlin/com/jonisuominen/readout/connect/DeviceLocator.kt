package com.jonisuominen.readout.connect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jonisuominen.readout.core.ConnectorException
import com.jonisuominen.readout.core.DeviceCoordinate
import com.jonisuominen.readout.core.DeviceLocating
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot, approximate device location for the weather forecast, from the
 * platform location service. No Google Play services are involved.
 */
class DeviceLocator(
    private val context: Context,
    private val bridge: PermissionBridge,
) : DeviceLocating {
    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    private val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun authorizedCoordinate(): DeviceCoordinate {
        if (!hasPermission && !bridge.requestPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw denied()
        }
        return currentCoordinate()
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentCoordinate(): DeviceCoordinate {
        if (!hasPermission) throw denied()
        val manager = manager ?: throw unknown()
        val providers = try {
            manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        } catch (_: SecurityException) {
            throw denied()
        }
        // A fix this recent is reused, so refreshes every few minutes don't wake
        // location services. The forecast grid is coarser than a 15-minute move.
        val cached = providers.mapNotNull { provider -> lastKnown(manager, provider) }.maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null && ageMillis(cached) < MAXIMUM_FIX_AGE_MILLIS) return cached.coordinate()

        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
            ?: providers.firstOrNull()
            ?: throw ConnectorException.unavailable("Turn on location, or choose a city for weather.")
        // Background work has little time; give up early and let the caller
        // forecast for the last stored coordinate instead.
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return (fresh ?: cached)?.coordinate() ?: throw unknown()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, provider: String): Location? = try {
        manager.getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null
    }

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun Location.coordinate() = DeviceCoordinate(latitude, longitude)

    private fun denied() =
        ConnectorException.authorizationDenied("Allow location access in Settings, or choose a city for weather.")

    private fun unknown() =
        ConnectorException.unavailable("Your location could not be found. Try again, or choose a city for weather.")

    private companion object {
        const val MAXIMUM_FIX_AGE_MILLIS = 15 * 60 * 1000L
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
