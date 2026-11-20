package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

/**
 * The `sandbox` route.
 *
 * Sets the sandbox mode via `sandbox.set` and manages roots via `sandbox.roots`
 * (sandbox.manage scope). Roots are workspace-relative subpaths only — the UI
 * never accepts an absolute path, so a root cannot be offered outside the
 * approved workspace; the server validates containment again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SandboxScreen(
    connection: CompanionConnection,
    navController: NavHostController,
    sessionId: String = "",
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("workspace-write") }
    var modeExpanded by remember { mutableStateOf(false) }
    var root by remember { mutableStateOf("") }
    var roots by remember { mutableStateOf<List<String>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val effectiveSessionId = sessionId.takeIf { it.isNotBlank() }

    fun call(
        type: String,
        op: String? = null,
        path: String? = null,
        onResult: (ai.emrg.companion.ProtocolFrame?) -> Unit = {},
    ) {
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 30_000) {
                when (type) {
                    "sandbox.set" -> connection.sendSandboxSet(requestId, mode, effectiveSessionId)
                    else -> connection.sendSandboxRoots(requestId, op ?: "list", path, effectiveSessionId)
                }
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or sandbox.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> onResult(frame)
            }
        }
    }

    fun refreshRoots() {
        call("sandbox.roots", op = "list") { frame ->
            roots = frame?.resultStrings("roots", "paths").orEmpty()
            result = if (roots.isEmpty()) "No extra roots." else null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sandbox") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_SANDBOX, navController) {
                ScreenNote(
                    "Sandbox roots must stay inside the approved workspace. " +
                        "Enter a workspace-relative subpath (for example \"src\"); absolute paths are rejected.",
                )
                effectiveSessionId?.let { Text("Session: $it", style = MaterialTheme.typography.bodySmall) }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { modeExpanded = !modeExpanded }) { Text(mode) }
                    if (modeExpanded) {
                        DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                            listOf("read-only", "workspace-write", "danger-full-access").forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = { mode = m; modeExpanded = false },
                                )
                            }
                        }
                    }
                    Button(onClick = {
                        call("sandbox.set") { result = "Sandbox set to $mode" }
                    }) { Text("Apply mode") }
                }
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Writable root (workspace-relative)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = root.isNotBlank(),
                        onClick = {
                            val r = root.trim()
                            call("sandbox.roots", op = "add", path = r) {
                                result = "Added root $r"
                                root = ""
                                refreshRoots()
                            }
                        },
                    ) { Text("Add root") }
                    OutlinedButton(onClick = { refreshRoots() }) { Text("List roots") }
                }
                if (roots.isNotEmpty()) {
                    Text("Current roots", style = MaterialTheme.typography.titleSmall)
                    roots.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(r, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                call("sandbox.roots", op = "remove", path = r) {
                                    result = "Removed root $r"
                                    refreshRoots()
                                }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
