package com.jonisuominen.readout.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R

sealed interface Overlay {
    data object None : Overlay
    data object Privacy : Overlay
    data object CalendarSettings : Overlay
    data object LocationPicker : Overlay
    data class Editor(val designID: String) : Overlay
}

/** Which tab and which full-screen page are showing. */
class NavState {
    var tab by mutableIntStateOf(0)
    var overlay by mutableStateOf<Overlay>(Overlay.None)
}

private class Tab(val title: String, val icon: Int)

private val tabs = listOf(
    Tab("Today", R.drawable.ic_today),
    Tab("Sources", R.drawable.ic_sources),
    Tab("Widgets", R.drawable.ic_widgets),
)

@Composable
fun ReadoutApp(container: AppContainer, nav: NavState) {
    val state by container.manager.state.collectAsStateWithLifecycle()
    val close = { nav.overlay = Overlay.None }

    when (val overlay = nav.overlay) {
        Overlay.None -> Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = nav.tab == index,
                            onClick = { nav.tab = index },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(tab.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (nav.tab) {
                    0 -> DashboardScreen(container, state)
                    1 -> SourcesScreen(container, state, nav)
                    else -> LayoutsScreen(container, state, nav)
                }
            }
        }
        Overlay.Privacy -> {
            BackHandler(onBack = close)
            PrivacyPolicyScreen(onClose = close)
        }
        Overlay.CalendarSettings -> {
            BackHandler(onBack = close)
            CalendarSettingsScreen(container, state, onClose = close)
        }
        Overlay.LocationPicker -> {
            BackHandler(onBack = close)
            LocationPickerScreen(container, onClose = close)
        }
        is Overlay.Editor -> {
            BackHandler(onBack = close)
            LayoutEditorScreen(container, state, overlay.designID, onClose = close)
        }
    }
}
