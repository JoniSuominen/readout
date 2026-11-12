package com.jonisuominen.readout.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Owns the connection state the app shows: which sources are connected, what
 * they last returned, and what each is doing now. Every function hops to one
 * dispatcher (the main thread in the app), so state changes never interleave
 * except where a fetch suspends.
 */
class ConnectorManager(
    private val store: ReadoutStore,
    connectors: List<DashboardConnector>,
    private val directOuraConnector: DashboardConnector,
    val directOuraAvailable: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val hooks: Hooks = Hooks(),
    private val now: () -> Instant = Instant::now,
) {
    class Hooks(
        val reloadWidgets: () -> Unit = {},
        val scheduleBackgroundRefresh: () -> Unit = {},
        val cancelBackgroundRefresh: () -> Unit = {},
    )

    sealed interface Activity {
        data object Idle : Activity
        data object Connecting : Activity
        data object Refreshing : Activity
        data object Disconnecting : Activity
        data class Failed(val message: String) : Activity

        val isBusy: Boolean get() = this == Connecting || this == Refreshing || this == Disconnecting
    }

    data class State(
        val connectedSources: Set<DataSource> = emptySet(),
        val metrics: List<DashboardMetric> = emptyList(),
        val updatedAt: Instant? = null,
        val activities: Map<DataSource, Activity> = emptyMap(),
        val ouraConnectionMethod: OuraConnectionMethod = OuraConnectionMethod.HEALTH_CONNECT,
        val disconnectResults: Map<DataSource, DisconnectResult> = emptyMap(),
        val dataDeletionResult: DisconnectResult? = null,
        val isDeletingLocalData: Boolean = false,
    ) {
        fun activity(source: DataSource): Activity = activities[source] ?: Activity.Idle
        val isRefreshing: Boolean get() = activities.values.any { it.isBusy }
    }

    private val connectors: Map<DataSource, DashboardConnector> = connectors.associateBy { it.source }
    private val operationIDs = HashMap<DataSource, Any>()
    private var isResetting = false
    private var isSwitchingOura = false
    private var resetRevision = 0

    private val mutableState: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        // Permission can be revoked while the app is closed. Clear the private
        // snapshot before publishing it, regardless of its fetch age.
        connectors.filterIsInstance<SnapshotGuard>().forEach { it.clearSnapshotIfAccessUnavailable() }
        val connected = store.dashboard.connectedSources
        mutableState = MutableStateFlow(
            State(
                connectedSources = connected,
                metrics = store.dashboard.metrics,
                updatedAt = store.dashboard.updatedAt,
                activities = connected.mapNotNull { source ->
                    store.dashboard.error(source)?.let { source to Activity.Failed(it) }
                }.toMap(),
                ouraConnectionMethod = store.ouraMethod,
                disconnectResults = store.ouraDisconnectResult?.let { mapOf(DataSource.OURA to it) }.orEmpty(),
            ),
        )
        state = mutableState.asStateFlow()
    }

    private val current: State get() = mutableState.value

    fun activity(source: DataSource): Activity = current.activity(source)

    private fun connector(source: DataSource): DashboardConnector? =
        if (source == DataSource.OURA && current.ouraConnectionMethod == OuraConnectionMethod.DIRECT) {
            directOuraConnector
        } else {
            connectors[source]
        }

    private fun setActivity(source: DataSource, activity: Activity) {
        mutableState.update { it.copy(activities = it.activities + (source to activity)) }
    }

    suspend fun connect(source: DataSource) = withContext(dispatcher) {
        val connector = connector(source)
        if (isResetting || (source == DataSource.OURA && isSwitchingOura) || connector == null || activity(source).isBusy) {
            return@withContext
        }
        val pending = current.disconnectResults[DataSource.OURA]
        if (source == DataSource.OURA && pending != null && !pending.localCredentialsCleared) {
            setActivity(source, Activity.Failed(pending.statusMessage))
            return@withContext
        }
        val operation = Any()
        val calendarRevision = store.calendar.revision
        operationIDs[source] = operation
        setActivity(source, Activity.Connecting)
        try {
            connector.authorize()
            currentCoroutineContext().ensureActive()
            if (!canPublish(source, operation, calendarRevision)) return@withContext
            val newMetrics = connector.refresh()
            currentCoroutineContext().ensureActive()
            if (!canPublish(source, operation, calendarRevision)) return@withContext
            store.dashboard.replaceMetrics(newMetrics, source, now())
            val connected = current.connectedSources + source
            store.dashboard.connectedSources = connected
            mutableState.update { state ->
                var results = state.disconnectResults
                var deletionResult = state.dataDeletionResult
                if (source != DataSource.OURA || state.ouraConnectionMethod == OuraConnectionMethod.DIRECT ||
                    results[DataSource.OURA]?.remoteAuthorization == DisconnectResult.RemoteAuthorization.NOT_APPLICABLE
                ) {
                    results = results - source
                }
                if (source == DataSource.OURA && state.ouraConnectionMethod == OuraConnectionMethod.DIRECT) {
                    store.ouraDisconnectResult = null
                    deletionResult = null
                }
                state.copy(
                    connectedSources = connected,
                    metrics = store.dashboard.metrics,
                    updatedAt = store.dashboard.updatedAt,
                    disconnectResults = results,
                    dataDeletionResult = deletionResult,
                    activities = state.activities + (source to Activity.Idle),
                )
            }
            hooks.reloadWidgets()
            hooks.scheduleBackgroundRefresh()
        } catch (error: Exception) {
            if (handle(error, source, operation, quiet = false, previous = Activity.Idle)) hooks.reloadWidgets()
            if (error is CancellationException) throw error
        }
    }

    suspend fun disconnect(source: DataSource) = withContext(dispatcher) {
        val connector = connector(source)
        if (isResetting || activity(source) == Activity.Disconnecting || connector == null) return@withContext
        disconnect(source, connector)
    }

    private suspend fun disconnect(source: DataSource, connector: DashboardConnector) {
        val operation = Any()
        operationIDs[source] = operation
        val connected = current.connectedSources - source
        store.dashboard.connectedSources = connected
        store.dashboard.removeMetrics(source)
        mutableState.update {
            it.copy(
                connectedSources = connected,
                metrics = store.dashboard.metrics,
                updatedAt = store.dashboard.updatedAt,
                activities = it.activities + (source to Activity.Disconnecting),
            )
        }
        hooks.reloadWidgets()
        if (connected.isEmpty()) hooks.cancelBackgroundRefresh()
        val result = connector.disconnect()
        if (operationIDs[source] !== operation) return
        record(result, source)
        setActivity(source, Activity.Idle)
    }

    suspend fun retryOuraDisconnection() = withContext(dispatcher) {
        if (isResetting || isSwitchingOura || activity(DataSource.OURA).isBusy) return@withContext
        // Deletion resets the selected method to Health Connect, but any failed
        // cleanup still belongs to the direct account's credentials and broker.
        disconnect(DataSource.OURA, directOuraConnector)
        if (current.dataDeletionResult != null) {
            mutableState.update { it.copy(dataDeletionResult = it.disconnectResults[DataSource.OURA]) }
        }
    }

    suspend fun setOuraConnectionMethod(method: OuraConnectionMethod) = withContext(dispatcher) {
        if (method == current.ouraConnectionMethod || isResetting || isSwitchingOura) return@withContext
        if (current.disconnectResults[DataSource.OURA]?.localCredentialsCleared == false) return@withContext
        if (method == OuraConnectionMethod.DIRECT && !directOuraAvailable) return@withContext
        isSwitchingOura = true
        try {
            val revision = resetRevision
            val connector = connector(DataSource.OURA)
            if (connector != null && activity(DataSource.OURA) != Activity.Disconnecting) {
                disconnect(DataSource.OURA, connector)
            }
            if (revision != resetRevision || isResetting) return@withContext
            if (current.disconnectResults[DataSource.OURA]?.localCredentialsCleared == false) return@withContext
            store.ouraMethod = method
            mutableState.update { it.copy(ouraConnectionMethod = method) }
        } finally {
            isSwitchingOura = false
        }
    }

    /**
     * Fetches one source. `quiet` is for background work: a failure leaves the
     * cached values and their age as they were instead of marking the source
     * failed. Returns true when the shared cache changed: new metrics or an error.
     */
    suspend fun refresh(source: DataSource, reloadWidgets: Boolean = true, quiet: Boolean = false): Boolean = withContext(dispatcher) {
        val connector = connector(source)
        if (isResetting || (source == DataSource.OURA && isSwitchingOura) ||
            source !in current.connectedSources || connector == null || activity(source).isBusy
        ) {
            return@withContext false
        }
        val operation = Any()
        val calendarRevision = store.calendar.revision
        val previous = activity(source)
        operationIDs[source] = operation
        setActivity(source, Activity.Refreshing)
        try {
            val newMetrics = connector.refresh()
            currentCoroutineContext().ensureActive()
            if (!canPublish(source, operation, calendarRevision)) return@withContext false
            store.dashboard.replaceMetrics(newMetrics, source, now())
            synchronizeFromStore()
            setActivity(source, Activity.Idle)
            if (reloadWidgets) hooks.reloadWidgets()
            true
        } catch (error: Exception) {
            val recorded = handle(error, source, operation, quiet, previous)
            if (recorded && reloadWidgets) hooks.reloadWidgets()
            if (error is CancellationException) throw error
            recorded
        }
    }

    suspend fun refreshConnectedSources(quiet: Boolean = false) = withContext(dispatcher) {
        refreshTogether(current.connectedSources, quiet)
    }

    /**
     * For background work: skip sources that were fetched recently, and stay
     * silent about failures.
     */
    suspend fun refreshStaleSources(olderThanMillis: Long, quiet: Boolean = true) = withContext(dispatcher) {
        reloadFromStoreNow()
        refreshTogether(current.connectedSources.filter { isStale(it, olderThanMillis) }.toSet(), quiet)
    }

    /** Fetches sources side by side and reloads widgets once, not once per source. */
    private suspend fun refreshTogether(sources: Set<DataSource>, quiet: Boolean) {
        val changed = coroutineScope {
            DataSource.entries.filter { it in sources }
                .map { source -> async { refresh(source, reloadWidgets = false, quiet = quiet) } }
                .awaitAll()
                .any { it }
        }
        if (changed) hooks.reloadWidgets()
    }

    private fun isStale(source: DataSource, minimumAgeMillis: Long): Boolean {
        val fetchedAt = store.dashboard.updatedAt(source) ?: return true
        return now().toEpochMilli() - fetchedAt.toEpochMilli() >= minimumAgeMillis
    }

    /** Picks a city, or null for the device location, then fetches its forecast. */
    suspend fun setWeatherLocation(location: WeatherLocation?) = withContext(dispatcher) {
        if (isResetting || activity(DataSource.WEATHER).isBusy) return@withContext
        if (location != null) store.weatherLocation.save(location) else store.weatherLocation.clear()
        if (DataSource.WEATHER in current.connectedSources) {
            refresh(DataSource.WEATHER)
        } else {
            connect(DataSource.WEATHER)
        }
    }

    /** Background work and widgets refresh the shared cache; show what they fetched. */
    suspend fun reloadFromStore() = withContext(dispatcher) { reloadFromStoreNow() }

    private fun reloadFromStoreNow() {
        synchronizeFromStore()
        for (source in current.connectedSources) {
            if (activity(source).isBusy) continue
            val error = store.dashboard.error(source)
            if (error != null) {
                setActivity(source, Activity.Failed(error))
            } else if (activity(source) is Activity.Failed) {
                setActivity(source, Activity.Idle)
            }
        }
    }

    /** Called when the app comes to the front. */
    suspend fun refreshIfNeeded(minimumIntervalMillis: Long = 5 * 60 * 1000L) = withContext(dispatcher) {
        reloadFromStoreNow()
        val guard = connectors[DataSource.CALENDAR] as? SnapshotGuard
        if (guard != null && guard.clearSnapshotIfAccessUnavailable()) calendarPreferencesDidChangeNow()
        currentCoroutineContext().ensureActive()
        refreshTogether(current.connectedSources.filter { isStale(it, minimumIntervalMillis) }.toSet(), quiet = false)
    }

    suspend fun deleteLocalData() = withContext(dispatcher) {
        if (isResetting) return@withContext
        isResetting = true
        resetRevision += 1
        try {
            operationIDs.clear()
            // A pending authorization cannot restore a deleted snapshot.
            store.deleteLocalData()
            mutableState.value = State(isDeletingLocalData = true)
            hooks.cancelBackgroundRefresh()
            hooks.reloadWidgets()
            // Clear credentials even if the account never finished connecting.
            val result = directOuraConnector.disconnect()
            mutableState.update { it.copy(dataDeletionResult = result) }
            record(result, DataSource.OURA)
            for (connector in connectors.values) connector.disconnect()
        } finally {
            isResetting = false
            mutableState.update { it.copy(isDeletingLocalData = false) }
        }
    }

    suspend fun calendarPreferencesDidChange() = withContext(dispatcher) { calendarPreferencesDidChangeNow() }

    private fun calendarPreferencesDidChangeNow() {
        operationIDs[DataSource.CALENDAR] = Any()
        setActivity(DataSource.CALENDAR, Activity.Idle)
        synchronizeFromStore()
    }

    suspend fun applyCalendarPreferences() = withContext(dispatcher) {
        calendarPreferencesDidChangeNow()
        refresh(DataSource.CALENDAR)
    }

    private fun canPublish(source: DataSource, operation: Any, calendarRevision: Int): Boolean {
        if (operationIDs[source] !== operation) return false
        if (source == DataSource.CALENDAR && calendarRevision != store.calendar.revision) {
            setActivity(source, Activity.Idle)
            synchronizeFromStore()
            return false
        }
        return true
    }

    private fun record(result: DisconnectResult, source: DataSource) {
        // Disconnecting the Health Connect projection says nothing about a
        // previous direct account's credentials or remote grant.
        val previous = current.disconnectResults[DataSource.OURA]
        if (source == DataSource.OURA &&
            result.remoteAuthorization == DisconnectResult.RemoteAuthorization.NOT_APPLICABLE &&
            previous != null && previous.remoteAuthorization != DisconnectResult.RemoteAuthorization.NOT_APPLICABLE
        ) {
            return
        }
        mutableState.update { it.copy(disconnectResults = it.disconnectResults + (source to result)) }
        if (source == DataSource.OURA) store.ouraDisconnectResult = result
    }

    /**
     * Returns true when the error was recorded in the shared cache, which the
     * caller then shows in widgets.
     */
    private fun handle(error: Exception, source: DataSource, operation: Any, quiet: Boolean, previous: Activity): Boolean {
        if (operationIDs[source] !== operation) return false
        if (error is CancellationException) {
            setActivity(source, Activity.Idle)
            return false
        }
        val unreadable = (error as? ConnectorException)?.kind == ConnectorException.Kind.TEMPORARILY_UNREADABLE
        if (quiet || unreadable) {
            setActivity(source, if (previous is Activity.Failed) previous else Activity.Idle)
            return false
        }
        val message = error.userMessage()
        setActivity(source, Activity.Failed(message))
        store.dashboard.recordError(message, source)
        return true
    }

    private fun synchronizeFromStore() {
        mutableState.update { it.copy(metrics = store.dashboard.metrics, updatedAt = store.dashboard.updatedAt) }
    }
}
