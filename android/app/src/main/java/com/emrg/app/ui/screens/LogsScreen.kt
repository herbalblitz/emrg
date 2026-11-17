package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.CheckedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    var source by remember { mutableStateOf("daemon") }
    var lines by remember { mutableStateOf<com.emrg.app.model.LogPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Logs") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            androidx.compose.material3.Text(
                text = "Source: ${source.take(1).uppercase()}${source.drop(1)}",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Button(onClick = { /* tail logs */ }) {
                Text(text = "Tail")
            }
            lines?.let { page ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    items(page.lines, key = { it }) { line ->
                        androidx.compose.material3.Text(text = line, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AuditScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    var entries by remember { mutableStateOf<List<com.emrg.app.model.AuditEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Audit log") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(entries, key = { it.ts }) { e ->
                    androidx.compose.material3.Text(
                        text = "${e.ts}  ${e.device}  ${e.action}  [${e.decision}]",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    var devices by remember { mutableStateOf<List<com.emrg.app.model.DeviceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Devices") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(devices, key = { it.id }) { d ->
                    androidx.compose.material3.Text(
                        text = "${d.name}  (${d.id})  ${if (d.connected) "online" else "offline"}",
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    var danger by remember { mutableStateOf(false) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Settings") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.Text(text = "Gateway settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Switch(checked = danger, onCheckedChange = { danger = it })
                androidx.compose.material3.Text(text = "Allow danger-full-access exec")
            }
        }
    }
}

@Composable
fun AboutScreen(
    navController: androidx.navigation.NavHostController,
) {
    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "About") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.Text(text = "EMRG Android", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.material3.Text(text = "Version 0.3.55 (build 355)")
            androidx.compose.material3.Text(text = "Remote-first EMRG client. Connects to emrg-gateway on a host over pinned TLS.")
        }
    }
}
