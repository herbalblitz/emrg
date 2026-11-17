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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
) {
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<com.emrg.app.model.MemoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            try {
                memories = repository.memories(scope = "agent", sessionId = "", cwd = repository.currentWorkspace())
            } catch (e: GatewayRequestException) {
                error = e.message
            }
            loading = false
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Memory") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            Button(onClick = refresh) { Text(text = "Refresh") }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(memories, key = { it.id }) { m ->
                    androidx.compose.material3.Text(text = m.title)
                }
            }
        }
    }
}

@Composable
fun ModelsScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<com.emrg.app.model.ModelInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    scope.launch {
        try {
            val (ms, cur) = repository.models()
            models = ms
            current = cur
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Models") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            androidx.compose.material3.Text(text = "Current: $current", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(models, key = { it.name }) { m ->
                    androidx.compose.material3.Text(
                        text = "${m.name} (${m.contextWindow})${if (m.vision) " • vision" else ""}",
                    )
                }
            }
        }
    }
}

@Composable
fun RantScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val m = message.trim()
        if (m.isEmpty()) return
        submitting = true
        error = null
        scope.launch {
            try {
                result = repository.submitRant(message = m, agent = agent.trim())
                message = ""
            } catch (e: GatewayRequestException) {
                error = e.message
            }
            submitting = false
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Rant / feedback") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            TextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(text = "Feedback") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
            )
            TextField(
                value = agent,
                onValueChange = { agent = it },
                label = { Text(text = "Target agent (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = submit, enabled = !submitting) {
                Text(text = if (submitting) "Sending…" else "Send rant")
            }
            result?.let { r -> androidx.compose.material3.Text(text = "Recorded ($r total)") }
        }
    }
}
