package com.jonisuominen.readout.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jonisuominen.readout.container
import com.jonisuominen.readout.core.WidgetCanvas
import com.jonisuominen.readout.core.WidgetDesign
import com.jonisuominen.readout.core.WidgetInstanceConfiguration
import com.jonisuominen.readout.core.WidgetPrivacyMode
import com.jonisuominen.readout.ui.Footnote
import com.jonisuominen.readout.ui.ReadoutTheme
import com.jonisuominen.readout.ui.SectionCard
import com.jonisuominen.readout.ui.SectionTitle

/** A placed widget's settings: which layout it draws and whether private values show. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Leaving without saving keeps the widget's current settings.
        setResult(RESULT_CANCELED, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = container
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        val canvas = when (provider) {
            MediumWidgetReceiver::class.java.name -> WidgetCanvas.MEDIUM
            LargeWidgetReceiver::class.java.name -> WidgetCanvas.LARGE
            else -> WidgetCanvas.SMALL
        }
        val current = container.widgetConfigs.configuration(appWidgetId)
        val designs = container.store.designs.designs(canvas).ifEmpty { listOf(WidgetDesign.starter(canvas)) }
        val selectedDesign = container.store.designs.resolvedDesign(current.designID, canvas).id

        setContent {
            ReadoutTheme {
                WidgetSettings(
                    canvas = canvas,
                    designs = designs,
                    initialDesignID = selectedDesign,
                    initialPrivacy = current.privacyMode,
                    onDone = { designID, privacy ->
                        container.widgetConfigs.save(appWidgetId, WidgetInstanceConfiguration(designID = designID, privacyMode = privacy))
                        WidgetUpdater.requestUpdate(this)
                        WidgetUpdater.ensureTicking(this)
                        setResult(RESULT_OK, result)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun WidgetSettings(
    canvas: WidgetCanvas,
    designs: List<WidgetDesign>,
    initialDesignID: String,
    initialPrivacy: WidgetPrivacyMode,
    onDone: (String, WidgetPrivacyMode) -> Unit,
) {
    var designID by remember { mutableStateOf(initialDesignID) }
    var privacy by remember { mutableStateOf(initialPrivacy) }
    Scaffold(topBar = { TopAppBar(title = { Text("${canvas.displayName} widget") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Layout")
            SectionCard {
                for (design in designs) {
                    RadioRow(design.name.ifEmpty { "Untitled" }, null, design.id == designID) { designID = design.id }
                }
                Footnote("Design layouts in the Widgets tab of the app.")
            }
            SectionTitle("Privacy")
            SectionCard {
                for (mode in WidgetPrivacyMode.entries) {
                    RadioRow(mode.displayName, mode.explanation, mode == privacy) { privacy = mode }
                }
                Footnote("Event titles also require permission in Calendar settings.")
            }
            Button(onClick = { onDone(designID, privacy) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(title)
            if (subtitle != null) Footnote(subtitle)
        }
    }
}
