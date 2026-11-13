package com.jonisuominen.readout.connect

import android.app.Activity
import com.jonisuominen.readout.core.ConnectorException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets connectors ask for permissions and wait for the answer. Only an
 * activity can show a system prompt, so requests fail when the app is not on
 * screen: widgets and background work never prompt.
 */
class PermissionBridge {
    interface Host {
        val activity: Activity
        fun launchPermission(permission: String)
        fun launchHealthPermissions(permissions: Set<String>)
    }

    private var host: Host? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingHealth: CompletableDeferred<Set<String>>? = null

    /** The activity on screen, or null in the background. */
    val activity: Activity? get() = host?.activity

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    suspend fun requestPermission(permission: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val host = host ?: throw ConnectorException.authorizationDenied("Open Readout to grant access.")
        pendingPermission?.complete(false)
        val pending = CompletableDeferred<Boolean>()
        pendingPermission = pending
        host.launchPermission(permission)
        pending.await()
    }

    suspend fun requestHealthPermissions(permissions: Set<String>): Set<String> = withContext(Dispatchers.Main.immediate) {
        val host = host ?: throw ConnectorException.authorizationDenied("Open Readout to grant access.")
        pendingHealth?.complete(emptySet())
        val pending = CompletableDeferred<Set<String>>()
        pendingHealth = pending
        host.launchHealthPermissions(permissions)
        pending.await()
    }

    fun onPermissionResult(granted: Boolean) {
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    fun onHealthPermissionsResult(granted: Set<String>) {
        pendingHealth?.complete(granted)
        pendingHealth = null
    }
}
