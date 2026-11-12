package com.jonisuominen.readout.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.pow

/**
 * A home screen widget size, divided into a grid of tile cells. Rows are
 * about 27 dp tall in every size, enough for one line of icon and value.
 */
@Serializable
enum class WidgetCanvas(
    val key: String,
    val displayName: String,
    val columns: Int,
    val rows: Int,
    /** A typical widget size in dp, used for in-app previews. */
    val previewWidth: Float,
    val previewHeight: Float,
) {
    @SerialName("small") SMALL("small", "Small", 2, 4, 164f, 164f),
    @SerialName("medium") MEDIUM("medium", "Medium", 4, 4, 344f, 164f),
    @SerialName("large") LARGE("large", "Large", 4, 10, 344f, 372f);

    companion object {
        const val CONTENT_MARGIN = 14f
        const val TILE_SPACING = 6f

        fun fromKey(key: String?): WidgetCanvas? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
enum class TileStyle {
    /** An icon or short label beside the value. */
    @SerialName("standard") STANDARD,

    /** Only the value, for people who know where each tile sits. */
    @SerialName("valueOnly") VALUE_ONLY,
}

/**
 * One item placed on the widget grid. Column and row are zero-based; width
 * and height count cells. The sync tile refreshes data and shows its age.
 */
@Serializable
data class WidgetTile(
    val id: String = UUID.randomUUID().toString(),
    val metricID: String,
    val column: Int,
    val row: Int,
    val width: Int = 1,
    val height: Int = 1,
    val style: TileStyle = TileStyle.STANDARD,
) {
    val isSync: Boolean get() = metricID == SYNC_ID
    val endColumn: Int get() = column + width
    val endRow: Int get() = row + height

    fun overlaps(other: WidgetTile): Boolean =
        column < other.endColumn && other.column < endColumn &&
            row < other.endRow && other.row < endRow

    companion object {
        const val SYNC_ID = "sync"
    }
}

@Serializable
data class WidgetDesign(
    val id: String,
    val name: String,
    val canvas: WidgetCanvas,
    val tiles: List<WidgetTile>,
    val appearance: WidgetAppearance = WidgetAppearance.SYSTEM,
) {
    fun fits(tile: WidgetTile, canvas: WidgetCanvas = this.canvas): Boolean =
        tile.column >= 0 && tile.row >= 0 && tile.width >= 1 && tile.height >= 1 &&
            tile.endColumn <= canvas.columns && tile.endRow <= canvas.rows

    /** True when the tile stays inside this canvas without covering another tile. */
    fun canPlace(tile: WidgetTile): Boolean =
        fits(tile) && tiles.none { it.id != tile.id && it.overlaps(tile) }

    /**
     * Every free position for a tile of this size, ignoring the tile's own
     * current cells.
     */
    fun placements(tile: WidgetTile): List<WidgetTile> {
        val found = ArrayList<WidgetTile>()
        for (row in 0..(canvas.rows - tile.height)) {
            for (column in 0..(canvas.columns - tile.width)) {
                val candidate = tile.copy(column = column, row = row)
                if (canPlace(candidate)) found.add(candidate)
            }
        }
        return found
    }

    /**
     * The free position closest to a wanted top-left corner, given in
     * fractional cells. The pitch is one cell plus its gap, so a short row
     * weighs less than a wide column. Null when nothing free lies within
     * `maxDistance`.
     */
    fun nearestPlacement(
        tile: WidgetTile,
        column: Float,
        row: Float,
        pitchWidth: Float,
        pitchHeight: Float,
        maxDistance: Float,
    ): WidgetTile? {
        var best: WidgetTile? = null
        var bestDistance = Float.POSITIVE_INFINITY
        for (candidate in placements(tile)) {
            val distance = hypot((candidate.column - column) * pitchWidth, (candidate.row - row) * pitchHeight)
            if (distance <= maxDistance && distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * The first free spot in a straight line from the tile, jumping over any
     * tiles in the way. Null when the line reaches the edge without one.
     */
    fun placement(tile: WidgetTile, columns: Int, rows: Int): WidgetTile? {
        if (columns == 0 && rows == 0) return null
        var candidate = tile
        while (true) {
            candidate = candidate.copy(column = candidate.column + columns, row = candidate.row + rows)
            if (!fits(candidate)) return null
            if (canPlace(candidate)) return candidate
        }
    }

    fun openSlot(width: Int, height: Int): Pair<Int, Int>? {
        for (row in 0 until canvas.rows) {
            for (column in 0 until canvas.columns) {
                val candidate = WidgetTile(id = "", metricID = "", column = column, row = row, width = width, height = height)
                if (canPlace(candidate)) return column to row
            }
        }
        return null
    }

    /**
     * Adds a tile in the first open space. Events try a wider tile first so
     * the start time and title fit on one line; the agenda also tries to be
     * tall enough for three events. Null when the layout is full.
     */
    fun addingTile(metricID: String): Pair<WidgetDesign, WidgetTile>? {
        val wide = minOf(3, canvas.columns)
        val sizes: List<Pair<Int, Int>> = when (metricID) {
            "nextEvent" -> listOf(wide to 1, 2 to 1, 1 to 1)
            "agenda" -> listOf(wide to 3, wide to 2, 2 to 2, wide to 1, 2 to 1, 1 to 1)
            else -> listOf(1 to 1)
        }
        for ((width, height) in sizes) {
            val slot = openSlot(width, height) ?: continue
            val tile = WidgetTile(metricID = metricID, column = slot.first, row = slot.second, width = width, height = height)
            return copy(tiles = tiles + tile) to tile
        }
        return null
    }

    /** Applies a moved or resized tile, or null when it would overlap or leave the grid. */
    fun updating(tile: WidgetTile): WidgetDesign? {
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index < 0 || !canPlace(tile)) return null
        return copy(tiles = tiles.toMutableList().also { it[index] = tile })
    }

    /**
     * Resizes as close to the requested size as fits, never below one cell.
     * Returns the new layout and the size actually applied.
     */
    fun resizingTile(id: String, width: Int, height: Int): Pair<WidgetDesign, WidgetTile>? {
        val index = tiles.indexOfFirst { it.id == id }
        if (index < 0) return null
        val tile = tiles[index]
        val maxWidth = minOf(maxOf(1, width), canvas.columns - tile.column)
        val maxHeight = minOf(maxOf(1, height), canvas.rows - tile.row)
        if (maxWidth < 1 || maxHeight < 1) return null
        for (candidateWidth in maxWidth downTo 1) {
            for (candidateHeight in maxHeight downTo 1) {
                val candidate = tile.copy(width = candidateWidth, height = candidateHeight)
                if (canPlace(candidate)) {
                    return copy(tiles = tiles.toMutableList().also { it[index] = candidate }) to candidate
                }
            }
        }
        return null
    }

    fun removingTile(id: String): WidgetDesign = copy(tiles = tiles.filter { it.id != id })

    /** A layout chosen for another widget size shows the tiles that fit. */
    fun visibleTiles(canvas: WidgetCanvas): List<WidgetTile> = tiles.filter { fits(it, canvas) }

    companion object {
        /**
         * Starter layouts shown until the first edit: a few glanceable values
         * in small, more detail in large.
         */
        val starters: List<WidgetDesign> by lazy { WidgetCanvas.entries.map(::makeStarter) }

        fun starter(canvas: WidgetCanvas): WidgetDesign = starters.first { it.canvas == canvas }

        private fun makeStarter(canvas: WidgetCanvas): WidgetDesign {
            fun tile(metricID: String, column: Int, row: Int, width: Int = 1, height: Int = 1) =
                // Fixed ids keep a starter layout equal to itself between reads.
                WidgetTile("starter.${canvas.key}.$metricID", metricID, column, row, width, height)
            val tiles = when (canvas) {
                WidgetCanvas.SMALL -> listOf(
                    tile("temperature", 0, 0, 1, 2), tile("uv", 1, 0, 1, 2),
                    tile("nextEvent", 0, 2, 2, 1),
                    tile("steps", 0, 3), tile(WidgetTile.SYNC_ID, 1, 3),
                )
                WidgetCanvas.MEDIUM -> listOf(
                    tile("temperature", 0, 0, 2, 2), tile("uv", 2, 0, 2, 2),
                    tile("rain", 0, 2), tile("tomorrow", 1, 2), tile("steps", 2, 2), tile("sleep", 3, 2),
                    tile("nextEvent", 0, 3, 3, 1), tile(WidgetTile.SYNC_ID, 3, 3),
                )
                // Fills all ten rows so no band of empty space is left over.
                WidgetCanvas.LARGE -> listOf(
                    tile("temperature", 0, 0, 2, 3), tile("uv", 2, 0, 2, 3),
                    tile("rain", 0, 3, 2, 2), tile("tomorrow", 2, 3, 2, 2),
                    tile("nextEvent", 0, 5, 3, 1), tile(WidgetTile.SYNC_ID, 3, 5),
                    tile("steps", 0, 6, 2, 2), tile("sleep", 2, 6, 2, 2),
                    tile("heart", 0, 8, 2, 2), tile("readiness", 2, 8, 2, 2),
                )
            }
            return WidgetDesign("starter.${canvas.key}", canvas.displayName, canvas, tiles)
        }
    }
}

/** Layouts are stored beside the snapshot so widgets can draw and list them. */
class WidgetDesignStore(private val kv: KeyValueStore) {
    /** Saved layouts, or one starter layout per size before the first edit. */
    val designs: List<WidgetDesign>
        get() {
            val raw = kv.getString(DESIGNS_KEY) ?: return WidgetDesign.starters
            return try {
                ReadoutJson.decodeFromString(ListSerializer(WidgetDesign.serializer()), raw)
            } catch (_: Exception) {
                WidgetDesign.starters
            }
        }

    fun designs(canvas: WidgetCanvas): List<WidgetDesign> = designs.filter { it.canvas == canvas }

    fun design(id: String?): WidgetDesign? = id?.let { wanted -> designs.firstOrNull { it.id == wanted } }

    /**
     * A widget whose layout was deleted, or that never chose one, uses the
     * first layout of its size.
     */
    fun resolvedDesign(id: String?, canvas: WidgetCanvas): WidgetDesign =
        design(id)?.takeIf { it.canvas == canvas } ?: designs(canvas).firstOrNull() ?: WidgetDesign.starter(canvas)

    fun save(design: WidgetDesign) {
        val all = designs.toMutableList()
        val index = all.indexOfFirst { it.id == design.id }
        if (index >= 0) all[index] = design else all.add(design)
        write(all)
    }

    fun delete(id: String) = write(designs.filter { it.id != id })

    fun newDesign(canvas: WidgetCanvas): WidgetDesign {
        val count = designs(canvas).size
        val starter = WidgetDesign.starter(canvas)
        return starter.copy(
            id = UUID.randomUUID().toString(),
            name = if (count == 0) canvas.displayName else "${canvas.displayName} ${count + 1}",
            tiles = starter.tiles.map { it.copy(id = UUID.randomUUID().toString()) },
        )
    }

    fun reset() = kv.remove(DESIGNS_KEY)

    private fun write(designs: List<WidgetDesign>) {
        kv.putString(DESIGNS_KEY, ReadoutJson.encodeToString(ListSerializer(WidgetDesign.serializer()), designs))
    }

    private companion object {
        const val DESIGNS_KEY = "widget.designs"
    }
}

/** A plain sRGB color whose contrast against a background can be measured. */
data class WidgetRGB(val red: Double, val green: Double, val blue: Double, val alpha: Double = 1.0) {
    /** 0xAARRGGBB. */
    val argb: Int
        get() {
            fun channel(value: Double) = (value.coerceIn(0.0, 1.0) * 255).toInt()
            return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
        }

    /** Relative luminance as WCAG defines it. */
    val luminance: Double
        get() {
            fun linear(channel: Double) =
                if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
            return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        }

    /** WCAG contrast ratio, 1 to 21. Body text needs at least 4.5. */
    fun contrast(other: WidgetRGB): Double {
        val lighter = maxOf(luminance, other.luminance)
        val darker = minOf(luminance, other.luminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** This color drawn at `opacity` over a background. */
    fun blended(background: WidgetRGB, opacity: Double): WidgetRGB = WidgetRGB(
        red * opacity + background.red * (1 - opacity),
        green * opacity + background.green * (1 - opacity),
        blue * opacity + background.blue * (1 - opacity),
    )

    companion object {
        val WHITE = WidgetRGB(1.0, 1.0, 1.0)
        val BLACK = WidgetRGB(0.0, 0.0, 0.0)

        fun hex(rgb: Int): WidgetRGB = WidgetRGB(
            ((rgb shr 16) and 0xFF) / 255.0,
            ((rgb shr 8) and 0xFF) / 255.0,
            (rgb and 0xFF) / 255.0,
        )
    }
}

/** A color for the phone's light theme and one for its dark theme. */
data class ThemedColor(val day: WidgetRGB, val night: WidgetRGB) {
    constructor(both: WidgetRGB) : this(both, both)

    fun resolve(isDark: Boolean): WidgetRGB = if (isDark) night else day
}

/**
 * How a layout's widget looks: its background, and whether tiles draw in
 * light or dark colors whatever the phone is set to.
 */
@Serializable
enum class WidgetAppearance(val key: String, val displayName: String) {
    /** Follows the phone's light and dark setting. */
    @SerialName("system") SYSTEM("system", "System"),
    @SerialName("light") LIGHT("light", "Light"),
    @SerialName("dark") DARK("dark", "Dark"),
    @SerialName("black") BLACK("black", "Black"),

    /** No background, so the home screen wallpaper shows through. */
    @SerialName("transparent") TRANSPARENT("transparent", "Transparent"),
    @SerialName("sky") SKY("sky", "Sky"),
    @SerialName("ember") EMBER("ember", "Ember");

    /** True for light tiles, false for dark ones, null to follow the phone. */
    val forcesDark: Boolean?
        get() = when (this) {
            SYSTEM, TRANSPARENT -> null
            LIGHT -> false
            DARK, BLACK, SKY, EMBER -> true
        }

    /**
     * Colors from one corner of the widget to the opposite one, for the
     * appearances drawn as a gradient. Empty for the others.
     */
    val gradientStops: List<WidgetRGB>
        get() = when (this) {
            SKY -> listOf(WidgetRGB(0.04, 0.13, 0.33), WidgetRGB(0.07, 0.30, 0.48))
            EMBER -> listOf(WidgetRGB(0.40, 0.08, 0.14), WidgetRGB(0.58, 0.20, 0.10))
            else -> emptyList()
        }

    val isGradient: Boolean get() = gradientStops.isNotEmpty()

    /** The solid background, or null for gradients and Transparent. */
    val solidBackground: ThemedColor?
        get() = when (this) {
            SYSTEM -> ThemedColor(LIGHT_SURFACE, DARK_SURFACE)
            LIGHT -> ThemedColor(LIGHT_SURFACE)
            DARK -> ThemedColor(DARK_SURFACE)
            BLACK -> ThemedColor(WidgetRGB.BLACK)
            TRANSPARENT, SKY, EMBER -> null
        }

    private fun themed(day: WidgetRGB, night: WidgetRGB): ThemedColor = when (forcesDark) {
        null -> ThemedColor(day, night)
        true -> ThemedColor(night)
        false -> ThemedColor(day)
    }

    /** Values and other primary text. */
    val ink: ThemedColor
        get() = if (isGradient) ThemedColor(WidgetRGB.WHITE) else themed(LIGHT_INK, WidgetRGB.WHITE)

    /** Captions and other secondary text. */
    val secondaryInk: ThemedColor
        get() = if (isGradient) {
            ThemedColor(WidgetRGB.WHITE.copy(alpha = GRADIENT_SECONDARY_OPACITY))
        } else {
            themed(LIGHT_SECONDARY, DARK_SECONDARY)
        }

    /**
     * An indicator color. On a gradient the standard colors lose contrast, so
     * lighter versions take their place.
     */
    fun tint(name: String): ThemedColor =
        if (isGradient) ThemedColor(TileTint.bright(name)) else TileTint.color(name).let { themed(it.day, it.night) }

    /** Stale or failed refresh state. */
    val warning: ThemedColor get() = tint("orange")

    companion object {
        /** Opacity of white secondary text on a gradient. */
        const val GRADIENT_SECONDARY_OPACITY = 0.78

        val LIGHT_SURFACE = WidgetRGB.WHITE
        val DARK_SURFACE = WidgetRGB(0.11, 0.11, 0.12)
        val LIGHT_INK = WidgetRGB.hex(0x1C1B1F)
        val LIGHT_SECONDARY = WidgetRGB.hex(0x5F5F66)
        val DARK_SECONDARY = WidgetRGB.hex(0xB4B4BB)
    }
}

object TileTint {
    val names = listOf("pink", "mint", "blue", "violet", "indigo", "orange", "yellow", "green", "red", "teal", "gray")

    /**
     * Light versions of every tint, for dark gradient backgrounds. Each is at
     * least 4.5:1 against the gradients in `WidgetAppearance`.
     */
    fun bright(name: String): WidgetRGB = when (name) {
        "pink" -> WidgetRGB(1.00, 0.74, 0.85)
        "mint" -> WidgetRGB(0.55, 0.95, 0.80)
        "blue" -> WidgetRGB(0.62, 0.84, 1.00)
        "violet" -> WidgetRGB(0.86, 0.77, 1.00)
        "indigo" -> WidgetRGB(0.76, 0.78, 1.00)
        "orange" -> WidgetRGB(1.00, 0.75, 0.45)
        "yellow" -> WidgetRGB(1.00, 0.88, 0.40)
        "green" -> WidgetRGB(0.55, 0.95, 0.60)
        "red" -> WidgetRGB(1.00, 0.72, 0.70)
        "teal" -> WidgetRGB(0.45, 0.92, 0.92)
        else -> WidgetRGB(0.82, 0.84, 0.88)
    }

    /** Indicator colors for plain light and dark backgrounds. */
    fun color(name: String): ThemedColor = when (name) {
        "pink" -> ThemedColor(WidgetRGB.hex(0xE0245E), WidgetRGB.hex(0xFF6B8E))
        "mint" -> ThemedColor(WidgetRGB.hex(0x00857C), WidgetRGB.hex(0x63E6E2))
        "blue" -> ThemedColor(WidgetRGB.hex(0x0A6CDF), WidgetRGB.hex(0x5AA9FF))
        "violet" -> ThemedColor(WidgetRGB.hex(0x9B40CC), WidgetRGB.hex(0xCF8BF7))
        "indigo" -> ThemedColor(WidgetRGB.hex(0x4F4DCB), WidgetRGB.hex(0x9C9AFF))
        "orange" -> ThemedColor(WidgetRGB.hex(0xC76A00), WidgetRGB.hex(0xFFAA33))
        "yellow" -> ThemedColor(WidgetRGB.hex(0x9A7500), WidgetRGB.hex(0xFFD60A))
        "green" -> ThemedColor(WidgetRGB.hex(0x1E8E3E), WidgetRGB.hex(0x4CD964))
        "red" -> ThemedColor(WidgetRGB.hex(0xD93025), WidgetRGB.hex(0xFF6961))
        "teal" -> ThemedColor(WidgetRGB.hex(0x117E94), WidgetRGB.hex(0x40C8E0))
        else -> ThemedColor(WidgetRGB.hex(0x6E6E73), WidgetRGB.hex(0xAEAEB2))
    }
}
