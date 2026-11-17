package com.emrg.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.net.str
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    sessionId: String = "",
    cwd: String = "",
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effectiveCwd = cwd.takeIf { it.isNotBlank() } ?: repository.currentWorkspace()
    val frames by repository.frames
        .collectAsStateWithLifecycle(lifecycle)

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun sendPrompt() {
        val text = input.trim()
        if (text.isEmpty() || sessionId.isEmpty()) return
        input = ""
        error = null
        try {
            repository.sendPrompt(
                sessionId = sessionId,
                cwd = effectiveCwd,
                prompt = text,
            )
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Session") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = padding,
            ) {
                items(frames, key = { it.toString() }) { frame ->
                    val type = frame.str("type").orEmpty()
                    val content = frame.str("content").orEmpty()
                    androidx.compose.material3.Text(
                        text = if (content.isNotBlank()) content else "[$type]",
                    )
                }
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(text = "Message") },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    singleLine = true,
                    imeOptions = androidx.compose.ui.text.input.ImeAction.Send,
                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                )
                IconButton(onClick = sendPrompt) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
