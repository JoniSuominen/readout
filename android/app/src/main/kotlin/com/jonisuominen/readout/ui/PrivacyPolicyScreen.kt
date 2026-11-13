package com.jonisuominen.readout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val policy = listOf(
    "Data you choose to connect" to listOf(
        "The app reads the Health Connect categories, Oura account metrics, and calendars you authorize. Health and calendar connections read records on this device. Oura account access retrieves data from Oura over the network.",
        "Weather and air quality are fetched from Open-Meteo using a coordinate rounded to about 1 km, either your approximate location or a city you choose. City search sends the text you type to Open-Meteo.",
    ),
    "Storage and sharing" to listOf(
        "A local cache stores the values needed by the app and its widgets in the app's private storage. It is excluded from Android backups. Widgets and background refresh may update weather, Health Connect, Calendar, and Oura values without opening the app. Oura credentials are encrypted with a key held in the Android Keystore. Health and calendar data are never sold or used for advertising.",
    ),
    "Your controls" to listOf(
        "Choose exactly which calendars are read, and whether event titles appear. Calendar titles are hidden by default. Each widget uses a layout you design and its own privacy mode. Discreet widgets hide health values and event titles, including from accessibility labels.",
        "Disconnect stops use of a source and clears its cached values. Saved Oura credentials are removed and Oura access is revoked where possible, and the result is reported. Delete local data also clears cached values, layouts, the weather location, connection state, and preferences. These actions do not delete records from Health Connect, Oura, or your calendar accounts. Revoke permissions in Android Settings and Health Connect, and review Oura authorization in your Oura account settings.",
    ),
    "Widget freshness" to listOf(
        "Widgets show the last successfully fetched data and its age in minutes. Tap the refresh tile to update. Android decides when background refresh runs.",
        "Tapping a tile opens Readout, which hands calendar tiles to your calendar app. The link names only the tile, never its value.",
    ),
)

@Composable
fun PrivacyPolicyScreen(onClose: () -> Unit) {
    Scaffold(topBar = { BackTopBar("Privacy policy", onClose) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((title, paragraphs) in policy) {
                SectionTitle(title)
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (paragraph in paragraphs) Text(paragraph)
                    }
                }
            }
        }
    }
}
