package com.jonisuominen.readout.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePresenterTest {
    private val uv = DashboardMetric("uv", DataSource.WEATHER, "UV index", "5", "Moderate · peak 6 at 13:00", Symbols.SUN, "yellow")
    private val event = DashboardMetric(
        "nextEvent", DataSource.CALENDAR, "Up next", "Design sync", "10:25", Symbols.CALENDAR, "blue",
        observedAt = baseNow.plusMinutes(25), endsAt = baseNow.plusMinutes(55),
    )

    private fun context(
        metrics: List<DashboardMetric> = listOf(uv),
        fetchedAt: java.time.Instant? = baseNow.plusMinutes(-12),
        error: String? = null,
        connected: Set<DataSource> = setOf(DataSource.WEATHER, DataSource.CALENDAR),
        showsUnavailable: Boolean = false,
    ) = TileContext(
        now = baseNow,
        freshness = DashboardFreshness(
            metrics,
            fetchedAt?.let { at -> metrics.associate { it.source to at } }.orEmpty(),
            error?.let { message -> metrics.associate { it.source to message } }.orEmpty(),
        ),
        privacyMode = WidgetPrivacyMode.SHOW_VALUES,
        connectedSources = connected,
        showsUnavailable = showsUnavailable,
        env = testEnv,
    )

    private fun tile(metricID: String, width: Int = 1, height: Int = 1, style: TileStyle = TileStyle.STANDARD) =
        WidgetTile("t", metricID, 0, 0, width, height, style)

    @Test
    fun tileFormatFollowsItsSize() {
        val compact = TilePresenter.present(tile("uv"), uv, context(), 70f, 27f) as TilePresentation.Compact
        assertEquals("5", compact.value)
        assertEquals(TileIndicator.Label("UV", compact.indicator!!.size, "yellow"), compact.indicator)
        assertNull("A one-cell tile has no caption", compact.detail)

        val wide = TilePresenter.present(tile("uv", width = 2), uv, context(), 150f, 27f) as TilePresentation.Compact
        assertEquals("Moderate", wide.detail)
        val wider = TilePresenter.present(tile("uv", width = 3), uv, context(), 230f, 27f) as TilePresentation.Compact
        assertEquals("Moderate · peak 6 at 13:00", wider.detail)

        val stacked = TilePresenter.present(tile("uv", height = 2), uv, context(), 70f, 60f) as TilePresentation.Stacked
        assertEquals("Moderate", stacked.detail)
        assertEquals(1, stacked.detailLines)

        val detailed = TilePresenter.present(tile("uv", 2, 2), uv, context(), 150f, 60f) as TilePresentation.Detailed
        assertEquals("UV index", detailed.title)
        assertTrue(detailed.indicator is TileIndicator.Icon)
        assertEquals("Moderate · peak 6 at 13:00", detailed.detail)
        assertEquals("UV index, 5, Moderate · peak 6 at 13:00", detailed.accessibilityText)

        val valueOnly = TilePresenter.present(tile("uv", 2, 2, TileStyle.VALUE_ONLY), uv, context(), 150f, 60f) as TilePresentation.Detailed
        assertNull(valueOnly.indicator)
        assertNull(valueOnly.title)
    }

    @Test
    fun longValuesShrinkButNeverBelowTheirMinimumScale() {
        val long = uv.copy(value = "Unhealthy for sensitive groups")
        val fitted = TilePresenter.present(tile("uv", height = 2), long, context(), 70f, 60f) as TilePresentation.Stacked
        val normal = TilePresenter.present(tile("uv", height = 2), uv, context(), 70f, 60f) as TilePresentation.Stacked
        assertTrue(fitted.valueSize < normal.valueSize)
        assertEquals(normal.valueSize * 0.4f, fitted.valueSize, 0.01f)
    }

    @Test
    fun valueLeavesRoomForItsCaptionLines() {
        fun lines(height: Float, value: Float, captions: Int) = value * 1.4f + 14.5f * captions
        val twoRows = TilePresenter.present(tile("uv", 2, 2), uv, context(), 150f, 60f) as TilePresentation.Detailed
        assertTrue(lines(60f, twoRows.valueSize, 2) <= 60f)
        val threeRows = TilePresenter.present(tile("uv", 2, 3), uv, context(), 150f, 93f) as TilePresentation.Detailed
        assertEquals(2, threeRows.detailLines)
        assertTrue(lines(93f, threeRows.valueSize, 3) <= 93f)
        assertTrue(threeRows.valueSize > twoRows.valueSize)
        val bare = TilePresenter.present(tile("uv", 2, 2, TileStyle.VALUE_ONLY), uv, context(), 150f, 60f) as TilePresentation.Detailed
        assertTrue("Without a title row the value grows", bare.valueSize > twoRows.valueSize)
        val stacked = TilePresenter.present(tile("uv", height = 2), uv, context(), 70f, 60f) as TilePresentation.Stacked
        assertTrue(14f + lines(60f, stacked.valueSize, 1) <= 60f)
    }

    @Test
    fun eventKeepsStartTimeAndTitleOnOneLine() {
        val context = context(listOf(event))
        val line = TilePresenter.present(tile("nextEvent", width = 3), event, context, 230f, 27f) as TilePresentation.Line
        assertEquals(listOf("10:25", " Design sync", " · in 25m"), line.runs.map { it.text })
        assertTrue(line.runs[0].bold)
        assertEquals(TextTone.SECONDARY, line.runs[2].tone)
        assertEquals("Next event, Design sync, 10:25, in 25m", line.accessibilityText)

        val narrow = TilePresenter.present(tile("nextEvent", width = 2), event, context, 150f, 27f) as TilePresentation.Line
        assertEquals(listOf("in 25m", " Design sync"), narrow.runs.map { it.text })

        val single = TilePresenter.present(tile("nextEvent"), event, context, 70f, 27f) as TilePresentation.NarrowEvent
        assertEquals("in 25m", single.whenText)
        assertNull(single.title)

        val running = context.copy(now = baseNow.plusMinutes(30))
        val ongoing = TilePresenter.present(tile("nextEvent", width = 3), event, running, 230f, 27f) as TilePresentation.Line
        assertEquals(listOf("Now", " Design sync", " · until 10:55"), ongoing.runs.map { it.text })

        val none = event.copy(value = "No upcoming events", observedAt = null, endsAt = null)
        val empty = TilePresenter.present(tile("nextEvent", width = 3), none, context, 230f, 27f) as TilePresentation.Line
        assertEquals("No upcoming events", empty.runs.single().text)
        assertEquals("None", (TilePresenter.present(tile("nextEvent"), none, context, 70f, 27f) as TilePresentation.Line).runs.single().text)
    }

    @Test
    fun agendaShowsOneEventPerRow() {
        val items = (1..5).map { AgendaItem(baseNow.plusMinutes(it * 90L), baseNow.plusMinutes(it * 90L + 30), false, if (it == 2) null else "Event $it") }
        val agenda = DashboardMetric("agenda", DataSource.CALENDAR, "Agenda", "Busy", "", Symbols.LIST, "blue", items = items)
        val presentation = TilePresenter.present(tile("agenda", 3, 3), agenda, context(listOf(agenda)), 230f, 93f) as TilePresentation.Agenda
        assertEquals(3, presentation.rows.size)
        assertEquals(31f, presentation.rowHeight, 0.01f)
        assertEquals(listOf("11:30", " Event 1"), presentation.rows[0].map { it.text })
        assertEquals(" Busy", presentation.rows[1][1].text)
        val timesOnly = TilePresenter.present(tile("agenda", 1, 2), agenda, context(listOf(agenda)), 70f, 60f) as TilePresentation.Agenda
        assertEquals(1, timesOnly.rows[0].size)
        assertTrue(presentation.accessibilityText.startsWith("Agenda, 11:30 Event 1, 13:00 Busy"))
    }

    @Test
    fun unconnectedSourcesStayEmptyInWidgetsAndDimmedInPreviews() {
        val disconnected = context(connected = setOf(DataSource.WEATHER))
        assertEquals(TilePresentation.Hidden, TilePresenter.present(tile("steps"), null, disconnected, 70f, 27f))
        assertEquals(TilePresentation.Hidden, TilePresenter.present(tile("retired"), null, context(showsUnavailable = true), 70f, 27f))
        val preview = TilePresenter.present(tile("steps"), null, context(connected = emptySet(), showsUnavailable = true), 70f, 27f)
        assertTrue(preview.dimmed)
        assertEquals("—", (preview as TilePresentation.Compact).value)
        assertEquals("Steps, no data", preview.accessibilityText)
        val waiting = TilePresenter.present(tile("temperature"), null, disconnected, 70f, 27f)
        assertTrue("A connected source without data keeps its tile", waiting is TilePresentation.Compact)
    }

    @Test
    fun syncTileShowsAgeAndWarnsWhenStaleOrFailed() {
        val sync = tile(WidgetTile.SYNC_ID)
        val fresh = TilePresenter.present(sync, null, context(), 70f, 27f, isTrailing = true) as TilePresentation.Sync
        assertEquals("12m", fresh.label)
        assertFalse(fresh.warning)
        assertTrue(fresh.trailing)
        assertEquals(Symbols.REFRESH, fresh.symbol)
        assertEquals("Refresh. Updated 12 minutes ago", fresh.accessibilityText)

        val stale = TilePresenter.present(sync, null, context(fetchedAt = baseNow.plusMinutes(-61)), 70f, 27f) as TilePresentation.Sync
        assertEquals("1h", stale.label)
        assertTrue(stale.warning)

        val failed = TilePresenter.present(sync, null, context(error = "Offline"), 70f, 27f) as TilePresentation.Sync
        assertTrue(failed.warning)
        assertEquals(Symbols.REFRESH_ERROR, failed.symbol)
        assertEquals("Refresh. Refresh failed", failed.accessibilityText)

        val never = TilePresenter.present(sync, null, context(fetchedAt = null), 70f, 27f) as TilePresentation.Sync
        assertEquals("—", never.label)
        val nothing = TilePresenter.present(sync, null, context(metrics = emptyList()), 70f, 27f) as TilePresentation.Sync
        assertNull(nothing.label)
        assertFalse(nothing.warning)

        val big = TilePresenter.present(tile(WidgetTile.SYNC_ID, 2, 2), null, context(), 150f, 60f) as TilePresentation.Sync
        assertEquals("Updated 12 min ago", big.label)
        val bare = TilePresenter.present(tile(WidgetTile.SYNC_ID, style = TileStyle.VALUE_ONLY), null, context(), 70f, 27f) as TilePresentation.Sync
        assertNull(bare.label)
    }

    @Test
    fun gridGeometryPlacesTilesOnCells() {
        val grid = TileGridGeometry(318f, 126f, WidgetCanvas.MEDIUM, 6f)
        assertEquals(75f, grid.cellWidth, 0.01f)
        assertEquals(27f, grid.cellHeight, 0.01f)
        assertEquals(TileFrame(81f, 33f, 156f, 60f), grid.frame(WidgetTile("t", "uv", 1, 1, 2, 2)))
        assertEquals(1 to -2, grid.cells(90f, -70f))
    }

    @Test
    fun widgetSnapshotCoversOnlyWhatTheWidgetDraws() {
        val clock = MutableClock()
        val store = ReadoutStore(InMemoryKeyValueStore(), clock)
        val empty = WidgetSnapshot.cached(store, WidgetInstanceConfiguration.homeDefault, WidgetCanvas.SMALL, env = testEnv)
        assertTrue(empty.isEmpty)

        store.dashboard.connectedSources = setOf(DataSource.WEATHER, DataSource.OURA)
        store.dashboard.replaceMetrics(listOf(uv.copy(expiresAt = baseNow.plusMinutes(300))), DataSource.WEATHER)
        clock.advanceMinutes(90)
        store.dashboard.replaceMetrics(listOf(metric("readiness", DataSource.OURA)), DataSource.OURA)
        val snapshot = WidgetSnapshot.cached(store, WidgetInstanceConfiguration(privacyMode = WidgetPrivacyMode.DISCREET), WidgetCanvas.SMALL, env = testEnv)
        assertFalse(snapshot.isEmpty)
        assertEquals("starter.small", snapshot.design.id)
        assertEquals("Readiness is not on the small starter", listOf("uv"), snapshot.displayedMetrics.map { it.id })
        assertTrue(snapshot.freshness.isStale(snapshot.now))
        assertEquals("Hidden", snapshot.metrics.first { it.id == "readiness" }.value)
        assertEquals(baseNow.plusMinutes(300), snapshot.nextExpiry)

        val preview = WidgetSnapshot.preview(WidgetDesign.starter(WidgetCanvas.LARGE), baseNow, testEnv)
        assertFalse(preview.freshness.isStale(baseNow))
        assertTrue(preview.design.tiles.filter { !it.isSync }.all { tile -> preview.metrics.any { it.id == tile.metricID } })
    }
}
