package com.jonisuominen.readout

import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import com.jonisuominen.readout.connect.DeviceCalendarConnector
import com.jonisuominen.readout.connect.DeviceLocator
import com.jonisuominen.readout.connect.HealthConnectConnector
import com.jonisuominen.readout.connect.OuraBrowserAuthenticator
import com.jonisuominen.readout.connect.OuraHealthConnectConnector
import com.jonisuominen.readout.connect.PermissionBridge
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.OpenMeteoClient
import com.jonisuominen.readout.core.OuraApiConnector
import com.jonisuominen.readout.core.OuraConfiguration
import com.jonisuominen.readout.core.ReadoutStore
import com.jonisuominen.readout.core.UrlConnectionTransport
import com.jonisuominen.readout.core.WeatherConnector
import com.jonisuominen.readout.data.KeystoreOuraTokenStore
import com.jonisuominen.readout.data.PrefsKeyValueStore
import com.jonisuominen.readout.widget.WidgetConfigStore
import com.jonisuominen.readout.widget.WidgetUpdater
import com.jonisuominen.readout.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.ZoneId
import java.util.Locale

class ReadoutApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val app = this
        DisplayEnvironment.provider = {
            DisplayEnvironment(ZoneId.systemDefault(), Locale.getDefault(), DateFormat.is24HourFormat(app))
        }
        // Alarms do not survive a restart or an app update; widgets may already be placed.
        WidgetUpdater.ensureTicking(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as ReadoutApplication).container

/** The objects the app, its widgets, and background work share in one process. */
class AppContainer(private val context: Context) {
    val store = ReadoutStore(PrefsKeyValueStore(context.getSharedPreferences("readout.data", Context.MODE_PRIVATE))).apply {
        onWidgetsNeedReload = { WidgetUpdater.requestUpdate(context) }
    }
    /** Outlives any screen, so a connect or refresh finishes even when the screen that started it closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val widgetConfigs = WidgetConfigStore(context)
    val bridge = PermissionBridge()
    val ouraConfiguration = OuraConfiguration(BuildConfig.OURA_BROKER_URL)
    val ouraAuthenticator = OuraBrowserAuthenticator(bridge)
    val openMeteo = OpenMeteoClient()
    val calendarConnector = DeviceCalendarConnector(context, store, bridge)

    val manager: ConnectorManager by lazy {
        ConnectorManager(
            store = store,
            connectors = listOf(
                WeatherConnector(store, openMeteo, DeviceLocator(context, bridge)),
                HealthConnectConnector(context, bridge),
                OuraHealthConnectConnector(context, bridge),
                calendarConnector,
            ),
            directOuraConnector = OuraApiConnector(
                configuration = ouraConfiguration,
                transport = UrlConnectionTransport(),
                tokenStorage = KeystoreOuraTokenStore(context),
                authenticator = ouraAuthenticator,
            ),
            directOuraAvailable = ouraConfiguration.isConfigured,
            dispatcher = Dispatchers.Main.immediate,
            hooks = ConnectorManager.Hooks(
                reloadWidgets = { WidgetUpdater.requestUpdate(context) },
                scheduleBackgroundRefresh = { RefreshWorker.schedule(context) },
                cancelBackgroundRefresh = { RefreshWorker.cancel(context) },
            ),
        )
    }
}
