package com.emrg.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.colors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emrg.app.ui.theme.EmrgTheme

@Composable
fun EmrgAppBar(
    title: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onNavigate) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                    contentDescription = "Navigate up",
                )
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = EmrgTheme.colors.surface,
        ),
    )
}

@Composable
fun EmrgScaffold(
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { EmrgAppBar(title = "", onNavigate = {}) },
        content = content,
    )
}

@Composable
fun LoadingContent(message: String = "Loading…") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(text = message, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = EmrgTheme.colors.errorContainer,
            style = EmrgTheme.typography.bodyMedium,
        )
        androidx.compose.material3.Button(onClick = onRetry) {
            Text(text = "Retry")
        }
    }
}

@Composable
fun EmptyContent(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = EmrgTheme.typography.bodyLarge)
    }
}

@Composable
fun HostListItem(
    host: com.emrg.app.model.HostProfile,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = if (connected) 4.dp else 2.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = host.name,
                    style = EmrgTheme.typography.titleMedium,
                )
                Text(
                    text = "${host.host}:${host.port}",
                    style = EmrgTheme.typography.bodyMedium,
                    color = EmrgTheme.colors.onSurfaceVariant,
                )
            }
            Text(
                text = if (connected) "Online" else "Offline",
                style = EmrgTheme.typography.labelLarge,
                color = if (connected) EmrgTheme.colors.primary else EmrgTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SessionListItem(
    session: com.emrg.app.model.SessionSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(text = session.title, style = EmrgTheme.typography.titleMedium)
                Text(
                    text = session.cwd,
                    style = EmrgTheme.typography.bodyMedium,
                    color = EmrgTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = session.updatedAt.take(16),
                style = EmrgTheme.typography.labelMedium,
                color = EmrgTheme.colors.onSurfaceVariant,
            )
        }
    }
}
