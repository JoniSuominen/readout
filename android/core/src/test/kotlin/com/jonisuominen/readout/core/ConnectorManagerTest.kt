package com.jonisuominen.readout.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorManagerTest {
    private val clock = MutableClock()
    private val store = ReadoutStore(InMemoryKeyValueStore(), clock)
    private val weather = FakeConnector(DataSource.WEATHER)
    private val health = FakeConnector(DataSource.HEALTH)
    private val ouraHealth = FakeConnector(DataSource.OURA)
    private val calendar = FakeConnector(DataSource.CALENDAR)
    private val directOura = FakeConnector(DataSource.OURA, listOf(metric("readiness", DataSource.OURA))).apply {
        disconnectResult = DisconnectResult.revoked
    }
    private var widgetReloads = 0
    private var scheduled = 0
    private var cancelled = 0

    private fun TestScope.manager(directAvailable: Boolean = true) = ConnectorManager(
        store,
        listOf(weather, health, ouraHealth, calendar),
        directOura,
        directAvailable,
        StandardTestDispatcher(testScheduler),
        ConnectorManager.Hooks({ widgetReloads++ }, { scheduled++ }, { cancelled++ }),
        clock,
    )

    private val ConnectorManager.now get() = state.value

    @Test
    fun connectPublishesMetricsAndSchedulesBackgroundRefresh() = runTest {
        val manager = manager()
        manager.connect(DataSource.WEATHER)
        assertEquals(setOf(DataSource.WEATHER), manager.now.connectedSources)
        assertEquals(setOf(DataSource.WEATHER), store.dashboard.connectedSources)
        assertEquals(listOf("temperature"), manager.now.metrics.map { it.id })
        assertEquals(baseNow, manager.now.updatedAt)
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.WEATHER))
        assertEquals(1, weather.authorizeCalls)
        assertEquals(1, scheduled)
        assertEquals(1, widgetReloads)

        manager.disconnect(DataSource.WEATHER)
        assertTrue(manager.now.connectedSources.isEmpty())
        assertTrue(store.dashboard.metrics.isEmpty())
        assertEquals(DisconnectResult.localOnly, manager.now.disconnectResults[DataSource.WEATHER])
        assertEquals(1, cancelled)
    }

    @Test
    fun deniedPermissionShowsTheReasonAndConnectsNothing() = runTest {
        val manager = manager()
        health.authorizeError = ConnectorException.authorizationDenied("Health Connect access was not granted.")
        manager.connect(DataSource.HEALTH)
        assertEquals(ConnectorManager.Activity.Failed("Health Connect access was not granted."), manager.activity(DataSource.HEALTH))
        assertTrue(manager.now.connectedSources.isEmpty())
        assertEquals(0, health.refreshCalls)
    }

    @Test
    fun foregroundRefreshSkipsFreshSourcesButUpdatesOldOnes() = runTest {
        val manager = manager()
        manager.connect(DataSource.WEATHER)
        clock.advanceMinutes(4)
        manager.connect(DataSource.HEALTH)
        clock.advanceMinutes(2)
        manager.refreshIfNeeded()
        assertEquals("Weather is six minutes old", 2, weather.refreshCalls)
        assertEquals("Health is two minutes old", 1, health.refreshCalls)
    }

    @Test
    fun backgroundRefreshSkipsFreshSourcesAndStaysSilentOnFailure() = runTest {
        val manager = manager()
        manager.connect(DataSource.WEATHER)
        manager.connect(DataSource.HEALTH)
        clock.advanceMinutes(11)
        // Another process path, such as a widget, fetched weather a moment ago.
        store.dashboard.replaceMetrics(listOf(metric("temperature", DataSource.WEATHER, value = "9")), DataSource.WEATHER)
        health.refreshError = java.io.IOException("offline")
        val reloadsBefore = widgetReloads
        manager.refreshStaleSources(10 * 60 * 1000L)
        assertEquals(1, weather.refreshCalls)
        assertEquals(2, health.refreshCalls)
        assertEquals("9", manager.now.metrics.first { it.id == "temperature" }.value)
        assertNull("A quiet failure is not recorded", store.dashboard.error(DataSource.HEALTH))
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.HEALTH))
        assertEquals(baseNow, store.dashboard.updatedAt(DataSource.HEALTH))
        assertEquals(reloadsBefore, widgetReloads)
    }

    @Test
    fun refreshFailureKeepsCachedDataAndOriginalTimestamp() = runTest {
        val manager = manager()
        manager.connect(DataSource.HEALTH)
        clock.advanceMinutes(30)
        health.refreshError = ConnectorException.unavailable("Health Connect is not available on this device.")
        assertTrue("The recorded error is a cache change", manager.refresh(DataSource.HEALTH))
        assertEquals(1, manager.now.metrics.size)
        assertEquals(baseNow, store.dashboard.updatedAt(DataSource.HEALTH))
        assertEquals("Health Connect is not available on this device.", store.dashboard.error(DataSource.HEALTH))
        assertTrue(manager.activity(DataSource.HEALTH) is ConnectorManager.Activity.Failed)

        // A quiet retry that fails again keeps showing the earlier failure.
        assertFalse(manager.refresh(DataSource.HEALTH, quiet = true))
        assertTrue(manager.activity(DataSource.HEALTH) is ConnectorManager.Activity.Failed)

        health.refreshError = ConnectorException.temporarilyUnreadable("Open the app to read health data.")
        assertFalse(manager.refresh(DataSource.HEALTH))

        health.refreshError = null
        assertTrue(manager.refresh(DataSource.HEALTH))
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.HEALTH))
        assertNull(store.dashboard.error(DataSource.HEALTH))
    }

    @Test
    fun temporarilyUnreadableDataIsNeverShownAsAFailure() = runTest {
        val manager = manager()
        manager.connect(DataSource.HEALTH)
        health.refreshError = ConnectorException.temporarilyUnreadable("Open the app to read health data.")
        assertFalse(manager.refresh(DataSource.HEALTH))
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.HEALTH))
        assertNull(store.dashboard.error(DataSource.HEALTH))
    }

    @Test
    fun deleteDuringAuthorizationCannotRestoreData() = runTest {
        val manager = manager()
        val gate = CompletableDeferred<Unit>()
        health.beforeAuthorizeReturns = { gate.await() }
        val connecting = launch { manager.connect(DataSource.HEALTH) }
        advanceUntilIdle()
        assertEquals(ConnectorManager.Activity.Connecting, manager.activity(DataSource.HEALTH))
        manager.deleteLocalData()
        gate.complete(Unit)
        connecting.join()
        assertTrue(manager.now.connectedSources.isEmpty())
        assertTrue(store.dashboard.metrics.isEmpty())
        assertEquals(0, health.refreshCalls)
        assertFalse(manager.now.isDeletingLocalData)
        assertEquals(DisconnectResult.revoked, manager.now.dataDeletionResult)
        assertEquals(1, directOura.disconnectCalls)
    }

    @Test
    fun disconnectDuringRefreshCannotRestoreSnapshot() = runTest {
        val manager = manager()
        manager.connect(DataSource.HEALTH)
        val gate = CompletableDeferred<Unit>()
        health.beforeRefreshReturns = { gate.await() }
        val refreshing = launch { manager.refresh(DataSource.HEALTH) }
        advanceUntilIdle()
        manager.disconnect(DataSource.HEALTH)
        gate.complete(Unit)
        refreshing.join()
        assertTrue(store.dashboard.metrics.isEmpty())
        assertTrue(manager.now.metrics.isEmpty())
        assertNull(store.dashboard.updatedAt(DataSource.HEALTH))
    }

    @Test
    fun calendarTitleOptOutRejectsRefreshStartedUnderPreviousPreferences() = runTest {
        val manager = manager()
        store.calendar.selectedCalendarIDs = setOf("personal")
        store.calendar.showEventTitles = true
        calendar.metrics = listOf(metric("nextEvent", DataSource.CALENDAR, value = "Busy", privateValue = "Secret"))
        manager.connect(DataSource.CALENDAR)
        assertEquals("Secret", manager.now.metrics.single().privateValue)

        val gate = CompletableDeferred<Unit>()
        calendar.beforeRefreshReturns = { gate.await() }
        val refreshing = launch { manager.refresh(DataSource.CALENDAR) }
        advanceUntilIdle()
        store.calendar.showEventTitles = false
        manager.calendarPreferencesDidChange()
        assertTrue("The published private metric is dropped at once", manager.now.metrics.isEmpty())
        gate.complete(Unit)
        refreshing.join()
        assertTrue("A fetch started under the old preferences is discarded", store.dashboard.metrics.isEmpty())
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.CALENDAR))

        calendar.beforeRefreshReturns = {}
        calendar.metrics = listOf(metric("nextEvent", DataSource.CALENDAR, value = "Busy"))
        manager.applyCalendarPreferences()
        assertNull(manager.now.metrics.single().privateValue)
    }

    @Test
    fun methodSwitchDisconnectsTheCurrentOuraSource() = runTest {
        val manager = manager()
        manager.connect(DataSource.OURA)
        assertEquals(1, ouraHealth.refreshCalls)
        manager.setOuraConnectionMethod(OuraConnectionMethod.DIRECT)
        assertEquals(OuraConnectionMethod.DIRECT, manager.now.ouraConnectionMethod)
        assertEquals(OuraConnectionMethod.DIRECT, store.ouraMethod)
        assertEquals(1, ouraHealth.disconnectCalls)
        assertFalse(DataSource.OURA in manager.now.connectedSources)
        manager.connect(DataSource.OURA)
        assertEquals(listOf("readiness"), manager.now.metrics.map { it.id })
        assertNull(manager.now.disconnectResults[DataSource.OURA])

        val unavailable = manager(directAvailable = false)
        unavailable.setOuraConnectionMethod(OuraConnectionMethod.HEALTH_CONNECT)
        unavailable.setOuraConnectionMethod(OuraConnectionMethod.DIRECT)
        assertEquals(OuraConnectionMethod.HEALTH_CONNECT, unavailable.now.ouraConnectionMethod)
    }

    @Test
    fun failedDirectCleanupSurvivesRelaunchAndBlocksReconnectUntilRetrySucceeds() = runTest {
        val failed = DisconnectResult(false, DisconnectResult.RemoteAuthorization.REVOKED)
        store.ouraMethod = OuraConnectionMethod.DIRECT
        val manager = manager()
        manager.connect(DataSource.OURA)
        directOura.disconnectResult = failed
        manager.disconnect(DataSource.OURA)
        assertEquals(failed, manager.now.disconnectResults[DataSource.OURA])

        val relaunched = manager()
        assertEquals("The failure is visible after relaunch", failed, relaunched.now.disconnectResults[DataSource.OURA])
        relaunched.connect(DataSource.OURA)
        assertEquals(ConnectorManager.Activity.Failed(failed.statusMessage), relaunched.activity(DataSource.OURA))
        assertFalse(DataSource.OURA in relaunched.now.connectedSources)
        relaunched.setOuraConnectionMethod(OuraConnectionMethod.HEALTH_CONNECT)
        assertEquals("A failed cleanup blocks the method switch", OuraConnectionMethod.DIRECT, relaunched.now.ouraConnectionMethod)
        assertEquals(0, ouraHealth.disconnectCalls)

        directOura.disconnectResult = DisconnectResult.revoked
        relaunched.retryOuraDisconnection()
        assertEquals(DisconnectResult.revoked, relaunched.now.disconnectResults[DataSource.OURA])
        relaunched.connect(DataSource.OURA)
        assertTrue(DataSource.OURA in relaunched.now.connectedSources)
        assertNull(store.ouraDisconnectResult)
    }

    @Test
    fun deleteReportsDirectCleanupFailureAndRetryStillUsesTheDirectConnector() = runTest {
        val manager = manager()
        manager.connect(DataSource.WEATHER)
        directOura.disconnectResult = DisconnectResult(false, DisconnectResult.RemoteAuthorization.UNCONFIRMED)
        manager.deleteLocalData()
        assertTrue(manager.now.connectedSources.isEmpty())
        assertTrue(manager.now.metrics.isEmpty())
        assertEquals(OuraConnectionMethod.HEALTH_CONNECT, manager.now.ouraConnectionMethod)
        assertEquals(false, manager.now.dataDeletionResult?.localCredentialsCleared)
        assertEquals(1, weather.disconnectCalls)

        directOura.disconnectResult = DisconnectResult.revoked
        val localDisconnects = ouraHealth.disconnectCalls
        manager.retryOuraDisconnection()
        assertEquals(2, directOura.disconnectCalls)
        assertEquals("The retry belongs to the direct account", localDisconnects, ouraHealth.disconnectCalls)
        assertEquals(DisconnectResult.revoked, manager.now.dataDeletionResult)
    }

    @Test
    fun healthConnectOuraDisconnectionCannotEraseAPreviousDirectFailure() = runTest {
        val unconfirmed = DisconnectResult(true, DisconnectResult.RemoteAuthorization.UNCONFIRMED)
        store.ouraDisconnectResult = unconfirmed
        val manager = manager()
        manager.connect(DataSource.OURA)
        assertEquals("Reconnecting through Health Connect keeps the warning", unconfirmed, manager.now.disconnectResults[DataSource.OURA])
        manager.disconnect(DataSource.OURA)
        assertEquals(unconfirmed, manager.now.disconnectResults[DataSource.OURA])
        assertEquals(unconfirmed, store.ouraDisconnectResult)
    }

    @Test
    fun weatherLocationChangeConnectsOrRefreshes() = runTest {
        val manager = manager()
        val oslo = WeatherLocation.of("Oslo", latitude = 59.91, longitude = 10.75, isCurrentLocation = false)
        manager.setWeatherLocation(oslo)
        assertEquals(oslo, store.weatherLocation.location)
        assertTrue(DataSource.WEATHER in manager.now.connectedSources)
        assertEquals(1, weather.refreshCalls)
        manager.setWeatherLocation(null)
        assertNull(store.weatherLocation.location)
        assertEquals(2, weather.refreshCalls)
        assertEquals(1, weather.authorizeCalls)
    }

    @Test
    fun reloadFromStoreShowsWhatBackgroundWorkFetched() = runTest {
        val manager = manager()
        manager.connect(DataSource.WEATHER)
        store.dashboard.replaceMetrics(listOf(metric("temperature", DataSource.WEATHER, value = "21")), DataSource.WEATHER)
        store.dashboard.recordError("Offline", DataSource.WEATHER)
        manager.reloadFromStore()
        assertEquals("21", manager.now.metrics.single().value)
        assertEquals(ConnectorManager.Activity.Failed("Offline"), manager.activity(DataSource.WEATHER))
        store.dashboard.replaceMetrics(listOf(metric("temperature", DataSource.WEATHER, value = "22")), DataSource.WEATHER)
        manager.reloadFromStore()
        assertEquals(ConnectorManager.Activity.Idle, manager.activity(DataSource.WEATHER))
    }

    @Test
    fun revokedCalendarAccessClearsTheSnapshotBeforeItIsShown() = runTest {
        store.dashboard.connectedSources = setOf(DataSource.CALENDAR)
        store.dashboard.replaceMetrics(listOf(metric("nextEvent", DataSource.CALENDAR, privateValue = "Secret")), DataSource.CALENDAR)
        class Guarded : DashboardConnector by calendar, SnapshotGuard {
            override fun clearSnapshotIfAccessUnavailable(): Boolean {
                store.calendar.invalidateSnapshot()
                return true
            }
        }
        val manager = ConnectorManager(store, listOf(Guarded()), directOura, true, StandardTestDispatcher(testScheduler))
        assertTrue(manager.state.value.metrics.isEmpty())
        assertTrue(store.dashboard.metrics.isEmpty())
    }
}
