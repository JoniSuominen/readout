package com.jonisuominen.readout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.MetricCatalog
import com.jonisuominen.readout.core.TileGridGeometry
import com.jonisuominen.readout.core.TileStyle
import com.jonisuominen.readout.core.WidgetAppearance
import com.jonisuominen.readout.core.WidgetCanvas
import com.jonisuominen.readout.core.WidgetDesign
import com.jonisuominen.readout.core.WidgetTile
import com.jonisuominen.readout.widget.WidgetUpdater
import java.time.Instant

private enum class DragKind { MOVE, RESIZE }

private data class TileDrag(val kind: DragKind, val tileID: String, val dx: Float, val dy: Float)

private val Free = Color(0xFF1E8E3E)
private val Blocked = Color(0xFFD93025)

private fun tileName(tile: WidgetTile): String =
    if (tile.isSync) MetricCatalog.sync.name else MetricCatalog.descriptor(tile.metricID)?.name ?: tile.metricID

private fun position(tile: WidgetTile): String = "column ${tile.column + 1}, row ${tile.row + 1}"

/** Cells the tile could be placed on, not counting its own. */
private fun availableCells(design: WidgetDesign, tile: WidgetTile?): Set<Pair<Int, Int>> {
    tile ?: return emptySet()
    val cells = HashSet<Pair<Int, Int>>()
    for (spot in design.placements(tile)) {
        for (column in spot.column until spot.endColumn) {
            for (row in spot.row until spot.endRow) cells += column to row
        }
    }
    for (column in tile.column until tile.endColumn) {
        for (row in tile.row until tile.endRow) cells -= column to row
    }
    return cells
}

/** The free spot nearest to where a drag has carried the tile. */
private fun snapped(design: WidgetDesign, tile: WidgetTile, dx: Float, dy: Float, grid: TileGridGeometry): WidgetTile? =
    design.nearestPlacement(
        tile,
        column = tile.column + dx / grid.pitchWidth,
        row = tile.row + dy / grid.pitchHeight,
        pitchWidth = grid.pitchWidth,
        pitchHeight = grid.pitchHeight,
        // A bit under a column, so a blocked cell hands the tile to the next free one.
        maxDistance = 0.9f * maxOf(grid.pitchWidth, grid.pitchHeight),
    )

private fun moved(design: WidgetDesign, tile: WidgetTile, dx: Float, dy: Float, grid: TileGridGeometry): WidgetTile {
    val (columns, rows) = grid.cells(dx, dy)
    return tile.copy(
        column = (tile.column + columns).coerceIn(0, design.canvas.columns - tile.width),
        row = (tile.row + rows).coerceIn(0, design.canvas.rows - tile.height),
    )
}

private fun resized(design: WidgetDesign, tile: WidgetTile, dx: Float, dy: Float, grid: TileGridGeometry): WidgetTile {
    val (columns, rows) = grid.cells(dx, dy)
    return tile.copy(
        width = (tile.width + columns).coerceIn(1, design.canvas.columns - tile.column),
        height = (tile.height + rows).coerceIn(1, design.canvas.rows - tile.row),
    )
}

/**
 * Edits one layout on its widget grid: tap a tile to select it and see where
 * it can go, then tap a free spot or drag the tile there. Drag its corner to
 * resize. Smaller tiles switch to a shorter format.
 */
@Composable
fun LayoutEditorScreen(container: AppContainer, state: ConnectorManager.State, designID: String, onClose: () -> Unit) {
    val store = container.store
    val initial = remember(designID) { store.designs.design(designID) }
    if (initial == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    var design by remember { mutableStateOf(initial) }
    var selectedID by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf<TileDrag?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val currentDesign by rememberUpdatedState(design)

    fun applyDesign(updated: WidgetDesign) {
        design = updated
        store.designs.save(updated)
    }

    /** Read aloud by TalkBack, which cannot see the move happen. */
    fun announce(text: String) {
        @Suppress("DEPRECATION")
        view.announceForAccessibility(text)
    }

    fun showMessage(text: String?) {
        message = text
    }

    /** Moves a tile one step, jumping over tiles in the way: how TalkBack users reposition tiles. */
    fun nudge(tileID: String, columns: Int, rows: Int, direction: String) {
        val tile = currentDesign.tiles.firstOrNull { it.id == tileID } ?: return
        val target = currentDesign.placement(tile, columns, rows)
        if (target == null) {
            showMessage("Cannot move $direction. No free space that way.")
            return
        }
        currentDesign.updating(target)?.let(::applyDesign)
        showMessage(null)
        announce("Moved $direction to ${position(target)}")
    }

    // Placed widgets redraw with the edited layout once the editor closes.
    DisposableEffect(Unit) { onDispose { WidgetUpdater.requestUpdate(context) } }

    val env = DisplayEnvironment.current
    val now = remember(state.metrics) { Instant.now() }
    val shownMetrics = remember(state.metrics, state.connectedSources, now) {
        previewMetrics(state.metrics, state.connectedSources, now, env)
    }
    val selectedTile = design.tiles.firstOrNull { it.id == selectedID }
    val available = remember(design, selectedID) { availableCells(design, selectedTile) }

    Scaffold(topBar = { BackTopBar(design.name.ifEmpty { "Layout" }, onClose) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                val canvas = design.canvas
                val maxScale = when (canvas) {
                    WidgetCanvas.SMALL -> 1.6f
                    WidgetCanvas.MEDIUM -> 1.1f
                    WidgetCanvas.LARGE -> 1.0f
                }
                val scale = maxOf(0.5f, minOf((maxWidth.value - 32f) / canvas.previewWidth, maxScale))
                val width = canvas.previewWidth * scale
                val height = canvas.previewHeight * scale
                val margin = WidgetCanvas.CONTENT_MARGIN * scale
                val innerWidth = width - 2 * margin
                val innerHeight = height - 2 * margin
                val grid = remember(scale, canvas) { TileGridGeometry(innerWidth, innerHeight, canvas, WidgetCanvas.TILE_SPACING * scale) }
                val density = LocalDensity.current.density
                val colors = rememberTileColors(design.appearance)
                val tileContext = remember(design, shownMetrics, state.connectedSources, scale) {
                    previewContext(store, design, shownMetrics, state.connectedSources, now, env, scale)
                }

                // Where a dragged tile would land. It snaps to the nearest free spot; with
                // none in reach it shows the blocked cell it hovers over.
                val moving = drag?.takeIf { it.kind == DragKind.MOVE }
                val movingTile = moving?.let { current -> design.tiles.firstOrNull { it.id == current.tileID } }
                val target: Pair<WidgetTile, Boolean>? = if (moving != null && movingTile != null) {
                    snapped(design, movingTile, moving.dx, moving.dy, grid)?.let { it to true }
                        ?: (moved(design, movingTile, moving.dx, moving.dy, grid) to false)
                } else {
                    null
                }

                Box(
                    modifier = Modifier
                        .size(width.dp, height.dp)
                        .widgetPreviewBackground(design.appearance, 22 * scale)
                        .then(
                            if (target != null) {
                                Modifier.border(2.dp, if (target.second) Free else Blocked, RoundedCornerShape((22 * scale).dp))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(margin.dp)
                            .size(innerWidth.dp, innerHeight.dp)
                            // Empty space: tap a free spot to place the selected tile there, anywhere else to deselect.
                            .pointerInput(scale) {
                                detectTapGestures { offset ->
                                    val x = offset.x / density
                                    val y = offset.y / density
                                    val layout = currentDesign
                                    val tile = layout.tiles.firstOrNull { it.id == selectedID }
                                    val cell = (x / grid.pitchWidth).toInt().coerceIn(0, layout.canvas.columns - 1) to
                                        (y / grid.pitchHeight).toInt().coerceIn(0, layout.canvas.rows - 1)
                                    if (tile == null || cell !in availableCells(layout, tile)) {
                                        selectedID = null
                                    } else {
                                        val frame = grid.frame(tile)
                                        val spot = layout.nearestPlacement(
                                            tile,
                                            column = (x - frame.width / 2) / grid.pitchWidth,
                                            row = (y - frame.height / 2) / grid.pitchHeight,
                                            pitchWidth = grid.pitchWidth,
                                            pitchHeight = grid.pitchHeight,
                                            maxDistance = Float.POSITIVE_INFINITY,
                                        )
                                        if (spot != null) {
                                            layout.updating(spot)?.let(::applyDesign)
                                            showMessage(null)
                                            if (spot != tile) announce("Moved to ${position(spot)}")
                                        }
                                    }
                                }
                            },
                    ) {
                        // Free spots get a fill and a solid outline; the rest stay faint, so color is not the only cue.
                        for (row in 0 until canvas.rows) {
                            for (column in 0 until canvas.columns) {
                                val frame = grid.frame(WidgetTile("", "", column, row))
                                val isFree = (column to row) in available
                                val shape = RoundedCornerShape((5 * scale).dp)
                                Box(
                                    modifier = Modifier
                                        .offset(frame.x.dp, frame.y.dp)
                                        .size(frame.width.dp, frame.height.dp)
                                        .then(
                                            if (isFree) {
                                                Modifier.background(Free.copy(alpha = 0.16f), shape).border(1.5.dp, Free.copy(alpha = 0.8f), shape)
                                            } else {
                                                Modifier.border(1.dp, colors.secondary.copy(alpha = 0.22f), shape)
                                            },
                                        ),
                                )
                            }
                        }

                        if (target != null) {
                            // A check marks a spot that works; a cross marks a blocked one.
                            val frame = grid.frame(target.first)
                            val tint = if (target.second) Free else Blocked
                            val shape = RoundedCornerShape((6 * scale).dp)
                            Box(
                                modifier = Modifier
                                    .offset(frame.x.dp, frame.y.dp)
                                    .size(frame.width.dp, frame.height.dp)
                                    .zIndex(0.5f)
                                    .background(tint.copy(alpha = if (target.second) 0.3f else 0.2f), shape)
                                    .border(2.dp, tint, shape)
                                    .clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(if (target.second) R.drawable.ic_check else R.drawable.ic_close),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size((14 * scale).dp),
                                )
                            }
                        }

                        for (tile in design.tiles) {
                            key(tile.id) {
                                val isSelected = tile.id == selectedID
                                val tileDrag = drag?.takeIf { it.tileID == tile.id }
                                val isMoving = tileDrag?.kind == DragKind.MOVE
                                val isResizing = tileDrag?.kind == DragKind.RESIZE
                                val shown = if (isResizing && tileDrag != null) resized(design, tile, tileDrag.dx, tileDrag.dy, grid) else tile
                                val frame = grid.frame(shown)
                                val blocked = isResizing && !design.canPlace(shown)
                                val shape = RoundedCornerShape((6 * scale).dp)
                                val outline = when {
                                    blocked -> Blocked
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> Color.Transparent
                                }
                                val fill = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else colors.secondary.copy(alpha = 0.1f)
                                val name = tileName(tile)
                                Box(
                                    modifier = Modifier
                                        .offset(frame.x.dp, frame.y.dp)
                                        .size(frame.width.dp, frame.height.dp)
                                        .zIndex(if (isMoving) 2f else if (isSelected) 1f else 0f)
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = name
                                            stateDescription = "${position(tile)}, ${tile.width} by ${tile.height}"
                                            selected = isSelected
                                            customActions = listOf(
                                                CustomAccessibilityAction("Move left") { nudge(tile.id, -1, 0, "left"); true },
                                                CustomAccessibilityAction("Move right") { nudge(tile.id, 1, 0, "right"); true },
                                                CustomAccessibilityAction("Move up") { nudge(tile.id, 0, -1, "up"); true },
                                                CustomAccessibilityAction("Move down") { nudge(tile.id, 0, 1, "down"); true },
                                            )
                                        }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClickLabel = "Select",
                                        ) { selectedID = tile.id }
                                        // Any tile can be picked up and dragged; it lands on the nearest free
                                        // spot and goes back where it was when none is in reach.
                                        .pointerInput(tile.id, scale) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    selectedID = tile.id
                                                    drag = TileDrag(DragKind.MOVE, tile.id, 0f, 0f)
                                                },
                                                onDragCancel = { drag = null },
                                                onDragEnd = {
                                                    val ended = drag
                                                    val layout = currentDesign
                                                    val current = layout.tiles.firstOrNull { it.id == tile.id }
                                                    if (ended != null && current != null) {
                                                        val spot = snapped(layout, current, ended.dx, ended.dy, grid)
                                                        if (spot != null) {
                                                            layout.updating(spot)?.let(::applyDesign)
                                                            showMessage(null)
                                                        } else {
                                                            showMessage("No free space there. Drop it on a green spot with a check, or shrink or remove a tile first.")
                                                        }
                                                    }
                                                    drag = null
                                                },
                                            ) { change, amount ->
                                                change.consume()
                                                drag = drag?.let { it.copy(dx = it.dx + amount.x / density, dy = it.dy + amount.y / density) }
                                            }
                                        }
                                        // Inside the gesture, so the picked-up tile follows the finger
                                        // without moving the area that tracks it.
                                        .graphicsLayer {
                                            if (isMoving && tileDrag != null) {
                                                translationX = tileDrag.dx * density
                                                translationY = tileDrag.dy * density
                                                scaleX = 1.04f
                                                scaleY = 1.04f
                                            }
                                        }
                                        .background(fill, shape)
                                        .border(2.dp, outline, shape)
                                        .padding(horizontal = (3 * scale).dp),
                                ) {
                                    TileView(
                                        tile = shown,
                                        metric = shownMetrics.firstOrNull { it.id == tile.metricID },
                                        context = tileContext,
                                        colors = colors,
                                        width = frame.width,
                                        height = frame.height,
                                        isTrailing = shown.endColumn == canvas.columns,
                                    )
                                }

                                if (isSelected) {
                                    // The handle stays on the tile's own corner while it is dragged, and is drawn under the finger.
                                    val corner = grid.frame(tile)
                                    Box(
                                        modifier = Modifier
                                            .offset((corner.x + corner.width - 26).dp, (corner.y + corner.height - 26).dp)
                                            .size(36.dp)
                                            .zIndex(3f)
                                            .clearAndSetSemantics {}
                                            .pointerInput(tile.id, scale) {
                                                detectDragGestures(
                                                    onDragStart = { drag = TileDrag(DragKind.RESIZE, tile.id, 0f, 0f) },
                                                    onDragCancel = { drag = null },
                                                    onDragEnd = {
                                                        val ended = drag
                                                        val layout = currentDesign
                                                        val current = layout.tiles.firstOrNull { it.id == tile.id }
                                                        if (ended != null && current != null) {
                                                            val wanted = resized(layout, current, ended.dx, ended.dy, grid)
                                                            if (wanted.width != current.width || wanted.height != current.height) {
                                                                val applied = layout.resizingTile(tile.id, wanted.width, wanted.height)
                                                                applied?.let { applyDesign(it.first) }
                                                                val exact = applied?.second?.let { it.width == wanted.width && it.height == wanted.height } ?: false
                                                                showMessage(if (exact) null else "Resized as far as the free space allows.")
                                                            }
                                                        }
                                                        drag = null
                                                    },
                                                ) { change, amount ->
                                                    change.consume()
                                                    drag = drag?.let { it.copy(dx = it.dx + amount.x / density, dy = it.dy + amount.y / density) }
                                                }
                                            }
                                            .graphicsLayer {
                                                if (isResizing && tileDrag != null) {
                                                    translationX = tileDrag.dx * density
                                                    translationY = tileDrag.dy * density
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_resize),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningColor,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                if (selectedTile != null) {
                    SectionTitle(tileName(selectedTile))
                    SectionCard {
                        Footnote(
                            if (available.isEmpty()) {
                                "No free spot for this tile. Shrink or remove another tile first."
                            } else {
                                "Tap a green spot or drag the tile onto one to move it."
                            },
                        )
                        val resize: (Int, Int) -> Unit = { width, height ->
                            if (width >= 1 && height >= 1) {
                                val updated = design.updating(selectedTile.copy(width = width, height = height))
                                if (updated != null) applyDesign(updated)
                                showMessage(if (updated != null) null else "No free space in that direction.")
                            }
                        }
                        Stepper("Width: ${selectedTile.width}", "width", { resize(selectedTile.width - 1, selectedTile.height) }) {
                            resize(selectedTile.width + 1, selectedTile.height)
                        }
                        Stepper("Height: ${selectedTile.height}", "height", { resize(selectedTile.width, selectedTile.height - 1) }) {
                            resize(selectedTile.width, selectedTile.height + 1)
                        }
                        ChoiceRow(
                            label = "Show",
                            options = TileStyle.entries,
                            selected = selectedTile.style,
                            name = { style ->
                                when (style) {
                                    TileStyle.STANDARD -> if (selectedTile.isSync) "Icon and minutes" else "Icon and value"
                                    TileStyle.VALUE_ONLY -> if (selectedTile.isSync) "Icon only" else "Value only"
                                }
                            },
                            onSelect = { style -> design.updating(selectedTile.copy(style = style))?.let(::applyDesign) },
                        )
                        TextButton(
                            onClick = {
                                applyDesign(design.removingTile(selectedTile.id))
                                selectedID = null
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Remove from layout") }
                    }
                } else {
                    Footnote("Tap a tile to select it. Green spots show where it can go: tap one, or drag the tile onto it. Drag the corner to resize. Smaller tiles show less: a full card, an icon and value, or just the value.")
                }

                SectionTitle("Add")
                SectionCard {
                    val used = design.tiles.map { it.metricID }.toSet()
                    val addable = (MetricCatalog.all + MetricCatalog.sync).filter { it.id !in used }
                    for (item in addable) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Add to layout") {
                                    val added = design.addingTile(item.id)
                                    if (added != null) {
                                        applyDesign(added.first)
                                        selectedID = added.second.id
                                        showMessage(null)
                                    } else {
                                        showMessage("No free space. Shrink or remove a tile first.")
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painterResource(SymbolIcons.resource(item.symbol)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(item.name)
                        }
                    }
                    if (addable.isEmpty()) Footnote("Every item is already on this layout.")
                }

                SectionTitle("Style")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (appearance in WidgetAppearance.entries) {
                        AppearanceSwatch(appearance, design.appearance == appearance) {
                            applyDesign(design.copy(appearance = appearance))
                        }
                    }
                }

                SectionTitle("Layout")
                SectionCard {
                    OutlinedTextField(
                        value = design.name,
                        onValueChange = { applyDesign(design.copy(name = it.take(40))) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    TextButton(
                        onClick = { confirmingDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete layout") }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this layout?") },
            text = { Text("Widgets using it switch to another ${design.canvas.displayName.lowercase()} layout.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        store.designs.delete(design.id)
                        onClose()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete layout") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stepper(label: String, what: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDecrement, modifier = Modifier.semantics { contentDescription = "Decrease $what" }) { Text("−") }
        OutlinedButton(onClick = onIncrement, modifier = Modifier.semantics { contentDescription = "Increase $what" }) { Text("+") }
    }
}

/** A small sample of a widget style for the style picker. */
@Composable
private fun AppearanceSwatch(appearance: WidgetAppearance, isSelected: Boolean, onSelect: () -> Unit) {
    val colors = rememberTileColors(appearance)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .widgetPreviewBackground(appearance, 12f)
                .then(if (isSelected) Modifier.border(3.dp, accent, RoundedCornerShape(12.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = colors.ink, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            appearance.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
