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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberMutableStateOf
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun SandboxScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    sessionId: String = "",
    cwd: String = "",
) {
    val scope = rememberCoroutineScope()
    val effectiveCwd = cwd.takeIf { it.isNotEmpty() }.orElse { repository.currentWorkspace() }
    var mode by remember { rememberMutableStateOf("workspace-write") }
    var root by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply() {
        error = null
        scope.launch {
            try {
                repository.setSandbox(sessionId = sessionId, cwd = effectiveCwd, mode = mode.value)
                result = "Sandbox set to ${mode.value}"
            } catch (e: GatewayRequestException) {
                error = e.message
            }
        }
    }
    fun addRoot() {
        val r = root.value.trim()
        if (r.isEmpty()) return
        error = null
        scope.launch {
            try {
                repository.addSandboxRoot(sessionId = sessionId, cwd = effectiveCwd, path = r)
                result = "Added root $r"
                root.value = ""
            } catch (e: GatewayRequestException) {
                error = e.message
            }
        }
    }

    var expanded by remember { rememberMutableStateOf(false) }

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
            androidx.compose.material3.Button(onClick = { expanded.value = !expanded.value }) {
                Text(text = mode.value)
            }
            if (expanded.value) {
                DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                    for (m in listOf("read-only", "workspace-write", "danger-full-access")) {
                        DropdownMenuItem(text = { Text(text = m) }, onClick = { mode.value = m; expanded.value = false })
                    }
                }
            }
            androidx.compose.material3.Button(onClick = { apply() }) { Text(text = "Apply mode") }
            TextField(value = root.value, onValueChange = { root.value = it }, label = { Text(text = "Writable root (absolute path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { addRoot() }) { Text(text = "Add root") }
        }
    }
}
