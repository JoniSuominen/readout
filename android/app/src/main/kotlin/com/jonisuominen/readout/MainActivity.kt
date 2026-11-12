package com.jonisuominen.readout

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.jonisuominen.readout.connect.PermissionBridge
import com.jonisuominen.readout.core.TileLink
import com.jonisuominen.readout.ui.NavState
import com.jonisuominen.readout.ui.Overlay
import com.jonisuominen.readout.ui.ReadoutApp
import com.jonisuominen.readout.ui.ReadoutTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), PermissionBridge.Host {
    override val activity: Activity get() = this

    private val nav = NavState()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.bridge.onPermissionResult(granted)
    }

    private val healthLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        container.bridge.onHealthPermissionsResult(granted)
    }

    override fun launchPermission(permission: String) = permissionLauncher.launch(permission)

    override fun launchHealthPermissions(permissions: Set<String>) = healthLauncher.launch(permissions)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.bridge.attach(this)
        if (savedInstanceState == null) handle(intent)
        setContent {
            ReadoutTheme {
                ReadoutApp(container, nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        val container = container
        container.scope.launch { container.manager.refreshIfNeeded() }
        // Coming back from the Oura page without a callback means sign-in was abandoned.
        if (container.ouraAuthenticator.isWaitingForCallback) {
            lifecycleScope.launch {
                delay(1_500)
                if (container.ouraAuthenticator.isWaitingForCallback) container.ouraAuthenticator.cancelNow()
            }
        }
    }

    override fun onDestroy() {
        container.bridge.detach(this)
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // Health Connect asks the app to explain its access.
        if (intent.action == RATIONALE_ACTION || intent.action == Intent.ACTION_VIEW_PERMISSION_USAGE) {
            nav.overlay = Overlay.Privacy
            return
        }
        val link = intent.data ?: return
        val metricID = TileLink.metricID(link.scheme, link.host, link.pathSegments.orEmpty()) ?: return
        // A tapped widget tile: calendar tiles go to the calendar on the event's
        // day, everything else shows Today.
        nav.tab = 0
        nav.overlay = Overlay.None
        val state = container.manager.state.value
        val destination = TileLink.destination(metricID, state.metrics, state.ouraConnectionMethod)
        if (destination is TileLink.Destination.Calendar) {
            val time = ContentUris.withAppendedId(
                CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(),
                destination.day.toEpochMilli(),
            )
            try {
                startActivity(Intent(Intent.ACTION_VIEW, time))
            } catch (_: ActivityNotFoundException) {
                // No calendar app: Today already shows the event.
            }
        }
    }

    private companion object {
        const val RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}
