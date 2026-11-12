package com.jonisuominen.readout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.WeatherLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Chooses the weather location: the phone's approximate location, or a city. */
@Composable
fun LocationPickerScreen(container: AppContainer, onClose: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pick: (WeatherLocation?) -> Unit = { location ->
        onClose()
        container.scope.launch { container.manager.setWeatherLocation(location) }
    }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            errorMessage = null
            isSearching = false
            return@LaunchedEffect
        }
        // Wait for typing to pause before searching.
        delay(350)
        isSearching = true
        try {
            results = container.openMeteo.searchPlaces(query)
            errorMessage = null
        } catch (error: CancellationException) {
            // A newer search replaced this one.
            throw error
        } catch (_: Exception) {
            errorMessage = "City search is unavailable. Check your connection."
        } finally {
            isSearching = false
        }
    }

    Scaffold(topBar = { BackTopBar("Weather location", onClose) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pick(null) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = null)
                    Text("Use my location")
                }
            }
            // Open-Meteo data is licensed CC BY 4.0 and requires this credit.
            TextButton(onClick = { openLink(context, "https://open-meteo.com/") }) { Text("Weather and city search by Open-Meteo.com") }

            SectionTitle("Cities")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search cities") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            )
            SectionCard {
                if (isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                errorMessage?.let { Text(it, color = WarningColor) }
                results.forEachIndexed { index, place ->
                    if (index > 0) HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { pick(place) }.padding(vertical = 10.dp),
                    ) {
                        Text(place.name)
                        place.detail?.let { Footnote(it) }
                    }
                }
                if (!isSearching && results.isEmpty() && errorMessage == null) {
                    Footnote(if (query.trim().length >= 2) "No matches" else "Type at least two letters")
                }
            }
        }
    }
}
