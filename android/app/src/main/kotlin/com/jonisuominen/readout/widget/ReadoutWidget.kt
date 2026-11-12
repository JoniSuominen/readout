package com.jonisuominen.readout.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.jonisuominen.readout.MainActivity
import com.jonisuominen.readout.R
import com.jonisuominen.readout.container
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.TextRun
import com.jonisuominen.readout.core.TextTone
import com.jonisuominen.readout.core.ThemedColor
import com.jonisuominen.readout.core.TileGridGeometry
import com.jonisuominen.readout.core.TileIndicator
import com.jonisuominen.readout.core.TileLink
import com.jonisuominen.readout.core.TilePresentation
import com.jonisuominen.readout.core.TilePresenter
import com.jonisuominen.readout.core.WidgetAppearance
import com.jonisuominen.readout.core.WidgetCanvas
import com.jonisuominen.readout.core.WidgetSnapshot
import com.jonisuominen.readout.core.WidgetTile
import com.jonisuominen.readout.ui.SymbolIcons
import com.jonisuominen.readout.work.RefreshWorker

/** One widget per size. Each draws the layout chosen for it on a grid of tiles. */
abstract class ReadoutWidget(private val canvas: WidgetCanvas) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            // A redraw request bumps the revision; the cache is then read again.
            val revision by WidgetUpdater.revision.collectAsState()
            val env = DisplayEnvironment.current
            val snapshot = remember(revision) {
                WidgetSnapshot.cached(container.store, container.widgetConfigs.configuration(appWidgetId), canvas, env = env)
            }
            // Read with each redraw, so a changed font size setting is picked up.
            val fontScale = remember(revision) { context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f }
            WidgetContent(snapshot, env, fontScale)
        }
    }
}

class SmallWidget : ReadoutWidget(WidgetCanvas.SMALL)
class MediumWidget : ReadoutWidget(WidgetCanvas.MEDIUM)
class LargeWidget : ReadoutWidget(WidgetCanvas.LARGE)

abstract class ReadoutWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also runs after a restart, when the minute alarm is gone.
        WidgetUpdater.ensureTicking(context)
        if (context.container.store.dashboard.connectedSources.isNotEmpty()) RefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdater.ensureTicking(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        context.container.widgetConfigs.delete(appWidgetIds)
    }
}

class SmallWidgetReceiver : ReadoutWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

class MediumWidgetReceiver : ReadoutWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MediumWidget()
}

class LargeWidgetReceiver : ReadoutWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

/** The refresh tile: fetch every connected source, then redraw. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

/** A widget's colors, resolved for the phone's light and dark themes. */
private class WidgetColors(private val appearance: WidgetAppearance) {
    val ink = provider(appearance.ink)
    val secondary = provider(appearance.secondaryInk)
    val warning = provider(appearance.warning)

    fun tint(name: String): ColorProvider = provider(appearance.tint(name))

    private fun provider(color: ThemedColor): ColorProvider =
        if (color.day == color.night) ColorProvider(Color(color.day.argb)) else ColorProvider(day = Color(color.day.argb), night = Color(color.night.argb))
}

/** Font sizes are given in dp so the grid holds; this converts them for text. */
private class TextSizer(private val fontScale: Float) {
    fun sp(size: Float): TextUnit = (size / fontScale).sp
}

private fun backgroundResource(appearance: WidgetAppearance): Int? = when (appearance) {
    WidgetAppearance.SYSTEM -> R.drawable.widget_bg_system
    WidgetAppearance.LIGHT -> R.drawable.widget_bg_light
    WidgetAppearance.DARK -> R.drawable.widget_bg_dark
    WidgetAppearance.BLACK -> R.drawable.widget_bg_black
    WidgetAppearance.TRANSPARENT -> null
    WidgetAppearance.SKY -> R.drawable.widget_bg_sky
    WidgetAppearance.EMBER -> R.drawable.widget_bg_ember
}

private fun openAppIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** A tile link names only the metric, never its value. */
private fun tileIntent(context: Context, metricID: String): Intent {
    val link = TileLink.uri(metricID) ?: return openAppIntent(context)
    return Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot, env: DisplayEnvironment, fontScale: Float) {
    val context = LocalContext.current
    val size = LocalSize.current
    val appearance = snapshot.design.appearance
    val colors = WidgetColors(appearance)
    val sizer = TextSizer(fontScale)
    val background = backgroundResource(appearance)
    var root = GlanceModifier.fillMaxSize().appWidgetBackground()
    if (background != null) root = root.background(ImageProvider(background))

    Box(modifier = root) {
        if (snapshot.isEmpty) {
            EmptyWidget(colors, sizer, context)
        } else {
            val margin = WidgetCanvas.CONTENT_MARGIN
            val grid = TileGridGeometry(
                width = size.width.value - 2 * margin,
                height = size.height.value - 2 * margin,
                canvas = snapshot.canvas,
                spacing = WidgetCanvas.TILE_SPACING,
            )
            val tileContext = snapshot.context(env)
            Box(modifier = GlanceModifier.fillMaxSize().padding(margin.dp)) {
                // A Glance container holds at most ten children, so tiles are grouped.
                snapshot.design.visibleTiles(snapshot.canvas).chunked(10).forEach { group ->
                    Box(modifier = GlanceModifier.fillMaxSize()) {
                        group.forEach { tile ->
                            val frame = grid.frame(tile)
                            val presentation = TilePresenter.present(
                                tile = tile,
                                metric = snapshot.metrics.firstOrNull { it.id == tile.metricID },
                                context = tileContext,
                                width = frame.width,
                                height = frame.height,
                                isTrailing = tile.endColumn == snapshot.canvas.columns,
                            )
                            if (presentation !is TilePresentation.Hidden) {
                                Box(modifier = GlanceModifier.fillMaxSize().padding(start = frame.x.dp, top = frame.y.dp)) {
                                    Tile(tile, presentation, colors, sizer, frame.width, frame.height, context)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWidget(colors: WidgetColors, sizer: TextSizer, context: Context) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(WidgetCanvas.CONTENT_MARGIN.dp)
            .clickable(actionStartActivity(openAppIntent(context))),
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_link),
            contentDescription = null,
            modifier = GlanceModifier.size(22.dp),
            colorFilter = ColorFilter.tint(colors.ink),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(
            text = "Open the app to connect weather, health, or calendar.",
            style = TextStyle(color = colors.secondary, fontSize = sizer.sp(12f)),
            maxLines = 4,
        )
    }
}

@Composable
private fun Tile(
    tile: WidgetTile,
    presentation: TilePresentation,
    colors: WidgetColors,
    sizer: TextSizer,
    width: Float,
    height: Float,
    context: Context,
) {
    val action: Action = if (tile.isSync) {
        actionRunCallback<RefreshAction>()
    } else {
        actionStartActivity(tileIntent(context, tile.metricID))
    }
    val description = presentation.accessibilityText
    Box(
        modifier = GlanceModifier.size(width.dp, height.dp)
            .clickable(action)
            .semantics { contentDescription = description },
    ) {
        when (presentation) {
            is TilePresentation.Hidden -> Unit
            is TilePresentation.Compact -> CompactTile(presentation, colors, sizer)
            is TilePresentation.Stacked -> StackedTile(presentation, colors, sizer)
            is TilePresentation.Detailed -> DetailedTile(presentation, colors, sizer)
            is TilePresentation.Line -> LineTile(presentation, colors, sizer)
            is TilePresentation.NarrowEvent -> NarrowEventTile(presentation, colors, sizer)
            is TilePresentation.Agenda -> AgendaTile(presentation, colors, sizer)
            is TilePresentation.Sync -> SyncTile(presentation, colors, sizer)
        }
    }
}

@Composable
private fun Indicator(indicator: TileIndicator, colors: WidgetColors, sizer: TextSizer) {
    when (indicator) {
        is TileIndicator.Icon -> Image(
            provider = ImageProvider(SymbolIcons.resource(indicator.symbol)),
            contentDescription = null,
            modifier = GlanceModifier.size(indicator.size.dp),
            colorFilter = ColorFilter.tint(colors.tint(indicator.tint)),
        )
        is TileIndicator.Label -> Text(
            text = indicator.text,
            style = TextStyle(color = colors.tint(indicator.tint), fontSize = sizer.sp(indicator.size), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** A tile without data is drawn in the secondary color: Glance has no opacity. */
private fun valueColor(dimmed: Boolean, colors: WidgetColors): ColorProvider = if (dimmed) colors.secondary else colors.ink

@Composable
private fun CompactTile(tile: TilePresentation.Compact, colors: WidgetColors, sizer: TextSizer) {
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        tile.indicator?.let {
            Indicator(it, colors, sizer)
            Spacer(modifier = GlanceModifier.width(4.dp))
        }
        Text(
            text = tile.value,
            style = TextStyle(color = valueColor(tile.dimmed, colors), fontSize = sizer.sp(tile.valueSize), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        tile.detail?.let {
            Spacer(modifier = GlanceModifier.width(4.dp))
            Text(
                text = it,
                style = TextStyle(color = colors.secondary, fontSize = sizer.sp(tile.detailSize)),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

@Composable
private fun StackedTile(tile: TilePresentation.Stacked, colors: WidgetColors, sizer: TextSizer) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        tile.indicator?.let { Indicator(it, colors, sizer) }
        Text(
            text = tile.value,
            style = TextStyle(color = valueColor(tile.dimmed, colors), fontSize = sizer.sp(tile.valueSize), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        tile.detail?.let {
            Text(
                text = it,
                style = TextStyle(color = colors.secondary, fontSize = sizer.sp(tile.detailSize)),
                maxLines = tile.detailLines,
            )
        }
    }
}

@Composable
private fun DetailedTile(tile: TilePresentation.Detailed, colors: WidgetColors, sizer: TextSizer) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (tile.indicator != null || tile.title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                tile.indicator?.let {
                    Indicator(it, colors, sizer)
                    Spacer(modifier = GlanceModifier.width(3.dp))
                }
                tile.title?.let {
                    Text(
                        text = it,
                        style = TextStyle(color = colors.secondary, fontSize = sizer.sp(tile.titleSize), fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            text = tile.value,
            style = TextStyle(color = valueColor(tile.dimmed, colors), fontSize = sizer.sp(tile.valueSize), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        tile.detail?.let {
            Text(
                text = it,
                style = TextStyle(color = colors.secondary, fontSize = sizer.sp(tile.detailSize)),
                maxLines = tile.detailLines,
            )
        }
    }
}

@Composable
private fun RunText(run: TextRun, size: Float, colors: WidgetColors, sizer: TextSizer, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = run.text,
        style = TextStyle(
            color = if (run.tone == TextTone.SECONDARY) colors.secondary else colors.ink,
            fontSize = sizer.sp(size),
            fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
        ),
        maxLines = maxLines,
        modifier = modifier,
    )
}

@Composable
private fun LineTile(tile: TilePresentation.Line, colors: WidgetColors, sizer: TextSizer) {
    if (tile.maxLines <= 1 || tile.runs.size < 2) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = if (tile.topAligned) Alignment.Top else Alignment.CenterVertically,
        ) {
            tile.indicator?.let {
                Indicator(it, colors, sizer)
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            tile.runs.forEach { RunText(it, tile.fontSize, colors, sizer) }
        }
    } else {
        // Text cannot mix styles and wrap here: the time and countdown share
        // the first line, and the title wraps below.
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                tile.indicator?.let {
                    Indicator(it, colors, sizer)
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
                RunText(tile.runs.first(), tile.fontSize, colors, sizer)
                tile.runs.drop(2).forEach { RunText(it, tile.fontSize, colors, sizer) }
            }
            RunText(tile.runs[1].copy(text = tile.runs[1].text.trim()), tile.fontSize, colors, sizer, maxLines = tile.maxLines - 1)
        }
    }
}

@Composable
private fun NarrowEventTile(tile: TilePresentation.NarrowEvent, colors: WidgetColors, sizer: TextSizer) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = if (tile.title != null) Alignment.Top else Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tile.indicator?.let {
                Indicator(it, colors, sizer)
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
            Text(
                text = tile.whenText,
                style = TextStyle(color = colors.ink, fontSize = sizer.sp(tile.fontSize), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        tile.title?.let {
            Text(
                text = it,
                style = TextStyle(color = colors.ink, fontSize = sizer.sp(tile.titleSize)),
                maxLines = tile.titleLines,
            )
        }
    }
}

@Composable
private fun AgendaTile(tile: TilePresentation.Agenda, colors: WidgetColors, sizer: TextSizer) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        tile.rows.forEach { runs ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(tile.rowHeight.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                runs.forEach { RunText(it, tile.fontSize, colors, sizer) }
            }
        }
    }
}

@Composable
private fun SyncTile(tile: TilePresentation.Sync, colors: WidgetColors, sizer: TextSizer) {
    val color = if (tile.warning) colors.warning else colors.secondary
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (tile.trailing) Alignment.End else Alignment.Start,
    ) {
        Image(
            provider = ImageProvider(SymbolIcons.resource(tile.symbol)),
            contentDescription = null,
            modifier = GlanceModifier.size(tile.fontSize.dp),
            colorFilter = ColorFilter.tint(color),
        )
        tile.label?.let {
            Spacer(modifier = GlanceModifier.width(3.dp))
            Text(
                text = it,
                style = TextStyle(color = color, fontSize = sizer.sp(tile.fontSize), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}
