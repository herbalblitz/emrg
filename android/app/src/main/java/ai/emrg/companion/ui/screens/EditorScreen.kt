package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The `editor` route. Shares the Files operation set, so it needs no new scope:
 * open reads via `files.read`, save writes via `files.write`. With only
 * files.read granted it opens read-only and says so. Save is a blind overwrite
 * of the file as last read (last-write-wins, matching the reference app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    connection: CompanionConnection,
    navController: NavHostController,
    path: String = "",
    sessionId: String = "",
) {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    val canWrite = connection.hasScope(PairingScope.WRITE_FILES)

    fun load() {
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 30_000) {
                connection.sendFilesRead(requestId, path)
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or files.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    content = frame.optResultString("content") ?: frame.raw.toString()
                    loaded = true
                }
            }
        }
    }

    LaunchedEffect(path) {
        if (path.isNotBlank()) load()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Editor") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_FILES, navController) {
                Text(path.ifBlank { "(no file selected)" }, style = MaterialTheme.typography.titleSmall)
                if (path.isBlank()) {
                    ScreenNote("Open a file from the Files screen to edit it here.")
                    return@ScopeGate
                }
                if (!canWrite) {
                    ScreenNote("Read-only: files.write was not granted, so this file cannot be saved from here.")
                }
                ScreenError(error)
                savedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = { Text(if (loaded) path else "Loading…") },
                    enabled = loaded && canWrite,
                    minLines = 20,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            saving = true
                            error = null
                            savedNote = null
                            scope.launch {
                                val requestId = UUID.randomUUID().toString()
                                val frame = connection.request(requestId, timeoutMs = 60_000) {
                                    connection.sendFilesWrite(requestId, path, content)
                                }
                                saving = false
                                when {
                                    frame == null -> error = "Could not reach the host (not connected, or files.write not granted)."
                                    frame.type == "error" -> error = frame.userErrorMessage()
                                    else -> savedNote = "Saved."
                                }
                            }
                        },
                        enabled = loaded && canWrite && !saving,
                    ) { Text(if (saving) "Saving…" else "Save") }
                    Button(onClick = { load() }, enabled = !saving) { Text("Reload") }
                }
            }
        }
    }
}
