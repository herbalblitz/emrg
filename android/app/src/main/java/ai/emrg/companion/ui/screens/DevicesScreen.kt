package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.userErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.util.UUID

internal data class RemoteDevice(
    val deviceId: String,
    val workspaceId: String,
    val scopes: List<String>,
    val revoked: Boolean,
)

/**
 * The `devices` route.
 *
 * Lists paired devices via `devices.list` and revokes one via
 * `devices.revoke` (devices.manage scope). Revoking needs an explicit
 * two-tap confirm, and the device this app is currently paired as is never
 * offered a Revoke button — the server blocks self-revocation, and the UI
 * must not present it as an option. Every revoke is audit-logged host-side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val pairedHost by connection.pairedHost.collectAsState()
    val currentDeviceId = pairedHost?.deviceId
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        error = null
        result = null
        pendingRevoke = null
        loading = true
        scope.launch {
            try {
                val requestId = UUID.randomUUID().toString()
                when (val outcome = runDeviceRequest {
                    connection.request(requestId) { connection.sendDevicesList(requestId) }
                }) {
                    is DeviceRequestResult.Success -> {
                        val frame = outcome.value
                        when {
                            frame == null -> error = "Could not reach the host (not connected, or devices.manage not granted)."
                            frame.type == "error" -> error = frame.userErrorMessage()
                            else -> {
                                val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                                if (daemonError != null) {
                                    error = daemonError
                                } else {
                                    devices = frame.resultObjects("entries", "devices").mapNotNull { row ->
                                        val id = row.optNonBlank("device_id") ?: return@mapNotNull null
                                        val scopesArray = row.optJSONArray("scopes")
                                        RemoteDevice(
                                            deviceId = id,
                                            workspaceId = row.optNonBlank("workspace_id").orEmpty(),
                                            scopes = scopesArray?.let { array ->
                                                (0 until array.length()).mapNotNull {
                                                    array.optString(it).takeIf { s -> s.isNotBlank() }
                                                }
                                            }.orEmpty(),
                                            revoked = row.optBoolean("revoked"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    DeviceRequestResult.Failed -> error = DEVICE_REQUEST_FAILURE_MESSAGE
                }
            } finally {
                // Also runs when the screen's coroutine is cancelled.
                loading = false
            }
        }
    }

    fun revoke(deviceId: String) {
        pendingRevoke = null
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            when (val outcome = runDeviceRequest {
                connection.request(requestId) { connection.sendDevicesRevoke(requestId, deviceId) }
            }) {
                is DeviceRequestResult.Success -> {
                    val frame = outcome.value
                    when {
                        frame == null -> error = "Could not reach the host (not connected, or devices.manage not granted)."
                        frame.type == "error" -> error = frame.userErrorMessage()
                        else -> {
                            val revoked = frame.optResultBoolean("revoked") == true
                            result = if (revoked) "Revoked device $deviceId."
                            else "Host did not confirm the revocation of $deviceId."
                            refresh()
                        }
                    }
                }
                DeviceRequestResult.Failed -> error = DEVICE_REQUEST_FAILURE_MESSAGE
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Devices") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_DEVICES, navController) {
                ScreenNote(
                    "Paired devices on this host. Revoking a device ends its access " +
                        "immediately; the device must re-pair to come back. " +
                        "This device cannot revoke itself.",
                )
                Button(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "Loading…" else "Refresh")
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Paired devices (${devices.size})", style = MaterialTheme.typography.titleSmall)
                if (devices.isEmpty() && !loading) {
                    Text("No devices yet — tap Refresh to load.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices, key = { it.deviceId }) { device ->
                        val isCurrent = device.deviceId == currentDeviceId
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    device.deviceId + if (isCurrent) " (this device)" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (device.workspaceId.isNotBlank()) {
                                    Text("Workspace: ${device.workspaceId}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (device.scopes.isNotEmpty()) {
                                    Text("Scopes: ${device.scopes.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (device.revoked) {
                                    Text("Revoked", style = MaterialTheme.typography.bodySmall)
                                }
                                if (!device.revoked && !isCurrent) {
                                    if (pendingRevoke == device.deviceId) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { revoke(device.deviceId) }) { Text("Confirm revoke") }
                                            OutlinedButton(onClick = { pendingRevoke = null }) { Text("Cancel") }
                                        }
                                    } else {
                                        Button(onClick = { pendingRevoke = device.deviceId }) { Text("Revoke") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
