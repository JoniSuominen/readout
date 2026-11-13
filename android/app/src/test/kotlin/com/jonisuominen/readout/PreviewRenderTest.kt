package com.jonisuominen.readout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DashboardMetric
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.WidgetAppearance
import com.jonisuominen.readout.core.WidgetDesign
import com.jonisuominen.readout.ui.DashboardScreen
import com.jonisuominen.readout.ui.LayoutEditorScreen
import com.jonisuominen.readout.ui.LayoutPreview
import com.jonisuominen.readout.ui.LayoutsScreen
import com.jonisuominen.readout.ui.NavState
import com.jonisuominen.readout.ui.ReadoutTheme
import com.jonisuominen.readout.ui.SourcesScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Draws layouts and screens with sample values and saves them as images, so a
 * change to tile rendering can be looked at without a phone. The images land
 * in `app/build/outputs/previews`; CI uploads them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PreviewRenderTest {
    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<ReadoutApplication>().container

    private val sampleState = ConnectorManager.State(
        connectedSources = DataSource.entries.toSet(),
        metrics = DashboardMetric.samples(Instant.now()),
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/previews/$name.png") {
            ReadoutTheme { content() }
        }
    }

    @Test
    fun renderLayoutPreviews() {
        val store = container.store
        for (appearance in listOf(WidgetAppearance.SYSTEM, WidgetAppearance.DARK, WidgetAppearance.SKY, WidgetAppearance.TRANSPARENT)) {
            capture("layouts-${appearance.key}") {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (design in WidgetDesign.starters) {
                        // No sources are connected, so the previews draw the sample values.
                        LayoutPreview(store, design.copy(appearance = appearance), emptyList(), emptySet(), scale = 1f)
                    }
                }
            }
        }
    }

    @Test
    fun renderScreens() {
        val container = container
        capture("screen-today") { DashboardScreen(container, sampleState) }
        capture("screen-today-empty") { DashboardScreen(container, ConnectorManager.State()) }
        capture("screen-sources") { SourcesScreen(container, sampleState, NavState()) }
        capture("screen-widgets") { LayoutsScreen(container, sampleState, NavState()) }
        capture("screen-editor") { LayoutEditorScreen(container, sampleState, "starter.medium", onClose = {}) }
    }
}
