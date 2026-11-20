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
import androidx.compose.material3.Button
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
import org.json.JSONObject
import java.util.UUID

/**
 * The `settings` route.
 *
 * Reads the host settings snapshot via `settings.get` and applies a typed
 * patch via `settings.set` (settings.manage scope). Only the allowlisted
 * engine keys are exposed: the model and max tokens. The base URL is shown
 * by presence only — never its value, never editable here. There is no
 * API-key field and no danger toggle on this screen, ever; the server
 * rejects any patch key outside its allowlist. Every save needs an explicit
 * confirm tap, and only keys that actually changed are sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var modelText by remember { mutableStateOf("") }
    var maxTokensText by remember { mutableStateOf("") }
    var loadedModel by remember { mutableStateOf<String?>(null) }
    var loadedMaxTokens by remember { mutableStateOf<Int?>(null) }
    var baseUrlPresent by remember { mutableStateOf<Boolean?>(null) }
    var editableKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf(false) }

    /** The patch key the host's own `editable` list names, dotted `llm.*` by default. */
    fun patchKey(dotted: String, flat: String): String = when {
        editableKeys.contains(dotted) -> dotted
        editableKeys.contains(flat) -> flat
        else -> dotted
    }

    fun optIntOrNull(obj: JSONObject, key: String): Int? =
        if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null

    fun refresh() {
        error = null
        result = null
        pendingSave = false
        loading = true
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendSettingsGet(requestId) }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or settings.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        // The snapshot nests engine keys under `settings` or
                        // `engine` (or `llm`); accept a flat top level too.
                        val settingsObj = frame.raw.optJSONObject("settings")
                            ?: frame.raw.optJSONObject("engine")
                            ?: frame.raw.optJSONObject("llm")
                            ?: frame.raw
                        val model = settingsObj.optNonBlank("model")
                            ?: settingsObj.optNonBlank("llm.model")
                        val maxTokens = optIntOrNull(settingsObj, "max_tokens")
                            ?: optIntOrNull(settingsObj, "llm.max_tokens")
                        val baseUrl = settingsObj.optNonBlank("base_url")
                            ?: settingsObj.optNonBlank("llm.base_url")
                        loadedModel = model
                        loadedMaxTokens = maxTokens
                        modelText = model.orEmpty()
                        maxTokensText = maxTokens?.toString().orEmpty()
                        baseUrlPresent = baseUrl != null
                        val editableArray = frame.raw.optJSONArray("editable")
                            ?: settingsObj.optJSONArray("editable")
                        editableKeys = editableArray?.let { array ->
                            (0 until array.length()).mapNotNull {
                                array.optString(it).takeIf { s -> s.isNotBlank() }
                            }
                        }.orEmpty()
                    }
                }
            }
        }
    }

    fun save() {
        pendingSave = false
        error = null
        result = null
        val patch = JSONObject()
        val newModel = modelText.trim()
        if (newModel != (loadedModel.orEmpty())) {
            if (newModel.isBlank()) {
                error = "Model must not be empty."
                return
            }
            patch.put(patchKey("llm.model", "model"), newModel)
        }
        val newMaxTokensText = maxTokensText.trim()
        if (newMaxTokensText != (loadedMaxTokens?.toString().orEmpty())) {
            val parsed = newMaxTokensText.toIntOrNull()
            if (parsed == null || parsed !in 1..2_000_000) {
                error = "Max tokens must be a whole number between 1 and 2,000,000."
                return
            }
            patch.put(patchKey("llm.max_tokens", "max_tokens"), parsed)
        }
        if (patch.length() == 0) {
            result = "Nothing changed."
            return
        }
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendSettingsSet(requestId, patch) }
            when {
                frame == null -> error = "Could not reach the host (not connected, or settings.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        result = "Settings saved." +
                            if (frame.optResultBoolean("restart_required") == true) {
                                " Restart the daemon to apply the model change."
                            } else ""
                        refresh()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_SETTINGS, navController) {
                ScreenNote(
                    "Host engine settings. Only the model and max tokens can be " +
                        "changed here — the API key and danger toggles are never shown " +
                        "or sent from this screen. A model change may need a daemon restart.",
                )
                Button(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "Loading…" else "Refresh")
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { maxTokensText = it },
                    label = { Text("Max tokens") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Base URL: " + when (baseUrlPresent) {
                        true -> "configured"
                        false -> "not configured"
                        null -> "unknown — tap Refresh to load"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (pendingSave) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { save() }) { Text("Confirm save") }
                        OutlinedButton(onClick = { pendingSave = false }) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = { pendingSave = true }) { Text("Save settings") }
                }
            }
        }
    }
}
