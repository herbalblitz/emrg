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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
    path: String = "/",
) {
    val scope = rememberCoroutineScope()
    val __current = remember { mutableStateOf(path) }
    var current: String
        get() = __current.value
        set(value) { __current.value = value }
    val __entries = remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var entries: String
        get() = __entries.value
        set(value) { __entries.value = value }
    val __loading = remember { mutableStateOf(false) }
    var loading: Boolean
        get() = __loading.value
        set(value) { __loading.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String?
        get() = __error.value
        set(value) { __error.value = value }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            try {
                entries = repository.listFiles(current)
            } catch (e: GatewayRequestException) {
                error = e.message
            }
            loading = false
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Files") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            TextField(
                value = current,
                onValueChange = { current = it },
                label = { Text(text = "Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.material3.Button(onClick = { refresh() }) { Text(text = "Refresh") }
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(entries, key = { it.first }) { (name, path, type) ->
                    androidx.compose.material3.Text(
                        text = "$name  ($type)",
                    )
                }
            }
        }
    }
}

@Composable
fun EditorScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
    path: String = "",
    sessionId: String = "",
) {
    val scope = rememberCoroutineScope()
    val __content = remember { mutableStateOf("") }
    var content: String
        get() = __content.value
        set(value) { __content.value = value }
    val __saving = remember { mutableStateOf(false) }
    var saving: Boolean
        get() = __saving.value
        set(value) { __saving.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String?
        get() = __error.value
        set(value) { __error.value = value }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Editor") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            TextField(
                value = path,
                onValueChange = { },
                label = { Text(text = "Path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
            )
            androidx.compose.material3.TextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(text = "Content") },
                modifier = Modifier.weight(1f).fillMaxSize(),
                minLines = 20,
                maxLines = 40,
            )
            androidx.compose.material3.Button(
                onClick = {
                    saving = true
                    error = null
                    val session = sessionId.takeIf { it.isNotEmpty() }.orEmpty()
                    scope.launch {
                        try {
                            repository.writeFile(
                                path = path,
                                content = content,
                                cwd = repository.currentWorkspace(),
                                sessionId = session,
                            )
                        } catch (e: GatewayRequestException) {
                            error = e.message
                        }
                        saving = false
                    }
                },
                enabled = !saving,
            ) {
                Text(text = if (saving) "Saving…" else "Save")
            }
        }
    }
}
