package com.jonisuominen.readout.core

import java.time.Instant
import kotlin.math.roundToInt

/** Everything a tile needs besides its own metric. */
data class TileContext(
    val now: Instant,
    val freshness: DashboardFreshness,
    val privacyMode: WidgetPrivacyMode,
    val connectedSources: Set<DataSource>,
    /** Previews draw tiles of unconnected sources dimmed instead of empty. */
    val showsUnavailable: Boolean = false,
    /** Multiplies fonts and spacing for enlarged editor previews. */
    val scale: Float = 1f,
    val env: DisplayEnvironment = DisplayEnvironment.current,
)

data class TileFrame(val x: Float, val y: Float, val width: Float, val height: Float)

/** Cell sizes for a canvas drawn into a given area, in dp. */
class TileGridGeometry(width: Float, height: Float, private val canvas: WidgetCanvas, val spacing: Float) {
    val cellWidth: Float = maxOf(0f, (width - spacing * (canvas.columns - 1)) / canvas.columns)
    val cellHeight: Float = maxOf(0f, (height - spacing * (canvas.rows - 1)) / canvas.rows)

    /** One cell plus its gap: how far a tile moves to cross a cell. */
    val pitchWidth: Float get() = maxOf(1f, cellWidth + spacing)
    val pitchHeight: Float get() = maxOf(1f, cellHeight + spacing)

    fun frame(tile: WidgetTile): TileFrame = TileFrame(
        x = tile.column * (cellWidth + spacing),
        y = tile.row * (cellHeight + spacing),
        width = tile.width * cellWidth + (tile.width - 1) * spacing,
        height = tile.height * cellHeight + (tile.height - 1) * spacing,
    )

    /** Whole cells covered by a drag. */
    fun cells(dx: Float, dy: Float): Pair<Int, Int> =
        (dx / pitchWidth).roundToInt() to (dy / pitchHeight).roundToInt()
}

enum class TextTone { PRIMARY, SECONDARY }

data class TextRun(val text: String, val bold: Boolean = false, val tone: TextTone = TextTone.PRIMARY)

/** The small mark that identifies a tile: an icon, or a short label. */
sealed interface TileIndicator {
    val size: Float
    val tint: String

    data class Icon(val symbol: String, override val size: Float, override val tint: String) : TileIndicator
    data class Label(val text: String, override val size: Float, override val tint: String) : TileIndicator
}

/**
 * What a tile shows at its size, decided once so the widget and the in-app
 * previews always agree. Sizes are in dp.
 */
sealed interface TilePresentation {
    val accessibilityText: String
    val dimmed: Boolean

    /** A tile for a source that is not connected keeps its space empty. */
    data object Hidden : TilePresentation {
        override val accessibilityText = ""
        override val dimmed = false
    }

    /** One row: indicator, value, and a caption when there is room. */
    data class Compact(
        val indicator: TileIndicator?,
        val value: String,
        val valueSize: Float,
        val detail: String?,
        val detailSize: Float,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /** A narrow, tall tile: indicator above the value, caption below. */
    data class Stacked(
        val indicator: TileIndicator?,
        val value: String,
        val valueSize: Float,
        val detail: String?,
        val detailSize: Float,
        val detailLines: Int,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /** A full card: indicator and title, value, and the whole detail. */
    data class Detailed(
        val indicator: TileIndicator?,
        val title: String?,
        val titleSize: Float,
        val value: String,
        val valueSize: Float,
        val detail: String?,
        val detailSize: Float,
        val detailLines: Int,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /** Running text beside an indicator, such as "14:30 Design sync · in 25m". */
    data class Line(
        val indicator: TileIndicator?,
        val runs: List<TextRun>,
        val fontSize: Float,
        val maxLines: Int,
        val topAligned: Boolean,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /** A one-column event: when it starts, and its title when the tile is tall. */
    data class NarrowEvent(
        val indicator: TileIndicator?,
        val whenText: String,
        val fontSize: Float,
        val title: String?,
        val titleSize: Float,
        val titleLines: Int,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /** One event per grid row. */
    data class Agenda(
        val rows: List<List<TextRun>>,
        val rowHeight: Float,
        val fontSize: Float,
        override val accessibilityText: String,
        override val dimmed: Boolean,
    ) : TilePresentation

    /**
     * The refresh button. Its label is the age of the oldest shown data; it
     * is drawn in the warning color when data is stale or a refresh failed.
     */
    data class Sync(
        val symbol: String,
        val label: String?,
        val fontSize: Float,
        val warning: Boolean,
        val trailing: Boolean,
        override val accessibilityText: String,
    ) : TilePresentation {
        override val dimmed = false
    }
}

/** Shrinks text that would not fit, like a minimum scale factor. */
object TextFit {
    /** Estimated width of semibold text at `size`, without measuring a font. */
    fun estimatedWidth(text: String, size: Float): Float {
        var units = 0f
        for (character in text) {
            units += when {
                character.isDigit() -> 0.60f
                character == ' ' -> 0.28f
                character == '°' -> 0.40f
                character == '%' -> 0.85f
                character in ".,:·'/" -> 0.30f
                character == '—' -> 0.90f
                character.isUpperCase() -> 0.70f
                character == 'm' || character == 'w' -> 0.88f
                character in "iljtf" -> 0.34f
                else -> 0.56f
            }
        }
        return units * size
    }

    fun size(text: String, size: Float, availableWidth: Float, minimumScale: Float): Float {
        // A margin for fonts that run wider than the estimate.
        val estimated = estimatedWidth(text, size) * 1.08f
        if (estimated <= availableWidth || estimated <= 0f) return size
        return maxOf(size * minimumScale, size * availableWidth / estimated)
    }
}

object TilePresenter {
    fun present(
        tile: WidgetTile,
        metric: DashboardMetric?,
        context: TileContext,
        width: Float,
        height: Float,
        isTrailing: Boolean = false,
    ): TilePresentation {
        if (tile.isSync) return sync(tile, context, height, isTrailing)
        val descriptor = MetricCatalog.descriptor(tile.metricID) ?: return TilePresentation.Hidden
        if (metric == null && !context.showsUnavailable && descriptor.source !in context.connectedSources) {
            return TilePresentation.Hidden
        }
        return MetricTile(tile, descriptor, metric, context, width, height).present()
    }

    private fun clamp(value: Float, lower: Float, upper: Float): Float = minOf(maxOf(value, lower), upper)

    /**
     * How much taller a line of widget text is than its font size. Android
     * text views pad each line, so values are sized to leave that room.
     */
    private const val LINE_HEIGHT = 1.4f

    /** The height one caption line takes, in dp at scale 1. */
    private const val CAPTION_LINE = 14.5f

    private fun sync(tile: WidgetTile, context: TileContext, height: Float, isTrailing: Boolean): TilePresentation.Sync {
        val freshness = context.freshness
        val scale = context.scale
        val isStale = freshness.isStale(context.now)
        val fetchedAt = freshness.oldestFetchAt
        val age = when {
            tile.style != TileStyle.STANDARD || !freshness.hasMetrics -> null
            fetchedAt == null -> "—"
            tile.width >= 2 && tile.height >= 2 -> UpdatedLabel.long(fetchedAt, context.now)
            else -> UpdatedLabel.short(fetchedAt, context.now)
        }
        val spoken = when {
            freshness.hasError -> "Refresh failed"
            fetchedAt == null -> "Not refreshed yet"
            else -> {
                val minutes = UpdatedLabel.minutes(fetchedAt, context.now)
                "Updated $minutes minute${if (minutes == 1) "" else "s"} ago${if (isStale) ", stale" else ""}"
            }
        }
        return TilePresentation.Sync(
            symbol = if (freshness.hasError) Symbols.REFRESH_ERROR else Symbols.REFRESH,
            label = age,
            fontSize = clamp(height * 0.5f, 10 * scale, 15 * scale),
            warning = freshness.hasError || isStale,
            trailing = isTrailing,
            accessibilityText = "Refresh. $spoken",
        )
    }

    private class MetricTile(
        val tile: WidgetTile,
        val descriptor: MetricDescriptor,
        val metric: DashboardMetric?,
        val context: TileContext,
        val width: Float,
        val height: Float,
    ) {
        val scale = context.scale
        val value = metric?.value ?: "—"
        val title = metric?.title ?: descriptor.name
        val detail = metric?.detail ?: ""
        val showsIndicator = tile.style == TileStyle.STANDARD
        val tint = metric?.tint ?: "gray"
        val dimmed = metric == null

        /** The first part of a detail such as "Moderate · peak 6 at 13:00". */
        val shortDetail = detail.split(" · ").firstOrNull() ?: detail

        fun present(): TilePresentation = when {
            descriptor.id == "nextEvent" -> event()
            descriptor.id == "agenda" -> agenda()
            tile.height >= 2 && tile.width >= 2 -> detailed()
            tile.height >= 2 -> stacked()
            else -> compact()
        }

        /**
         * Weather symbols identify most tiles. UV and tomorrow use short text
         * labels in small tiles, where a sun or cloud alone would be ambiguous.
         */
        fun indicator(size: Float, prefersText: Boolean): TileIndicator? {
            if (!showsIndicator) return null
            return when {
                prefersText && descriptor.id == "uv" -> TileIndicator.Label("UV", size * 0.7f, tint)
                prefersText && descriptor.id == "tomorrow" -> TileIndicator.Label("TMRW", size * 0.5f, tint)
                else -> TileIndicator.Icon(metric?.symbol ?: descriptor.symbol, size * 0.85f, tint)
            }
        }

        fun indicatorWidth(indicator: TileIndicator?): Float = when (indicator) {
            null -> 0f
            is TileIndicator.Icon -> indicator.size + 4 * scale
            is TileIndicator.Label -> TextFit.estimatedWidth(indicator.text, indicator.size) + 4 * scale
        }

        fun compact(): TilePresentation {
            val baseSize = clamp(height * 0.6f, 11 * scale, 22 * scale)
            val indicator = indicator(baseSize, prefersText = true)
            val available = width - indicatorWidth(indicator)
            val valueSize = TextFit.size(value, baseSize, available, 0.5f)
            val caption = when {
                tile.width < 2 || detail.isEmpty() -> null
                tile.width >= 3 -> detail
                else -> shortDetail
            }
            // A caption needs real room beside the value, or it is only an ellipsis.
            val remaining = available - TextFit.estimatedWidth(value, valueSize) - 4 * scale
            return TilePresentation.Compact(
                indicator = indicator,
                value = value,
                valueSize = valueSize,
                detail = caption?.takeIf { remaining >= 28 * scale },
                detailSize = 10 * scale,
                accessibilityText = accessibilityText(),
                dimmed = dimmed,
            )
        }

        fun stacked(): TilePresentation {
            val indicator = indicator(13 * scale, prefersText = true)
            val caption = shortDetail.takeIf { height >= 56 * scale && it.isNotEmpty() }
            val captionLines = if (tile.height >= 3) 2 else 1
            // The value takes the height the indicator and caption leave over.
            val taken = (if (indicator != null) 14 * scale else 0f) +
                (if (caption != null) CAPTION_LINE * scale * captionLines else 0f)
            val baseSize = clamp((height - taken) / LINE_HEIGHT, 14 * scale, 40 * scale)
            return TilePresentation.Stacked(
                indicator = indicator,
                value = value,
                valueSize = TextFit.size(value, baseSize, width, 0.4f),
                detail = caption,
                detailSize = 10 * scale,
                detailLines = captionLines,
                accessibilityText = accessibilityText(),
                dimmed = dimmed,
            )
        }

        fun detailed(): TilePresentation {
            val caption = detail.takeIf { it.isNotEmpty() }
            val captionLines = if (tile.height >= 3) 2 else 1
            val taken = (if (showsIndicator) CAPTION_LINE * scale else 0f) +
                (if (caption != null) CAPTION_LINE * scale * captionLines else 0f)
            val baseSize = clamp((height - taken) / LINE_HEIGHT, 14 * scale, 46 * scale)
            return TilePresentation.Detailed(
                indicator = indicator(11 * scale, prefersText = false),
                title = title.takeIf { showsIndicator },
                titleSize = 10 * scale,
                value = value,
                valueSize = TextFit.size(value, baseSize, width, 0.5f),
                detail = caption,
                detailSize = 10.5f * scale,
                detailLines = captionLines,
                accessibilityText = accessibilityText(),
                dimmed = dimmed,
            )
        }

        /**
         * The start time and title always share one line, followed by when it
         * happens relative to now.
         */
        fun event(): TilePresentation {
            // Taller event tiles use a larger font rather than leaving empty rows.
            val fontSize = if (tile.height == 1) {
                clamp(height * 0.5f, 11 * scale, 15 * scale)
            } else {
                clamp(height * 0.3f, 14 * scale, 20 * scale)
            }
            val indicator = indicator(fontSize, prefersText = false)
            val start = metric?.observedAt
            if (metric == null || start == null) {
                return emptyLine(indicator, fontSize, topAligned = false)
            }
            val now = context.now
            val env = context.env
            if (tile.width == 1) {
                return TilePresentation.NarrowEvent(
                    indicator = indicator,
                    whenText = EventTiming.compactText(start, metric.endsAt, now, env),
                    fontSize = fontSize,
                    title = metric.value.takeIf { tile.height >= 2 },
                    titleSize = 11 * scale,
                    titleLines = tile.height,
                    accessibilityText = accessibilityText(),
                    dimmed = false,
                )
            }
            val narrow = tile.width == 2 && tile.height == 1
            val ongoing = EventTiming.isOngoing(start, metric.endsAt, now)
            val whenText = when {
                ongoing -> "Now"
                narrow -> EventTiming.compactText(start, metric.endsAt, now, env)
                else -> EventTiming.startText(start, now, env)
            }
            val runs = ArrayList<TextRun>()
            runs += TextRun(whenText, bold = true)
            runs += TextRun(" " + metric.value)
            if (!narrow) {
                runs += TextRun(" · " + EventTiming.relativeText(start, metric.endsAt, now, env), tone = TextTone.SECONDARY)
            }
            return TilePresentation.Line(
                indicator = indicator,
                runs = runs,
                fontSize = fontSize,
                maxLines = tile.height,
                topAligned = tile.height >= 2,
                accessibilityText = accessibilityText(),
                dimmed = false,
            )
        }

        /**
         * One event per grid row: when it starts, then its title. A one-column
         * tile shows only the times.
         */
        fun agenda(): TilePresentation {
            val rowHeight = height / maxOf(1, tile.height)
            val fontSize = clamp(rowHeight * 0.5f, 10 * scale, 14 * scale)
            val items = metric?.items.orEmpty().take(maxOf(1, tile.height))
            if (items.isEmpty()) {
                return emptyLine(indicator(fontSize, prefersText = false), fontSize, topAligned = tile.height >= 2)
            }
            val rows = items.map { item ->
                val runs = ArrayList<TextRun>()
                runs += TextRun(EventTiming.agendaText(item, context.now, context.env), bold = true)
                if (tile.width >= 2) runs += TextRun(" " + (item.title ?: "Busy"))
                runs
            }
            return TilePresentation.Agenda(rows, rowHeight, fontSize, accessibilityText(), dimmed = false)
        }

        fun emptyLine(indicator: TileIndicator?, fontSize: Float, topAligned: Boolean): TilePresentation {
            val text = when {
                metric == null -> "—"
                tile.width >= 2 -> WidgetPrivacy.NO_EVENTS
                else -> "None"
            }
            return TilePresentation.Line(
                indicator = indicator,
                runs = listOf(TextRun(text, tone = TextTone.SECONDARY)),
                fontSize = fontSize,
                maxLines = 1,
                topAligned = topAligned,
                accessibilityText = accessibilityText(),
                dimmed = dimmed,
            )
        }

        fun accessibilityText(): String {
            val metric = metric ?: return "${descriptor.name}, no data"
            val start = metric.observedAt
            if (descriptor.id == "nextEvent" && start != null) {
                return "Next event, ${metric.value}, ${EventTiming.startText(start, context.now, context.env)}, " +
                    EventTiming.relativeText(start, metric.endsAt, context.now, context.env)
            }
            val items = metric.items
            if (descriptor.id == "agenda" && !items.isNullOrEmpty()) {
                val lines = items.take(maxOf(1, tile.height)).map {
                    "${EventTiming.agendaText(it, context.now, context.env)} ${it.title ?: "Busy"}"
                }
                return (listOf("Agenda") + lines).joinToString(", ")
            }
            return listOf(title, value, detail).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }
}

/** What one widget draws: its layout, privacy-rendered metrics, and their freshness. */
class WidgetSnapshot(
    val now: Instant,
    val design: WidgetDesign,
    val canvas: WidgetCanvas,
    /** Privacy-rendered metrics for this widget's privacy mode. */
    val metrics: List<DashboardMetric>,
    val configuration: WidgetInstanceConfiguration,
    val connectedSources: Set<DataSource>,
    val updatedAtBySource: Map<DataSource, Instant>,
    val errorsBySource: Map<DataSource, String>,
) {
    /** Metrics this widget actually draws; freshness covers only these. */
    val displayedMetrics: List<DashboardMetric>
        get() {
            val ids = design.tiles.map { it.metricID }.toSet()
            return metrics.filter { it.id in ids }
        }

    val freshness: DashboardFreshness
        get() = DashboardFreshness(displayedMetrics, updatedAtBySource, errorsBySource)

    /** True before anything is connected: the widget shows a hint instead of tiles. */
    val isEmpty: Boolean get() = connectedSources.isEmpty() && metrics.isEmpty()

    fun context(env: DisplayEnvironment = DisplayEnvironment.current): TileContext = TileContext(
        now = now,
        freshness = freshness,
        privacyMode = configuration.privacyMode,
        connectedSources = connectedSources,
        env = env,
    )

    /**
     * When this widget should be redrawn from a fresh fetch: once a shown
     * value expires, such as the next event ending or today's totals at midnight.
     */
    val nextExpiry: Instant?
        get() = metrics.mapNotNull { it.expiresAt }.filter { it.isAfter(now.plusSeconds(60)) }.minOrNull()

    companion object {
        fun cached(
            store: ReadoutStore,
            configuration: WidgetInstanceConfiguration,
            canvas: WidgetCanvas,
            now: Instant = store.now(),
            env: DisplayEnvironment = DisplayEnvironment.current,
        ): WidgetSnapshot {
            val updates = HashMap<DataSource, Instant>()
            val errors = HashMap<DataSource, String>()
            for (source in DataSource.entries) {
                store.dashboard.updatedAt(source)?.let { updates[source] = it }
                store.dashboard.error(source)?.let { errors[source] = it }
            }
            return WidgetSnapshot(
                now = now,
                design = store.designs.resolvedDesign(configuration.designID, canvas),
                canvas = canvas,
                metrics = configuration.renderedMetrics(store.dashboard.metrics.filter { !it.isExpired(now) }, now, env),
                configuration = configuration,
                connectedSources = store.dashboard.connectedSources,
                updatedAtBySource = updates,
                errorsBySource = errors,
            )
        }

        /** Sample values, for the widget picker and for layouts before anything is connected. */
        fun preview(
            design: WidgetDesign,
            now: Instant = Instant.now(),
            env: DisplayEnvironment = DisplayEnvironment.current,
        ): WidgetSnapshot = WidgetSnapshot(
            now = now,
            design = design,
            canvas = design.canvas,
            metrics = WidgetInstanceConfiguration.homeDefault.renderedMetrics(DashboardMetric.samples(now), now, env),
            configuration = WidgetInstanceConfiguration.homeDefault,
            connectedSources = DataSource.entries.toSet(),
            updatedAtBySource = DataSource.entries.associateWith { now },
            errorsBySource = emptyMap(),
        )
    }
}
