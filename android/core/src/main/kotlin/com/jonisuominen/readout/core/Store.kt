package com.jonisuominen.readout.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/** String storage shared by the app, its widgets, and background work. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Null removes the key. */
    fun putString(key: String, value: String?)

    fun remove(key: String) = putString(key, null)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, String>()

    @Synchronized
    override fun getString(key: String): String? = values[key]

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The cached snapshot the app and widgets draw from. */
class DashboardStore(
    private val kv: KeyValueStore,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()

    var metrics: List<DashboardMetric>
        get() = synchronized(lock) {
            val current = now()
            readMetrics().filter { !it.isExpired(current) }
        }
        set(value) = synchronized(lock) { writeMetrics(value) }

    var connectedSources: Set<DataSource>
        get() = synchronized(lock) {
            decode(SOURCES_KEY, ListSerializer(String.serializer()))
                .orEmpty().mapNotNull(DataSource::fromKey).toSet()
        }
        set(value) = synchronized(lock) {
            kv.putString(SOURCES_KEY, ReadoutJson.encodeToString(ListSerializer(String.serializer()), value.map { it.key }.sorted()))
        }

    val updatedAt: Instant?
        get() = synchronized(lock) { kv.getString(UPDATED_AT_KEY)?.toLongOrNull()?.let(Instant::ofEpochMilli) }

    fun updatedAt(source: DataSource): Instant? =
        synchronized(lock) { readUpdates()[source.key]?.let(Instant::ofEpochMilli) }

    fun error(source: DataSource): String? = synchronized(lock) { readErrors()[source.key] }

    fun recordError(message: String, source: DataSource) = synchronized(lock) {
        writeErrors(readErrors() + (source.key to message))
    }

    fun replaceMetrics(newMetrics: List<DashboardMetric>, source: DataSource, fetchedAt: Instant = now()) = synchronized(lock) {
        val order = MetricCatalog.all.map { it.id }
        val current = readMetrics().filter { !it.isExpired(now()) && it.source != source } +
            newMetrics.filter { it.source == source }
        writeMetrics(current.sortedBy { metric -> order.indexOf(metric.id).let { if (it < 0) order.size else it } })
        writeUpdates(readUpdates() + (source.key to fetchedAt.toEpochMilli()))
        writeErrors(readErrors() - source.key)
        kv.putString(UPDATED_AT_KEY, fetchedAt.toEpochMilli().toString())
    }

    fun removeMetrics(source: DataSource) = synchronized(lock) {
        writeMetrics(readMetrics().filter { it.source != source })
        val updates = readUpdates() - source.key
        writeUpdates(updates)
        writeErrors(readErrors() - source.key)
        // Removing a source is not a data fetch.
        kv.putString(UPDATED_AT_KEY, updates.values.maxOrNull()?.toString())
    }

    fun clear() = synchronized(lock) {
        listOf(METRICS_KEY, SOURCES_KEY, UPDATED_AT_KEY, SOURCE_UPDATES_KEY, SOURCE_ERRORS_KEY).forEach(kv::remove)
    }

    private fun readMetrics(): List<DashboardMetric> =
        decode(METRICS_KEY, ListSerializer(DashboardMetric.serializer())).orEmpty()

    private fun writeMetrics(metrics: List<DashboardMetric>) {
        kv.putString(METRICS_KEY, ReadoutJson.encodeToString(ListSerializer(DashboardMetric.serializer()), metrics))
    }

    private fun readUpdates(): Map<String, Long> =
        decode(SOURCE_UPDATES_KEY, MapSerializer(String.serializer(), Long.serializer())).orEmpty()

    private fun writeUpdates(updates: Map<String, Long>) {
        kv.putString(SOURCE_UPDATES_KEY, ReadoutJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), updates))
    }

    private fun readErrors(): Map<String, String> =
        decode(SOURCE_ERRORS_KEY, MapSerializer(String.serializer(), String.serializer())).orEmpty()

    private fun writeErrors(errors: Map<String, String>) {
        kv.putString(SOURCE_ERRORS_KEY, ReadoutJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), errors))
    }

    private fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val raw = kv.getString(key) ?: return null
        return try {
            ReadoutJson.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val METRICS_KEY = "dashboard.metrics"
        const val SOURCES_KEY = "dashboard.sources"
        const val UPDATED_AT_KEY = "dashboard.updatedAt"
        const val SOURCE_UPDATES_KEY = "dashboard.sourceUpdates"
        const val SOURCE_ERRORS_KEY = "dashboard.sourceErrors"
    }
}

/**
 * Freshness describes the last successful source fetch, not a widget redraw
 * or the date of the underlying measurement. One stale source makes the
 * displayed group stale even when another source fetched successfully.
 */
class DashboardFreshness(
    metrics: List<DashboardMetric>,
    updatedAtBySource: Map<DataSource, Instant>,
    errorsBySource: Map<DataSource, String>,
) {
    val oldestFetchAt: Instant?
    val hasError: Boolean
    val hasMissingFetch: Boolean
    val hasMetrics: Boolean = metrics.isNotEmpty()

    init {
        val sources = metrics.map { it.source }.toSet()
        val dates = sources.mapNotNull { updatedAtBySource[it] }
        oldestFetchAt = dates.minOrNull()
        hasError = sources.any { errorsBySource[it] != null }
        hasMissingFetch = dates.size < sources.size
    }

    fun isStale(at: Instant): Boolean {
        if (!hasMetrics) return false
        val oldest = oldestFetchAt
        if (hasError || hasMissingFetch || oldest == null) return true
        return at.toEpochMilli() - oldest.toEpochMilli() >= STALE_MILLIS
    }

    companion object {
        const val STALE_MILLIS = 60 * 60 * 1000L
    }
}

/**
 * Everything Readout keeps on the phone, over one key-value file. OAuth
 * tokens are not here; they have their own encrypted store.
 */
class ReadoutStore(
    val kv: KeyValueStore,
    val now: () -> Instant = Instant::now,
) {
    /** Called when the snapshot changes outside a fetch, so widgets redraw. */
    @Volatile
    var onWidgetsNeedReload: () -> Unit = {}

    val dashboard = DashboardStore(kv, now)
    val designs = WidgetDesignStore(kv)
    val units = UnitPreferences(kv)
    val weatherLocation = WeatherLocationStore(kv)
    val calendar = CalendarPreferences(kv, dashboard) { onWidgetsNeedReload() }

    /**
     * The saved Oura choice, read by background work to know whether Oura
     * data comes from Health Connect or the Oura account.
     */
    var ouraMethod: OuraConnectionMethod
        get() = OuraConnectionMethod.entries.firstOrNull { it.key == kv.getString(OURA_METHOD_KEY) }
            ?: OuraConnectionMethod.HEALTH_CONNECT
        set(value) = kv.putString(OURA_METHOD_KEY, value.key)

    /**
     * Cleanup status only, never account identifiers or credentials. A failed
     * credential deletion must stay visible after relaunch, so deleting local
     * data keeps it.
     */
    var ouraDisconnectResult: DisconnectResult?
        get() = kv.getString(OURA_DISCONNECT_RESULT_KEY)?.let {
            try {
                ReadoutJson.decodeFromString(DisconnectResult.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }
        set(value) = kv.putString(
            OURA_DISCONNECT_RESULT_KEY,
            value?.let { ReadoutJson.encodeToString(DisconnectResult.serializer(), it) },
        )

    fun freshness(metrics: List<DashboardMetric>): DashboardFreshness {
        val sources = metrics.map { it.source }.toSet()
        return DashboardFreshness(
            metrics,
            sources.mapNotNull { source -> dashboard.updatedAt(source)?.let { source to it } }.toMap(),
            sources.mapNotNull { source -> dashboard.error(source)?.let { source to it } }.toMap(),
        )
    }

    fun deleteLocalData() {
        dashboard.clear()
        calendar.reset()
        units.reset()
        designs.reset()
        weatherLocation.clear()
        kv.remove(OURA_METHOD_KEY)
    }

    private companion object {
        const val OURA_METHOD_KEY = "oura.connectionMethod"
        const val OURA_DISCONNECT_RESULT_KEY = "oura.disconnectResult"
    }
}
