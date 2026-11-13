package com.jonisuominen.readout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.connect.SelectableCalendar
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DataSource
import kotlinx.coroutines.launch

private const val NO_ACCESS = "Connect Calendar and allow access before choosing calendars. You can change access in Settings."

@Composable
fun CalendarSettingsScreen(container: AppContainer, state: ConnectorManager.State, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = container.store.calendar
    val connector = container.calendarConnector
    var calendars by remember { mutableStateOf<List<SelectableCalendar>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedIDs by remember { mutableStateOf(preferences.selectedCalendarIDs) }
    var showTitles by remember { mutableStateOf(preferences.showEventTitles) }

    val controlsUnavailable = state.isDeletingLocalData ||
        DataSource.CALENDAR !in state.connectedSources ||
        state.activity(DataSource.CALENDAR) == ConnectorManager.Activity.Disconnecting

    // The Connect action owns the permission prompt. Merely opening this
    // screen must not request access or read calendars without it.
    suspend fun load() {
        isLoading = true
        selectedIDs = preferences.selectedCalendarIDs
        showTitles = preferences.showEventTitles
        if (!connector.hasAccess) {
            calendars = emptyList()
            preferences.invalidateSnapshot()
            container.manager.calendarPreferencesDidChange()
            errorMessage = NO_ACCESS
        } else {
            calendars = try {
                connector.calendars().sortedWith(
                    compareBy<SelectableCalendar, String>(String.CASE_INSENSITIVE_ORDER) { it.accountName }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenBy { it.id },
                )
            } catch (_: Exception) {
                emptyList()
            }
            errorMessage = null
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { load() }
    // Calendar accounts and permissions change in Settings; reload on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { load() } }

    fun refreshAfterPreferenceChange() {
        // The preference setter already removed the stored snapshot. Drop the
        // in-memory copy before the fetch can suspend.
        container.scope.launch { container.manager.applyCalendarPreferences() }
    }

    fun saveSelection(selection: Set<String>) {
        if (controlsUnavailable) return
        selectedIDs = selection
        preferences.selectedCalendarIDs = selection
        refreshAfterPreferenceChange()
    }

    Scaffold(topBar = { BackTopBar("Calendar privacy", onClose) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Footnote("Only calendars you select are used. Leaving every calendar unchecked shows no events.")

            SectionTitle("Calendars")
            SectionCard {
                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading calendars…")
                    }
                }
                val error = errorMessage
                if (error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = WarningColor, modifier = Modifier.size(18.dp))
                        Text(error, color = WarningColor)
                    }
                    TextButton(onClick = { openAppSettings(context) }) { Text("Open Settings") }
                } else if (!isLoading && calendars.isEmpty()) {
                    Footnote("No calendars found.")
                }
                for (calendar in calendars) {
                    val enabled = !controlsUnavailable && !isLoading && error == null
                    SwitchRow(
                        title = calendar.title,
                        subtitle = calendar.accountName,
                        checked = calendar.id in selectedIDs,
                        enabled = enabled,
                    ) { isSelected ->
                        saveSelection(if (isSelected) selectedIDs + calendar.id else selectedIDs - calendar.id)
                    }
                }
                if (selectedIDs.isEmpty()) {
                    Footnote("No calendars selected. No events show until you select one.")
                } else {
                    TextButton(onClick = { saveSelection(emptySet()) }, enabled = !controlsUnavailable) { Text("Deselect all calendars") }
                }
                if (!isLoading && error == null && (selectedIDs - calendars.map { it.id }.toSet()).isNotEmpty()) {
                    Footnote("Some selected calendars are no longer available. Only selected calendars in this list can contribute events.")
                }
            }

            if (errorMessage == null) {
                SectionTitle("Missing a calendar?")
                SectionCard {
                    Footnote("Readout shows every calendar synced to this phone: Google, Outlook, Exchange, and others. To add one, add the account in Android Settings under Passwords & accounts, or in your calendar app, and make sure its calendar sync is on. No separate sign-in is needed here; this list updates when you come back.")
                }
            }

            SectionTitle("Event title privacy")
            SectionCard {
                SwitchRow(
                    title = "Show event titles",
                    subtitle = null,
                    checked = showTitles,
                    enabled = !controlsUnavailable && (errorMessage == null || showTitles),
                ) { newValue ->
                    if (!controlsUnavailable) {
                        showTitles = newValue
                        preferences.showEventTitles = newValue
                        refreshAfterPreferenceChange()
                    }
                }
                Footnote("Off shows only availability and timing. On allows event titles in the app and in widgets set to show values. Discreet widgets always hide titles.")
            }

            SectionTitle("System permission")
            SectionCard {
                Footnote("This list reads calendars only with Calendar access. Disconnecting Calendar here does not revoke the Android permission. Manage permission in Settings.")
                TextButton(onClick = { openAppSettings(context) }) { Text("Open Settings") }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            if (!subtitle.isNullOrEmpty()) Footnote(subtitle)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
