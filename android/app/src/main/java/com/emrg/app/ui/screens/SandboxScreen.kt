package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar




import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun SandboxScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    sessionId: String = "",
    cwd: String = "",
) {
    val scope = rememberCoroutineScope()
    val effectiveCwd = cwd.takeIf { it.isNotEmpty() } ?: repository.currentWorkspace()
    var mode by remember { mutableStateOf("workspace-write") }
    var root by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply() {
        error = null
        scope.launch {
            try {
                repository.setSandbox(sessionId = sessionId, cwd = effectiveCwd, mode = mode)
                result = "Sandbox set to $mode"
            } catch (e: GatewayRequestException) {
                error = e.message
            }
        }
    }
    fun addRoot() {
        val r = root.trim()
        if (r.isEmpty()) return
        error = null
        scope.launch {
            try {
                repository.addSandboxRoot(sessionId = sessionId, cwd = effectiveCwd, path = r)
                result = "Added root $r"
                root = ""
            } catch (e: GatewayRequestException) {
                error = e.message
            }
        }
    }

    var expanded by remember { mutableStateOf(false) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Sandbox") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            result?.let { r ->
                androidx.compose.material3.Text(text = r, color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)
            }
            androidx.compose.material3.Text(text = "Mode", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            androidx.compose.material3.Button(onClick = { expanded = !expanded }) {
                Text(text = mode)
            }
            if (expanded) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (m in listOf("read-only", "workspace-write", "danger-full-access")) {
                        DropdownMenuItem(text = { Text(text = m) }, onClick = { mode = m; expanded = false })
                    }
                }
            }
            androidx.compose.material3.Button(onClick = { apply() }) { Text(text = "Apply mode") }
            TextField(value = root, onValueChange = { root = it }, label = { Text(text = "Writable root (absolute path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { addRoot() }) { Text(text = "Add root") }
        }
    }
}
