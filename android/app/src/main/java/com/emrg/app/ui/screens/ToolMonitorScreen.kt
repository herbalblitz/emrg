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



import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

@Composable
fun ToolMonitorScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
                onClick = { run() },
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
