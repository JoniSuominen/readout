package com.jonisuominen.readout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.WidgetCanvas

/** Lists tile layouts by widget size. Each placed widget picks one in its settings. */
@Composable
fun LayoutsScreen(container: AppContainer, state: ConnectorManager.State, nav: NavState) {
    val store = container.store
    var designs by remember { mutableStateOf(store.designs.designs) }

    Scaffold(topBar = { TopAppBar(title = { Text("Widgets") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (canvas in WidgetCanvas.entries) {
                SectionTitle(canvas.displayName)
                SectionCard {
                    val ofSize = designs.filter { it.canvas == canvas }
                    ofSize.forEachIndexed { index, design ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClickLabel = "Edit layout") { nav.overlay = Overlay.Editor(design.id) }
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    design.name.ifEmpty { "Untitled" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                LayoutPreview(
                                    store = store,
                                    design = design,
                                    metrics = state.metrics,
                                    connectedSources = state.connectedSources,
                                    scale = if (canvas == WidgetCanvas.SMALL) 0.9f else 0.8f,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            val design = store.designs.newDesign(canvas)
                            store.designs.save(design)
                            designs = store.designs.designs
                            nav.overlay = Overlay.Editor(design.id)
                        },
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("New ${canvas.displayName.lowercase()} layout", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            Footnote("Add a widget: touch and hold an empty spot on the home screen, tap Widgets, and choose Readout. To pick a layout, touch and hold the widget and tap its settings or edit button.")
            Footnote("The refresh tile updates the widget. Its number is the minutes since the oldest value shown was fetched.")
        }
    }
}
