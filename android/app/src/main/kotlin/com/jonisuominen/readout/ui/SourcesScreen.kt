package com.jonisuominen.readout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.DisconnectResult
import com.jonisuominen.readout.core.OuraConnectionMethod
import com.jonisuominen.readout.core.RainUnit
import com.jonisuominen.readout.core.TemperatureUnit
import com.jonisuominen.readout.core.WindUnit
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(container: AppContainer, state: ConnectorManager.State, nav: NavState) {
    val manager = container.manager
    var pendingSource by remember { mutableStateOf<DataSource?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // Unit choices are read from the store; this counter redraws after a change.
    var unitsRevision by remember { mutableIntStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("Sources") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Oura connection")
            SectionCard {
                ChoiceRow(
                    label = "Connect Oura through",
                    options = OuraConnectionMethod.entries,
                    selected = state.ouraConnectionMethod,
                    name = { it.displayName },
                    enabled = !state.activity(DataSource.OURA).isBusy && !state.isDeletingLocalData,
                    isOptionEnabled = { it != OuraConnectionMethod.DIRECT || manager.directOuraAvailable },
                    onSelect = { method -> container.scope.launch { manager.setOuraConnectionMethod(method) } },
                )
                if (!manager.directOuraAvailable) {
                    Footnote("Oura account access is unavailable in this build. You can use sleep data that Oura shares with Health Connect.")
                }
                Footnote(
                    "Changing the connection method disconnects the current Oura source. Connect again to use the new method.",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionTitle("Sources")
            SectionCard {
                DataSource.entries.forEachIndexed { index, source ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    SourceRow(container, state, source, nav, onConnect = { pendingSource = source })
                }
            }

            SectionTitle("Units")
            SectionCard {
                val units = container.store.units
                val refreshWeather = {
                    unitsRevision++
                    // Weather values are stored already formatted, so new units need a fetch.
                    if (DataSource.WEATHER in state.connectedSources) {
                        container.scope.launch { manager.refresh(DataSource.WEATHER) }
                    }
                }
                val enabled = !state.isDeletingLocalData
                val selection = remember(unitsRevision, state.isDeletingLocalData) { units.selection }
                ChoiceRow("Temperature", TemperatureUnit.entries, selection.temperature, { it.displayName }, enabled) {
                    units.temperature = it
                    refreshWeather()
                }
                ChoiceRow("Rain", RainUnit.entries, selection.rain, { it.displayName }, enabled) {
                    units.rain = it
                    refreshWeather()
                }
                ChoiceRow("Wind", WindUnit.entries, selection.wind, { it.displayName }, enabled) {
                    units.wind = it
                    refreshWeather()
                }
                Footnote("Automatic follows your phone's region. Changing a unit refreshes the weather.")
            }

            SectionTitle("Privacy")
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Footnote("Cached values stay on this phone for the app and its widgets. Weather requests send an approximate location to Open-Meteo. Oura account access also contacts Oura and the secure sign-in service.")
                }
                TextButton(onClick = { nav.overlay = Overlay.Privacy }) { Text("Read privacy policy") }
            }

            SectionCard {
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = !state.isDeletingLocalData,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete local data") }
                if (state.isDeletingLocalData) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Footnote("Deleting local data and disconnecting…")
                    }
                }
                state.dataDeletionResult?.let { result ->
                    Footnote("Cached dashboard and widget data removed.")
                    DisconnectFeedback(container, state, DataSource.OURA, result)
                }
            }
        }
    }

    pendingSource?.let { source ->
        PermissionPrimer(
            source = source,
            ouraMethod = state.ouraConnectionMethod,
            onDismiss = { pendingSource = null },
            onConnect = {
                pendingSource = null
                container.scope.launch { manager.connect(source) }
            },
            onChooseCity = {
                pendingSource = null
                nav.overlay = Overlay.LocationPicker
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete local data?") },
            text = {
                Text("This removes cached metrics, widget layouts, the weather location, app preferences, and connection state. Saved Oura credentials are removed and Oura access is revoked where possible; the result is shown here. Provider records and Android permissions remain unchanged.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        container.scope.launch { manager.deleteLocalData() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete local data") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun sourceColor(source: DataSource): Color = when (source) {
    DataSource.WEATHER -> Color(0xFFC76A00)
    DataSource.HEALTH -> Color(0xFFE0245E)
    DataSource.OURA -> Color(0xFF9B40CC)
    DataSource.CALENDAR -> Color(0xFF0A6CDF)
}

private fun description(source: DataSource, ouraMethod: OuraConnectionMethod): String = when (source) {
    DataSource.WEATHER -> "Temperature, UV index, rain, wind, air quality, and tomorrow's forecast"
    DataSource.HEALTH -> "Steps, activity, workouts, heart rate, and HRV"
    DataSource.OURA ->
        if (ouraMethod == OuraConnectionMethod.DIRECT) "Readiness and sleep from your Oura account" else "Oura sleep shared with Health Connect"
    DataSource.CALENDAR -> "Calendars on this phone, including Google and Outlook accounts"
}

@Composable
private fun SourceRow(
    container: AppContainer,
    state: ConnectorManager.State,
    source: DataSource,
    nav: NavState,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val activity = state.activity(source)
    val connected = source in state.connectedSources
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(38.dp).background(sourceColor(source), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(SymbolIcons.resource(source.symbol)), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    description(source, state.ouraConnectionMethod),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (activity is ConnectorManager.Activity.Failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Text(activity.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        SourceButton(container, state, source, onConnect)
        if (source == DataSource.WEATHER && connected) {
            TextButton(
                onClick = { nav.overlay = Overlay.LocationPicker },
                enabled = !state.isDeletingLocalData && !activity.isBusy,
            ) {
                Icon(painterResource(R.drawable.ic_location), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Location: ${container.store.weatherLocation.location?.name ?: "Current location"}", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (source == DataSource.WEATHER) {
            // Open-Meteo data is licensed CC BY 4.0 and requires this credit.
            TextButton(onClick = { openLink(context, "https://open-meteo.com/") }) { Text("Weather data by Open-Meteo.com") }
        }
        if (source == DataSource.CALENDAR && connected) {
            TextButton(onClick = { nav.overlay = Overlay.CalendarSettings }, enabled = !state.isDeletingLocalData) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Choose calendars and title privacy", modifier = Modifier.padding(start = 6.dp))
            }
            Footnote(
                if (container.store.calendar.selectedCalendarIDs.isEmpty()) {
                    "No calendars selected. Choose at least one to show events."
                } else {
                    "Event titles are ${if (container.store.calendar.showEventTitles) "shown" else "hidden"}."
                },
            )
        }
        state.disconnectResults[source]?.let { DisconnectFeedback(container, state, source, it) }
    }
}

@Composable
private fun SourceButton(container: AppContainer, state: ConnectorManager.State, source: DataSource, onConnect: () -> Unit) {
    val manager = container.manager
    val activity = state.activity(source)
    when {
        activity.isBusy -> Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                when (activity) {
                    ConnectorManager.Activity.Connecting -> "Connecting…"
                    ConnectorManager.Activity.Disconnecting -> "Disconnecting…"
                    else -> "Refreshing…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        source in state.connectedSources -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Connected", modifier = Modifier.padding(start = 6.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        onClick = {
                            expanded = false
                            container.scope.launch { manager.refresh(source) }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            expanded = false
                            container.scope.launch { manager.disconnect(source) }
                        },
                    )
                }
            }
        }
        else -> Button(
            onClick = onConnect,
            enabled = !state.isDeletingLocalData &&
                state.disconnectResults[source]?.localCredentialsCleared != false &&
                !(source == DataSource.OURA && state.ouraConnectionMethod == OuraConnectionMethod.DIRECT && !manager.directOuraAvailable),
        ) { Text("Connect") }
    }
}

@Composable
private fun DisconnectFeedback(container: AppContainer, state: ConnectorManager.State, source: DataSource, result: DisconnectResult) {
    val context = LocalContext.current
    val color = when {
        !result.localCredentialsCleared -> MaterialTheme.colorScheme.error
        result.requiresAttention -> WarningColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                painterResource(if (result.requiresAttention) R.drawable.ic_warning else R.drawable.ic_shield),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(result.warningMessage ?: result.statusMessage, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (source == DataSource.OURA) {
            if (!result.localCredentialsCleared) {
                TextButton(
                    onClick = { container.scope.launch { container.manager.retryOuraDisconnection() } },
                    enabled = !state.isDeletingLocalData && !state.activity(DataSource.OURA).isBusy,
                ) { Text("Retry removing Oura credentials") }
            }
            if (result.requiresAttention || result.remoteAuthorization == DisconnectResult.RemoteAuthorization.NO_STORED_CREDENTIALS) {
                TextButton(onClick = { openLink(context, "https://cloud.ouraring.com/") }) { Text("Review access in your Oura account") }
            }
        }
    }
}

@Composable
private fun PermissionPrimer(
    source: DataSource,
    ouraMethod: OuraConnectionMethod,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onChooseCity: () -> Unit,
) {
    val direct = source == DataSource.OURA && ouraMethod == OuraConnectionMethod.DIRECT
    val explanation = when (source) {
        DataSource.WEATHER ->
            "Weather comes from Open-Meteo. Use your approximate location, or choose a city if you prefer not to share location. Only a rounded coordinate is sent."
        DataSource.HEALTH ->
            "Reads your step count, active energy, exercise sessions, distance, heart rate, resting heart rate, and heart rate variability from Health Connect. Pick which one each tile shows in Widgets. Nothing is written to your health records. Choose the categories to share on the next screen."
        DataSource.OURA -> if (direct) {
            "Sign in with Oura to read your readiness and sleep. Oura and a secure sign-in service handle authorization and credential refresh. Access credentials are encrypted on this phone. You can disconnect from Sources at any time."
        } else {
            "Reads sleep records from Health Connect and shows only records written by Oura. First enable Health Connect sharing in the Oura app. This connection does not include Oura Readiness or sleep scores."
        }
        DataSource.CALENDAR ->
            "Reads calendars on this phone, including Google accounts already added to Android. After connecting, choose which calendars to include; none are selected automatically. Event titles stay hidden unless you turn them on. Your events are never changed."
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(SymbolIcons.resource(source.symbol)), contentDescription = null)
                Text("Connect ${source.displayName}", style = MaterialTheme.typography.headlineSmall)
            }
            Text(explanation)
            Text(
                "Your selection controls what appears in the app and widgets. Disconnecting stops using this source and clears its cached data. System permissions are managed in Android Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        source == DataSource.WEATHER -> "Use my location"
                        direct -> "Continue to Oura"
                        else -> "Continue"
                    },
                )
            }
            if (source == DataSource.WEATHER) {
                OutlinedButton(onClick = onChooseCity, modifier = Modifier.fillMaxWidth()) { Text("Choose a city") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
