package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __command = remember { mutableStateOf("") }
    var command: String
        get() = __command.value
        set(value) { __command.value = value }
    val __running = remember { mutableStateOf(false) }
    var running: Boolean
        get() = __running.value
        set(value) { __running.value = value }
    val __output = remember { mutableStateOf("") }
    var output: String
        get() = __output.value
        set(value) { __output.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String?
        get() = __error.value
        set(value) { __error.value = value }
    val __mode = remember { mutableStateOf("workspace-write") }
    var mode: String
        get() = __mode.value
        set(value) { __mode.value = value }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        running = true
        error = null
        scope.launch {
            try {
                val outcome = repository.exec(
                    command = cmd,
                    cwd = repository.currentWorkspace(),
                    sessionId = "",
                    mode = mode,
                    timeout = 60,
                )
                output += "\n$ cmd\n${outcome.stdout}${if (outcome.stderr.isNotEmpty()) "\n${outcome.stderr}" else ""}\n(exit ${outcome.exitCode})"
            } catch (e: GatewayRequestException) {
                error = e.message
            }
            running = false
        }
    }

    val __expanded = remember { mutableStateOf(false) }
    var expanded: Boolean
        get() = __expanded.value
        set(value) { __expanded.value = value }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Terminal") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            androidx.compose.material3.Text(
                text = "Sandbox mode: $mode",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Button(onClick = { expanded = !expanded }) {
                Text(text = "Change mode")
            }
            if (expanded) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(text = "read-only") }, onClick = { mode = "read-only"; expanded = false })
                    DropdownMenuItem(text = { Text(text = "workspace-write") }, onClick = { mode = "workspace-write"; expanded = false })
                    DropdownMenuItem(text = { Text(text = "danger-full-access") }, onClick = { mode = "danger-full-access"; expanded = false })
                }
            }
            androidx.compose.material3.TextField(
                value = output,
                onValueChange = { },
                label = { Text(text = "Output") },
                modifier = Modifier.weight(1f).fillMaxSize(),
                minLines = 12,
                maxLines = 40,
                readOnly = true,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(text = "Command") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    singleLine = true,
                    imeOptions = androidx.compose.ui.text.input.ImeAction.Send,
                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                )
                Button(onClick = { run() }, enabled = !running) {
                    Text(text = if (running) "Running\u2026" else "Run")
                }
            }
        }
    }
}
