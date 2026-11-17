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
fun FilesScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    path: String = "/",
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(path) }
    var entries by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
    navController: androidx.navigation.NavHostController,
    path: String = "",
    sessionId: String = "",
) {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
