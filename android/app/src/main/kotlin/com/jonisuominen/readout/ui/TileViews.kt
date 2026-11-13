package com.jonisuominen.readout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jonisuominen.readout.core.DashboardFreshness
import com.jonisuominen.readout.core.DashboardMetric
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.ReadoutStore
import com.jonisuominen.readout.core.TextRun
import com.jonisuominen.readout.core.TextTone
import com.jonisuominen.readout.core.TileContext
import com.jonisuominen.readout.core.TileGridGeometry
import com.jonisuominen.readout.core.TileIndicator
import com.jonisuominen.readout.core.TilePresentation
import com.jonisuominen.readout.core.TilePresenter
import com.jonisuominen.readout.core.WidgetAppearance
import com.jonisuominen.readout.core.WidgetCanvas
import com.jonisuominen.readout.core.WidgetDesign
import com.jonisuominen.readout.core.WidgetPrivacy
import com.jonisuominen.readout.core.WidgetPrivacyMode
import com.jonisuominen.readout.core.WidgetTile
import java.time.Instant

/** A widget appearance's colors, resolved for the current theme. */
class TileColors(appearance: WidgetAppearance, isDark: Boolean) {
    private val appearance = appearance
    private val dark = isDark
    val ink: Color = appearance.ink.resolve(isDark).toColor()
    val secondary: Color = appearance.secondaryInk.resolve(isDark).toColor()
    val warning: Color = appearance.warning.resolve(isDark).toColor()
    fun tint(name: String): Color = appearance.tint(name).resolve(dark).toColor()
}

@Composable
fun rememberTileColors(appearance: WidgetAppearance): TileColors {
    val dark = isSystemInDarkTheme()
    return remember(appearance, dark) { TileColors(appearance, dark) }
}

/** The widget background for in-app previews, clipped to the widget's corners. */
@Composable
fun Modifier.widgetPreviewBackground(appearance: WidgetAppearance, cornerRadius: Float): Modifier {
    val shape = RoundedCornerShape(cornerRadius.dp)
    val dark = isSystemInDarkTheme()
    val stops = appearance.gradientStops
    val solid = appearance.solidBackground
    val filled = when {
        stops.isNotEmpty() -> background(Brush.linearGradient(stops.map { it.toColor() }), shape)
        solid != null -> background(solid.resolve(dark).toColor(), shape)
        // A transparent widget sits on a stand-in wallpaper, otherwise
        // nothing would show where it ends.
        else -> background(
            Brush.linearGradient(listOf(Color(0xFF5856D6).copy(alpha = 0.5f), Color(0xFF30B0C7).copy(alpha = 0.5f))),
            shape,
        )
    }
    return clip(shape).then(filled).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
}

/** What the previews show: real values when anything is connected, samples otherwise. */
fun previewMetrics(metrics: List<DashboardMetric>, connectedSources: Set<DataSource>, now: Instant, env: DisplayEnvironment): List<DashboardMetric> =
    WidgetPrivacy.render(if (connectedSources.isEmpty()) DashboardMetric.samples(now) else metrics, WidgetPrivacyMode.SHOW_VALUES, now, env)

fun previewContext(
    store: ReadoutStore,
    design: WidgetDesign,
    shownMetrics: List<DashboardMetric>,
    connectedSources: Set<DataSource>,
    now: Instant,
    env: DisplayEnvironment,
    scale: Float,
): TileContext {
    val tileIDs = design.tiles.map { it.metricID }.toSet()
    val shown = shownMetrics.filter { it.id in tileIDs }
    // Sample values were never fetched; give them a fresh age so the refresh tile looks as it would.
    val freshness = if (connectedSources.isEmpty()) {
        DashboardFreshness(shown, DataSource.entries.associateWith { now }, emptyMap())
    } else {
        store.freshness(shown)
    }
    return TileContext(
        now = now,
        freshness = freshness,
        privacyMode = WidgetPrivacyMode.SHOW_VALUES,
        connectedSources = connectedSources.ifEmpty { DataSource.entries.toSet() },
        showsUnavailable = true,
        scale = scale,
        env = env,
    )
}

/** A widget-sized, non-interactive rendering of a layout. */
@Composable
fun LayoutPreview(
    store: ReadoutStore,
    design: WidgetDesign,
    metrics: List<DashboardMetric>,
    connectedSources: Set<DataSource>,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val env = DisplayEnvironment.current
    val now = remember(metrics, design) { Instant.now() }
    val shown = remember(metrics, connectedSources, now) { previewMetrics(metrics, connectedSources, now, env) }
    val context = remember(design, shown, connectedSources, scale) {
        previewContext(store, design, shown, connectedSources, now, env, scale)
    }
    val canvas = design.canvas
    val width = canvas.previewWidth * scale
    val height = canvas.previewHeight * scale
    val margin = WidgetCanvas.CONTENT_MARGIN * scale
    val colors = rememberTileColors(design.appearance)
    Box(
        modifier = modifier
            .size(width.dp, height.dp)
            .widgetPreviewBackground(design.appearance, 22 * scale)
            .padding(margin.dp)
            .clearAndSetSemantics { contentDescription = "${design.name} layout preview" },
    ) {
        val grid = TileGridGeometry(width - 2 * margin, height - 2 * margin, canvas, WidgetCanvas.TILE_SPACING * scale)
        for (tile in design.visibleTiles(canvas)) {
            val frame = grid.frame(tile)
            Box(modifier = Modifier.offset(frame.x.dp, frame.y.dp).size(frame.width.dp, frame.height.dp)) {
                TileView(tile, shown.firstOrNull { it.id == tile.metricID }, context, colors, frame.width, frame.height, tile.endColumn == canvas.columns)
            }
        }
    }
}

/** One tile, drawn the way the widget draws it. */
@Composable
fun TileView(
    tile: WidgetTile,
    metric: DashboardMetric?,
    context: TileContext,
    colors: TileColors,
    width: Float,
    height: Float,
    isTrailing: Boolean,
) {
    val presentation = TilePresenter.present(tile, metric, context, width, height, isTrailing)
    val scale = context.scale
    Box(modifier = Modifier.fillMaxSize().alpha(if (presentation.dimmed) 0.45f else 1f)) {
        when (presentation) {
            is TilePresentation.Hidden -> Unit
            is TilePresentation.Compact -> Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
            ) {
                presentation.indicator?.let { Indicator(it, colors) }
                TileText(presentation.value, presentation.valueSize, colors.ink, weight = FontWeight.SemiBold)
                presentation.detail?.let {
                    TileText(it, presentation.detailSize, colors.secondary, modifier = Modifier.weight(1f))
                }
            }
            is TilePresentation.Stacked -> Column(modifier = Modifier.fillMaxSize()) {
                presentation.indicator?.let { Indicator(it, colors) }
                TileText(presentation.value, presentation.valueSize, colors.ink, weight = FontWeight.SemiBold)
                presentation.detail?.let { TileText(it, presentation.detailSize, colors.secondary, maxLines = presentation.detailLines) }
            }
            is TilePresentation.Detailed -> Column(modifier = Modifier.fillMaxSize()) {
                if (presentation.indicator != null || presentation.title != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((3 * scale).dp)) {
                        presentation.indicator?.let { Indicator(it, colors) }
                        presentation.title?.let { TileText(it, presentation.titleSize, colors.secondary, weight = FontWeight.Medium) }
                    }
                }
                TileText(presentation.value, presentation.valueSize, colors.ink, weight = FontWeight.SemiBold)
                presentation.detail?.let { TileText(it, presentation.detailSize, colors.secondary, maxLines = presentation.detailLines) }
            }
            is TilePresentation.Line -> {
                if (presentation.maxLines <= 1 || presentation.runs.size < 2) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = if (presentation.topAligned) Alignment.Top else Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((4 * scale).dp),
                    ) {
                        presentation.indicator?.let { Indicator(it, colors) }
                        RunsText(presentation.runs, presentation.fontSize, colors, maxLines = 1)
                    }
                } else {
                    // Matches the widget: time and countdown first, the title wraps below.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                            presentation.indicator?.let { Indicator(it, colors) }
                            RunsText(listOf(presentation.runs.first()) + presentation.runs.drop(2), presentation.fontSize, colors, maxLines = 1)
                        }
                        TileText(presentation.runs[1].text.trim(), presentation.fontSize, colors.ink, maxLines = presentation.maxLines - 1)
                    }
                }
            }
            is TilePresentation.NarrowEvent -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = if (presentation.title != null) Arrangement.Top else Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                    presentation.indicator?.let { Indicator(it, colors) }
                    TileText(presentation.whenText, presentation.fontSize, colors.ink, weight = FontWeight.Bold)
                }
                presentation.title?.let { TileText(it, presentation.titleSize, colors.ink, maxLines = presentation.titleLines) }
            }
            is TilePresentation.Agenda -> Column(modifier = Modifier.fillMaxSize()) {
                for (runs in presentation.rows) {
                    Row(modifier = Modifier.fillMaxWidth().height(presentation.rowHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                        RunsText(runs, presentation.fontSize, colors, maxLines = 1)
                    }
                }
            }
            is TilePresentation.Sync -> {
                val color = if (presentation.warning) colors.warning else colors.secondary
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (presentation.trailing) Arrangement.End else Arrangement.Start,
                ) {
                    Icon(
                        painter = painterResource(SymbolIcons.resource(presentation.symbol)),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(presentation.fontSize.dp),
                    )
                    presentation.label?.let {
                        Spacer(modifier = Modifier.width((3 * scale).dp))
                        TileText(it, presentation.fontSize, color, weight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Sizes are in dp so previews match the widget whatever the font scale is. */
@Composable
private fun fontSize(size: Float): TextUnit = with(LocalDensity.current) { size.dp.toSp() }

@Composable
private fun TileText(
    text: String,
    size: Float,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    val unit = fontSize(size)
    Text(
        text = text,
        color = color,
        fontSize = unit,
        lineHeight = unit * 1.2f,
        // Widget text has no extra letter spacing; the Material default would make values wider.
        letterSpacing = 0.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun RunsText(runs: List<TextRun>, size: Float, colors: TileColors, maxLines: Int) {
    val unit = fontSize(size)
    Text(
        text = buildAnnotatedString {
            for (run in runs) {
                withStyle(
                    SpanStyle(
                        color = if (run.tone == TextTone.SECONDARY) colors.secondary else colors.ink,
                        fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                ) { append(run.text) }
            }
        },
        fontSize = unit,
        lineHeight = unit * 1.2f,
        letterSpacing = 0.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Indicator(indicator: TileIndicator, colors: TileColors) {
    when (indicator) {
        is TileIndicator.Icon -> Icon(
            painter = painterResource(SymbolIcons.resource(indicator.symbol)),
            contentDescription = null,
            tint = colors.tint(indicator.tint),
            modifier = Modifier.size(indicator.size.dp),
        )
        is TileIndicator.Label -> TileText(indicator.text, indicator.size, colors.tint(indicator.tint), weight = FontWeight.ExtraBold)
    }
}
