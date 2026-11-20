package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.userErrorMessage
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

internal data class RemoteModel(
    val name: String,
    val contextWindow: Int,
    val vision: Boolean,
)

/**
 * The `models` route.
 *
 * Lists models via `models.list` and switches via `models.set`
 * (models.manage scope). The model is global engine state: switching it
 * changes the model for every client of this host, not just this device —
 * the screen says so and every switch needs an explicit confirm tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelsScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingSet by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendModelsList(requestId) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or models.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        current = frame.optResultString("current").orEmpty()
                        models = frame.resultObjects("entries", "models").mapNotNull { row ->
                            val name = row.optNonBlank("name") ?: row.optNonBlank("model")
                                ?: return@mapNotNull null
                            RemoteModel(
                                name = name,
                                contextWindow = row.optInt("context_window"),
                                vision = row.optBoolean("vision"),
                            )
                        }
                    }
                }
            }
        }
    }

    fun setModel(name: String) {
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 120_000) {
                connection.sendModelsSet(requestId, name)
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or models.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        val now = frame.optResultString("model") ?: name
                        val previous = frame.optResultString("previous")
                        current = now
                        result = "Model switched to $now" +
                            if (!previous.isNullOrBlank()) " (was $previous)" else ""
                        pendingSet = null
                    }
                }
            }
            refresh()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Models") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_MODELS, navController) {
                ScreenNote(
                    "Warning: the model is global engine state. Switching it changes " +
                        "the model for ALL clients of this host, not just this device.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (current.isNotBlank()) {
                    Text("Current model: $current", style = MaterialTheme.typography.titleSmall)
                }
                if (models.isEmpty() && !loading) {
                    Text("No models listed yet. Tap Refresh to load them.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(models, key = { it.name }) { model ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(model.name, style = MaterialTheme.typography.titleSmall)
                                val meta = buildString {
                                    if (model.contextWindow > 0) append("${model.contextWindow} ctx")
                                    if (model.vision) {
                                        if (isNotEmpty()) append(" · ")
                                        append("vision")
                                    }
                                }
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                                if (model.name == current) {
                                    Text("Current", style = MaterialTheme.typography.bodySmall)
                                } else if (pendingSet == model.name) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { setModel(model.name) }) { Text("Confirm switch") }
                                        OutlinedButton(onClick = { pendingSet = null }) { Text("Cancel") }
                                    }
                                } else {
                                    Button(onClick = { pendingSet = model.name }) { Text("Switch to this model") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
