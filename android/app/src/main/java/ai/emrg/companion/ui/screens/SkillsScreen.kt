package ai.emrg.companion.ui.screens

import ai.emrg.companion.CompanionConnection
import ai.emrg.companion.PairingScope
import ai.emrg.companion.ProtocolFrame
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

internal data class RemoteSkill(
    val name: String,
    val description: String,
    val source: String,
    val path: String,
)

internal data class RemoteAvailableSkill(
    val name: String,
    val description: String,
    val installed: Boolean,
    val managed: Boolean,
)

/**
 * The `skills` route.
 *
 * Lists installed skills via `skills.list`, the installable catalog via
 * `skills.available`, installs one via `skills.install` and refreshes
 * managed skills via `skills.update` (skills.manage scope).
 *
 * Install and update fetch and install code on the host — the screen says so
 * and every install needs an explicit confirm tap. A host that answers an
 * install with `confirm_required` is shown the message and asked to confirm
 * on the host; this client never silently confirms a code install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillsScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<RemoteSkill>>(emptyList()) }
    var available by remember { mutableStateOf<List<RemoteAvailableSkill>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    /** One skills.* call. Returns the result frame, or null when refused. */
    fun call(
        type: String,
        name: String? = null,
        confirmed: Boolean? = null,
        onDone: () -> Unit = {},
        onResult: (ProtocolFrame?) -> Unit = {},
    ) {
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 120_000) {
                connection.sendSkills(requestId, type, name, confirmed)
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or skills.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) error = daemonError
                    else onResult(frame)
                }
            }
            onDone()
        }
    }

    fun refreshInstalled() {
        loading = true
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId) { connection.sendSkills(requestId, "skills.list") }
            loading = false
            when {
                frame == null -> error = "Could not reach the host (not connected, or skills.manage not granted)."
                frame.type == "error" -> error = frame.userErrorMessage()
                else -> {
                    val daemonError = frame.raw.optString("error").takeIf { it.isNotBlank() }
                    if (daemonError != null) {
                        error = daemonError
                    } else {
                        skills = frame.resultObjects("entries", "skills").mapNotNull { row ->
                            val name = row.optNonBlank("name") ?: return@mapNotNull null
                            RemoteSkill(
                                name = name,
                                description = row.optNonBlank("description").orEmpty(),
                                source = row.optNonBlank("source").orEmpty(),
                                path = row.optNonBlank("path").orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun refreshAvailable() {
        call("skills.available") { frame ->
            available = frame?.resultObjects("entries", "skills").orEmpty().mapNotNull { row ->
                val name = row.optNonBlank("name") ?: return@mapNotNull null
                RemoteAvailableSkill(
                    name = name,
                    description = row.optNonBlank("description").orEmpty(),
                    installed = row.optBoolean("installed"),
                    managed = row.optBoolean("managed"),
                )
            }
        }
    }

    fun refreshAll() {
        refreshInstalled()
        refreshAvailable()
    }

    fun install(name: String) {
        pendingInstall = null
        call("skills.install", name = name, confirmed = true) { frame ->
            val confirmRequired = frame?.optResultBoolean("confirm_required") == true
            if (confirmRequired) {
                result = "Install of $name needs host confirmation first (the host must confirm it on the host side)."
            } else {
                val version = frame?.optResultString("version")
                result = "Installed $name" + if (!version.isNullOrBlank()) " ($version)" else ""
            }
            refreshAll()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Skills") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_SKILLS, navController) {
                ScreenNote(
                    "Warning: install and update fetch and install code on the host. " +
                        "Only install skills you trust, and confirm each install explicitly.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refreshAll() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                    Button(
                        enabled = !updating,
                        onClick = {
                            updating = true
                            call("skills.update", onDone = { updating = false }) { frame ->
                                val updated = frame?.resultStrings("updated").orEmpty()
                                val checked = frame?.raw?.optInt("checked")
                                result = if (updated.isNotEmpty()) "Updated: ${updated.joinToString(", ")}"
                                else "Update checked ${checked ?: 0} managed skill(s); nothing changed."
                                refreshAll()
                            }
                        },
                    ) { Text(if (updating) "Updating…" else "Update managed skills") }
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Installed skills (${skills.size})", style = MaterialTheme.typography.titleSmall)
                if (skills.isEmpty() && !loading) {
                    Text("No skills installed yet.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(skills, key = { "installed:${it.name}" }) { skill ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                if (skill.description.isNotBlank()) Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                val meta = listOfNotNull(
                                    skill.source.takeIf { it.isNotBlank() }?.let { "source: $it" },
                                    skill.path.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Text("Available to install (${available.size})", style = MaterialTheme.typography.titleSmall)
                    }
                    items(available, key = { "available:${it.name}" }) { skill ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                if (skill.description.isNotBlank()) Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                val flags = listOfNotNull(
                                    "installed".takeIf { skill.installed },
                                    "managed".takeIf { skill.managed },
                                ).joinToString(" · ")
                                if (flags.isNotBlank()) Text(flags, style = MaterialTheme.typography.bodySmall)
                                if (!skill.installed) {
                                    if (pendingInstall == skill.name) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { install(skill.name) }) { Text("Confirm install") }
                                            OutlinedButton(onClick = { pendingInstall = null }) { Text("Cancel") }
                                        }
                                    } else {
                                        Button(onClick = { pendingInstall = skill.name }) { Text("Install") }
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
