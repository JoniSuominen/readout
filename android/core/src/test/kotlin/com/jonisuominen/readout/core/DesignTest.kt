package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {
    private fun tile(id: String, column: Int, row: Int, width: Int = 1, height: Int = 1) =
        WidgetTile(id, id, column, row, width, height)

    private fun design(vararg tiles: WidgetTile, canvas: WidgetCanvas = WidgetCanvas.MEDIUM) =
        WidgetDesign("test", "Test", canvas, tiles.toList())

    @Test
    fun starterLayoutsFitTheirGridWithoutOverlapsAndIncludeRefresh() {
        assertEquals(WidgetCanvas.entries.size, WidgetDesign.starters.size)
        for (starter in WidgetDesign.starters) {
            assertTrue(starter.tiles.any { it.isSync })
            for (tile in starter.tiles) {
                assertTrue("${starter.id} ${tile.metricID} fits", starter.fits(tile))
                assertTrue("${starter.id} ${tile.metricID} is free", starter.canPlace(tile))
                assertTrue(tile.isSync || MetricCatalog.descriptor(tile.metricID) != null)
            }
        }
        val large = WidgetDesign.starter(WidgetCanvas.LARGE)
        assertEquals("Every row is used", WidgetCanvas.LARGE.rows, large.tiles.maxOf { it.endRow })
        assertEquals(WidgetDesign.starter(WidgetCanvas.SMALL), WidgetDesign.starter(WidgetCanvas.SMALL))
    }

    @Test
    fun placementRejectsOverlapsAndTilesOutsideTheGrid() {
        val layout = design(tile("a", 0, 0, 2, 2), tile("b", 2, 0))
        assertFalse(layout.canPlace(tile("c", 1, 1)))
        assertTrue(layout.canPlace(tile("c", 2, 1)))
        assertFalse(layout.canPlace(tile("c", 3, 3, 2, 1)))
        assertFalse(layout.canPlace(tile("c", -1, 0)))
        assertFalse(layout.canPlace(tile("c", 0, 2, 1, 0)))
        assertNull("A move onto a taken cell is refused", layout.updating(tile("b", 1, 1)))
        assertEquals(tile("b", 3, 3), layout.updating(tile("b", 3, 3))?.tiles?.last())
        assertNull(layout.updating(tile("missing", 3, 3)))
    }

    @Test
    fun placementsListOnlyFreeSpotsAndIgnoreTheTilesOwnCells() {
        val layout = design(tile("a", 0, 0, 2, 1), tile("b", 2, 0, 2, 4), canvas = WidgetCanvas.MEDIUM)
        val spots = layout.placements(layout.tiles[0])
        assertTrue(spots.any { it.column == 0 && it.row == 0 })
        assertEquals(4, spots.size)
        assertTrue(spots.all { it.column == 0 })
    }

    @Test
    fun nudgeJumpsOverTilesAndStopsAtTheEdge() {
        val layout = design(tile("a", 0, 0), tile("b", 1, 0), tile("c", 2, 0))
        assertEquals(3, layout.placement(layout.tiles[0], 1, 0)?.column)
        assertNull(layout.placement(layout.tiles[0], -1, 0))
        assertNull(layout.placement(layout.tiles[0], 0, 0))
        assertEquals(1, layout.placement(layout.tiles[0], 0, 1)?.row)
    }

    @Test
    fun nearestPlacementSnapsPastTakenCellsAndGivesUpWhenFarAway() {
        val layout = design(tile("a", 0, 0), tile("b", 1, 0))
        val snapped = layout.nearestPlacement(layout.tiles[0], 1.1f, 0f, 80f, 30f, 72f)
        assertNotNull(snapped)
        assertEquals("The taken cell hands the tile to the row below", 1, snapped!!.column)
        assertEquals(1, snapped.row)
        val full = design(tile("a", 0, 0, 2, 4), tile("b", 2, 0, 2, 3), tile("c", 2, 3))
        assertNull(full.nearestPlacement(full.tiles[2], 0f, 0f, 80f, 30f, 72f))
    }

    @Test
    fun resizeGrowsAsFarAsFreeSpaceAllowsAndNeverBelowOneCell() {
        val layout = design(tile("a", 0, 0), tile("b", 2, 0))
        val (grown, applied) = layout.resizingTile("a", 4, 2)!!
        assertEquals(2, applied.width)
        assertEquals(2, applied.height)
        assertEquals(applied, grown.tiles[0])
        val (_, shrunk) = layout.resizingTile("a", 0, -3)!!
        assertEquals(1, shrunk.width)
        assertEquals(1, shrunk.height)
        assertNull(layout.resizingTile("missing", 2, 2))
    }

    @Test
    fun addingFailsWhenTheGridIsFullAndEventsPreferWideTiles() {
        val empty = design()
        val (withEvent, event) = empty.addingTile("nextEvent")!!
        assertEquals(3, event.width)
        val (_, agenda) = withEvent.addingTile("agenda")!!
        assertEquals(3, agenda.width)
        assertEquals(3, agenda.height)
        assertEquals(2, empty.copy(canvas = WidgetCanvas.SMALL).addingTile("nextEvent")!!.second.width)
        val full = design(tile("a", 0, 0, 4, 4))
        assertNull(full.addingTile("steps"))
        assertTrue(full.removingTile("a").tiles.isEmpty())
    }

    @Test
    fun layoutChosenForAnotherSizeShowsOnlyTilesThatFit() {
        val medium = WidgetDesign.starter(WidgetCanvas.MEDIUM)
        val visible = medium.visibleTiles(WidgetCanvas.SMALL)
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.endColumn <= WidgetCanvas.SMALL.columns })
        assertTrue(visible.size < medium.tiles.size)
    }

    @Test
    fun storeKeepsStartersOnFirstSaveAndFallsBackAfterDeletion() {
        val store = WidgetDesignStore(InMemoryKeyValueStore())
        assertEquals(WidgetDesign.starters, store.designs)
        val created = store.newDesign(WidgetCanvas.SMALL)
        assertEquals("Small 2", created.name)
        store.save(created)
        assertEquals(WidgetDesign.starters.size + 1, store.designs.size)
        assertEquals(created, store.resolvedDesign(created.id, WidgetCanvas.SMALL))
        store.save(created.copy(name = "Renamed", appearance = WidgetAppearance.SKY))
        assertEquals("Renamed", store.design(created.id)?.name)
        assertEquals(WidgetAppearance.SKY, store.design(created.id)?.appearance)
        store.delete(created.id)
        assertEquals("starter.small", store.resolvedDesign(created.id, WidgetCanvas.SMALL).id)
        assertEquals(
            "A layout of another size is never used",
            "starter.medium", store.resolvedDesign("starter.small", WidgetCanvas.MEDIUM).id,
        )
        store.designs.forEach { store.delete(it.id) }
        assertEquals("starter.large", store.resolvedDesign(null, WidgetCanvas.LARGE).id)
        store.reset()
        assertEquals(WidgetDesign.starters, store.designs)
    }

    @Test
    fun newLayoutsGetTheirOwnIdentities() {
        val store = WidgetDesignStore(InMemoryKeyValueStore())
        val first = store.newDesign(WidgetCanvas.MEDIUM)
        val second = store.newDesign(WidgetCanvas.MEDIUM)
        assertNotEquals(first.id, second.id)
        assertTrue(first.tiles.map { it.id }.intersect(second.tiles.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun layoutsSavedWithoutAppearanceOrStyleLoadWithDefaults() {
        val kv = InMemoryKeyValueStore()
        kv.putString(
            "widget.designs",
            """[{"id":"x","name":"Old","canvas":"small","tiles":[{"id":"t","metricID":"uv","column":0,"row":0}],"future":1}]""",
        )
        val design = WidgetDesignStore(kv).designs.single()
        assertEquals(WidgetAppearance.SYSTEM, design.appearance)
        assertEquals(TileStyle.STANDARD, design.tiles.single().style)
        assertEquals(1, design.tiles.single().width)
    }

    @Test
    fun appearancesKeepTextAndTintsReadable() {
        for (appearance in WidgetAppearance.entries) {
            assertTrue(appearance.displayName.isNotEmpty())
            val backgrounds: List<Pair<WidgetRGB, Boolean>> = when {
                appearance.isGradient -> appearance.gradientStops.map { it to true }
                appearance.solidBackground != null -> listOf(
                    appearance.solidBackground!!.day to false,
                    appearance.solidBackground!!.night to true,
                )
                else -> emptyList()
            }
            for ((background, isDark) in backgrounds) {
                val ink = appearance.ink.resolve(isDark)
                assertTrue("$appearance ink", ink.contrast(background) >= 7.0)
                val secondary = appearance.secondaryInk.resolve(isDark)
                val flattened = secondary.blended(background, secondary.alpha)
                assertTrue("$appearance secondary ${flattened.contrast(background)}", flattened.contrast(background) >= 4.5)
                for (name in TileTint.names) {
                    val tint = appearance.tint(name).resolve(isDark)
                    assertTrue("$appearance $name ${tint.contrast(background)}", tint.contrast(background) >= 3.0)
                }
            }
        }
        for (name in TileTint.names) {
            for (stop in WidgetAppearance.SKY.gradientStops + WidgetAppearance.EMBER.gradientStops) {
                assertTrue("$name on gradient", TileTint.bright(name).contrast(stop) >= 4.5)
            }
        }
    }

    @Test
    fun argbPacksChannels() {
        assertEquals(0xFFFFFFFF.toInt(), WidgetRGB.WHITE.argb)
        assertEquals(0xFF000000.toInt(), WidgetRGB.BLACK.argb)
        assertEquals(0xFF1C1B1F.toInt(), WidgetRGB.hex(0x1C1B1F).argb)
    }
}
