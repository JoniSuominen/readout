package com.jonisuominen.readout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jonisuominen.readout.AppContainer
import com.jonisuominen.readout.R
import com.jonisuominen.readout.core.ConnectorManager
import com.jonisuominen.readout.core.DashboardMetric
import com.jonisuominen.readout.core.DataSource
import com.jonisuominen.readout.core.DisplayEnvironment
import com.jonisuominen.readout.core.EventTiming
import com.jonisuominen.readout.core.TileTint
import com.jonisuominen.readout.core.UpdatedLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** The current time, stepped each minute so countdowns and ages stay current. */
@Composable
fun minuteClock(): Instant {
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            value = Instant.now()
        }
    }
    return now
}

@Composable
fun DashboardScreen(container: AppContainer, state: ConnectorManager.State) {
    val refresh: () -> Unit = { container.scope.launch { container.manager.refreshConnectedSources() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = refresh, enabled = state.connectedSources.isNotEmpty()) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh connected data sources")
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceRefreshWarnings(state)
                if (state.metrics.isEmpty()) {
                    EmptyDashboard(state)
                } else {
                    val now = minuteClock()
                    val env = DisplayEnvironment.current
                    // Large text gets one card per row.
                    val columns = if (LocalConfiguration.current.fontScale >= 1.5f) 1 else 2
                    for (row in state.metrics.chunked(columns)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (metric in row) {
                                MetricCard(metric, now, env, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            if (row.size < columns) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    SourceRefreshDates(container, state, now)
                }
            }
        }
    }
}

@Composable
private fun EmptyDashboard(state: ConnectorManager.State) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
        if (state.isRefreshing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Refreshing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_link),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    if (state.connectedSources.isEmpty()) "Nothing connected" else "No data yet",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Connect weather, Health Connect, Oura, or Calendar in Sources.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MetricCard(metric: DashboardMetric, now: Instant, env: DisplayEnvironment, modifier: Modifier = Modifier) {
    val tint = TileTint.color(metric.tint).current()
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(SymbolIcons.resource(metric.symbol)), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(metric.title, style = MaterialTheme.typography.labelLarge, color = tint)
            }
            val start = metric.observedAt
            val items = metric.items
            if (metric.id == "nextEvent" && start != null) {
                // Start time and title belong together on one line.
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(if (EventTiming.isOngoing(start, metric.endsAt, now)) "Now" else EventTiming.startText(start, now, env))
                        }
                        append(" " + metric.displayValue)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Footnote(EventTiming.relativeText(start, metric.endsAt, now, env))
            } else if (metric.id == "agenda" && !items.isNullOrEmpty()) {
                for (item in items) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(EventTiming.agendaText(item, now, env)) }
                            append(" " + (item.title ?: "Busy"))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(metric.displayValue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (metric.detail.isNotEmpty()) Footnote(metric.detail)
            }
            Footnote("From ${metric.originLabel}")
        }
    }
}

@Composable
private fun SourceRefreshDates(container: AppContainer, state: ConnectorManager.State, now: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (source in DataSource.entries.filter { it in state.connectedSources }) {
            val date = container.store.dashboard.updatedAt(source)
            Footnote(
                if (date != null) {
                    "${source.displayName}: ${UpdatedLabel.long(date, now).lowercase()}"
                } else {
                    "${source.displayName}: waiting for a successful refresh"
                },
            )
        }
    }
}

@Composable
fun SourceRefreshWarnings(state: ConnectorManager.State) {
    val failed = DataSource.entries.mapNotNull { source ->
        (state.activity(source) as? ConnectorManager.Activity.Failed)?.let { source to it.message }
    }
    if (failed.isEmpty()) return
    val color = WarningColor
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text("Some sources need attention", style = MaterialTheme.typography.titleSmall, color = color)
        }
        for ((source, message) in failed) {
            Text("${source.displayName}: $message", style = MaterialTheme.typography.bodySmall, color = color)
        }
        Text(
            "Cached values may be older. Manage connections or try Refresh in Sources.",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
