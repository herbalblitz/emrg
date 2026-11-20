package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    path: String = "",
    sessionId: String = "",
) {
    val scope = rememberCoroutineScope()
    val __content = remember { mutableStateOf("") }
    var content: String
        get() = __content.value
        set(value) { __content.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }
    val __saving = remember { mutableStateOf(false) }
    var saving: Boolean
        get() = __saving.value
        set(value) { __saving.value = value }

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(text = "Editor") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "Navigate up",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            TextField(
                value = content,
                onValueChange = { content = it },
                label = { androidx.compose.material3.Text(text = path) },
                modifier = Modifier.weight(1f).fillMaxSize(),
                singleLine = false,
                maxLines = 20,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                Button(
                    onClick = {
                        error = null
                        saving = true
                        scope.launch {
                            try {
                                repository.writeFile(
                                    path = path,
                                    content = content,
                                    cwd = repository.currentWorkspace(),
                                    sessionId = sessionId,
                                )
                                navController.popBackStack()
                            } catch (e: GatewayRequestException) {
                                error = e.message
                            }
                            saving = false
                        }
                    },
                    enabled = !saving,
                ) {
                    androidx.compose.material3.Text(
                        text = if (saving) "Saving\u2026" else "Save",
                    )
                }
            }
        }
    }
}
