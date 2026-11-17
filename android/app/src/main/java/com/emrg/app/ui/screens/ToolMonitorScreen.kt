package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun ToolMonitorScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
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
    val __result = remember { mutableStateOf<String?>(null) }
    var result: String?
        get() = __result.value
        set(value) { __result.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String?
        get() = __error.value
        set(value) { __error.value = value }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        running = true
        result = null
        error = null
        scope.launch {
            try {
                val outcome = repository.exec(
                    command = cmd,
                    cwd = repository.currentWorkspace(),
                    sessionId = "",
                    mode = null,
                    timeout = 30,
                )
                result = "exit=${outcome.exitCode}\n${outcome.stdout}${if (outcome.stderr.isNotEmpty()) "\nSTDERR:\n${outcome.stderr}" else ""}"
            } catch (e: GatewayRequestException) {
                error = e.message
            }
            running = false
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Tool monitor") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(text = "Command") },
                placeholder = { Text(text = "ls -la") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.Button(
                onClick = run,
                enabled = !running,
            ) {
                Text(text = if (running) "Running…" else "Run")
            }
            if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
            result?.let { r ->
                androidx.compose.material3.Text(text = r, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
