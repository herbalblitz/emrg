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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __memories = remember { mutableStateOf(emptyList<com.emrg.app.model.MemoryEntry>()) }
    var memories: List<com.emrg.app.model.MemoryEntry>
        get() = __memories.value
        set(value) { __memories.value = value }
    val __loading = remember { mutableStateOf(false) }
    var loading: Boolean = false
        get() = __loading.value
        set(value) { __loading.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }

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
            Button(onClick = { refresh() }) { Text(text = "Refresh") }
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
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __current = remember { mutableStateOf("") }
    var current: String? = null
        get() = __current.value
        set(value) { __current.value = value }
    val __models = remember { mutableStateOf(emptyList<com.emrg.app.model.ModelInfo>()) }
    var models: List<com.emrg.app.model.ModelInfo>
        get() = __models.value
        set(value) { __models.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }

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
                        text = "${m.name} (${m.contextWindow})${if (m.vision) " \u2022 vision" else ""}",
                    )
                }
            }
        }
    }
}

@Composable
fun RantScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __message = remember { mutableStateOf("") }
    var message: String? = null
        get() = __message.value
        set(value) { __message.value = value }
    val __agent = remember { mutableStateOf("") }
    var agent: String? = null
        get() = __agent.value
        set(value) { __agent.value = value }
    val __submitting = remember { mutableStateOf(false) }
    var submitting: Boolean = false
        get() = __submitting.value
        set(value) { __submitting.value = value }
    val __result = remember { mutableStateOf<Int?>(null) }
    var result: Int?
        get() = __result.value
        set(value) { __result.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }

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
            Button(onClick = { submit() }, enabled = !submitting) {
                Text(text = if (submitting) "Sending\u2026" else "Send rant")
            }
            result?.let { r -> androidx.compose.material3.Text(text = "Recorded ($r total)") }
        }
    }
}
