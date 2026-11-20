package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.userErrorMessage
import ai.emrg.companion.ui.navigation.Route
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

internal data class FileEntry(val name: String, val path: String, val type: String)

/**
 * The `files` route.
 *
 * Lists and reads files via `files.list` / `files.read` (files.read scope).
 * Delete sends `files.delete` (files.write scope) after an explicit confirm
 * tap. All paths are workspace-relative: the server joins them to the bound
 * cwd, and this screen never offers an absolute path or a `..` escape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesScreen(
    connection: CompanionConnection,
    navController: NavHostController,
    path: String = "",
) {
    val scope = rememberCoroutineScope()
    var current by remember(path) { mutableStateOf(path.ifBlank { "." }) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val listPath = current
            val frame = connection.request(requestId) { connection.sendFilesList(requestId, listPath) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or files.read not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val rows = frame.resultObjects("entries", "files", "items")
                    entries = if (rows.isNotEmpty()) {
                        rows.mapNotNull { row ->
                            val name = row.optNonBlank("name") ?: row.optNonBlank("path") ?: return@mapNotNull null
                            FileEntry(
                                name = name.substringAfterLast("/"),
                                path = row.optNonBlank("path") ?: name,
                                type = row.optNonBlank("type").orEmpty().ifEmpty { "file" },
                            )
                        }
                    } else {
                        // Some list results use bare path strings.
                        frame.resultStrings("entries", "files", "items").map { p ->
                            FileEntry(p.substringAfterLast("/"), p, if (p.endsWith("/")) "directory" else "file")
                        }
                    }
                }
            }
        }
    }

    fun delete(pathToDelete: String) {
        error = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendFilesDelete(requestId, pathToDelete) }
            when {
                frame == null -> error = "Could not reach the host (not connected, or files.write not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> pendingDelete = null
            }
            refresh()
        }
    }

    fun parentOf(p: String): String {
        val clean = p.trimEnd('/')
        if (clean.isEmpty() || clean == ".") return "."
        val parent = clean.substringBeforeLast("/", "")
        return parent.ifEmpty { "." }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Files") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.READ_FILES, navController) {
                ScreenNote("Paths are relative to the approved workspace. The server joins them to the bound workspace directory.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Workspace-relative path") },
                        singleLine = true,
                    )
                    Button(onClick = { refresh() }, enabled = !loading) { Text("List") }
                }
                if (current != ".") {
                    OutlinedButton(onClick = { current = parentOf(current); refresh() }) { Text("Up") }
                }
                ScreenError(error)
                if (entries.isEmpty() && !loading) {
                    Text("No entries. Tap List to load this directory.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    entry.name + if (entry.type == "directory") "  (directory)" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.clickable {
                                        if (entry.type == "directory") {
                                            current = entry.path
                                            refresh()
                                        } else {
                                            navController.navigate(Route.editor(entry.path))
                                        }
                                    },
                                )
                                Text(entry.path, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (entry.type == "directory") {
                                        OutlinedButton(onClick = { current = entry.path; refresh() }) { Text("Open") }
                                    } else {
                                        Button(onClick = { navController.navigate(Route.editor(entry.path)) }) { Text("Edit") }
                                    }
                                    if (connection.hasScope(PairingScope.WRITE_FILES) && entry.type != "directory") {
                                        if (pendingDelete == entry.path) {
                                            Button(onClick = { delete(entry.path) }) { Text("Confirm delete") }
                                            OutlinedButton(onClick = { pendingDelete = null }) { Text("Keep") }
                                        } else {
                                            OutlinedButton(onClick = { pendingDelete = entry.path }) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
