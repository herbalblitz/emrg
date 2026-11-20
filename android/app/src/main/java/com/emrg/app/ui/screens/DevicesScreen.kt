package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.model.DeviceInfo
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __devices = remember { mutableStateOf(emptyList<DeviceInfo>()) }
    var devices: List<DeviceInfo> by __devices
    val __loading = remember { mutableStateOf(true) }
    var loading: Boolean by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? by __error

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                devices = repository.devices()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Devices") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (!loading && error == null) {
                Text(
                    text = "Paired devices (${devices.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(devices, key = { it.id }) { device ->
                        androidx.compose.material3.Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            androidx.compose.foundation.layout.Column(
                                modifier = androidx.compose.foundation.layout.Modifier.padding(16.dp),
                            ) {
                                androidx.compose.material3.Row(
                                    modifier = androidx.compose.foundation.layout.Modifier.fillMaxWidth(),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = androidx.compose.foundation.layout.Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = device.name.ifEmpty { device.id },
                                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text =
                                                "connected=${device.connected} activeTurn=${device.activeTurn}",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    repository.revokeDevice(deviceId = device.id)
                                                } catch (e: Exception) {
                                                    error = e.message
                                                }
                                            }
                                        },
                                    ) {
                                        Text(text = "Revoke")
                                    }
                                }
                                Text(
                                    text =
                                        "scopes=${device.scopes.joinToString(",")} lastSeen=${device.lastSeen}",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
