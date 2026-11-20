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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The `terminal` route.
 *
 * Runs a command via `terminal.exec` (terminal.exec scope) with a sandbox mode,
 * a timeout, and — for the danger-full-access tier — an explicit per-run
 * `confirm_danger` checkbox. The checkbox is never pre-checked and never
 * remembered: it resets after every run, so a dangerous command always needs a
 * fresh, deliberate tap. Cancel sends `terminal.exec_cancel` for the exec id
 * the host returned. Commands run in the approved workspace; no cwd is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("workspace-write") }
    var modeExpanded by remember { mutableStateOf(false) }
    var timeoutText by remember { mutableStateOf("60") }
    var confirmDanger by remember { mutableStateOf(false) }
    var activeExecId by remember { mutableStateOf<String?>(null) }

    val isDangerMode = mode == "danger-full-access"

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        if (isDangerMode && !confirmDanger) {
            error = "danger-full-access needs the confirmation checkbox ticked for this run."
            return
        }
        running = true
        error = null
        val timeoutSeconds = timeoutText.toIntOrNull()?.coerceIn(1, 600) ?: 60
        val dangerConfirmed = confirmDanger
        // The confirmation covers this run only: clear it before sending.
        confirmDanger = false
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(
                requestId,
                timeoutMs = (timeoutSeconds + 30L) * 1000L,
            ) {
                connection.sendTerminalExec(requestId, cmd, mode, timeoutSeconds, dangerConfirmed)
            }
            running = false
            activeExecId = null
            when {
                frame == null -> error = "Could not reach the host (not connected, or terminal.exec not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val execId = frame.optResultString("exec_id")
                    val stdout = frame.optResultString("stdout").orEmpty()
                    val stderr = frame.optResultString("stderr").orEmpty()
                    val exitCode = if (frame.raw.has("exit_code")) frame.raw.optInt("exit_code").toString() else "?"
                    output += "\n$ $cmd\n$stdout" +
                        (if (stderr.isNotEmpty()) "\n$stderr" else "") +
                        "\n(exit $exitCode)"
                    if (execId != null) activeExecId = execId
                }
            }
        }
    }

    fun cancelExec() {
        val execId = activeExecId ?: return
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            connection.request(requestId) { connection.sendTerminalExecCancel(requestId, execId) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Terminal") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.EXEC_TERMINAL, navController) {
                ScreenNote("Commands run on the host inside the approved workspace only.")
                ScreenError(error)
                Text("Sandbox mode: $mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { modeExpanded = !modeExpanded }) { Text("Change mode") }
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
                }
                if (isDangerMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmDanger, onCheckedChange = { confirmDanger = it })
                        Text(
                            "I confirm this dangerous command for this run only. " +
                                "The host must also have danger-full-access enabled.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = output,
                    onValueChange = { },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = { Text("Output") },
                    readOnly = true,
                    minLines = 12,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Command") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filter { c -> c.isDigit() }.take(3) },
                        modifier = Modifier.weight(0.35f),
                        label = { Text("Timeout s") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { run() },
                        enabled = !running && command.isNotBlank() && (!isDangerMode || confirmDanger),
                    ) { Text(if (running) "Running…" else "Run") }
                    OutlinedButton(onClick = { cancelExec() }, enabled = running && activeExecId != null) {
                        Text("Cancel exec")
                    }
                }
            }
        }
    }
}
