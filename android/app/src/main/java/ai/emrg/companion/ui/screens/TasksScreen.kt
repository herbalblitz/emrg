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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.util.UUID

internal data class RemoteTask(
    val name: String,
    val prompt: String,
    val interval: String,
    val enabled: Boolean,
    val sandbox: String,
    val type: String,
)

internal data class RemoteTaskTemplate(
    val name: String,
    val prompt: String,
)

/**
 * The `tasks` route.
 *
 * Lists, triggers, creates, updates and deletes scheduled tasks, and manages
 * task templates, via `tasks.*` frames (tasks.manage scope). Create, update,
 * delete and the template calls fail cleanly with "scheduler not running"
 * when the host scheduler is down — that error is shown as-is, not hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksScreen(connection: CompanionConnection, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<RemoteTask>>(emptyList()) }
    var templates by remember { mutableStateOf<List<RemoteTaskTemplate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var editInterval by remember { mutableStateOf("") }
    var editEnabled by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // Create-task form.
    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("") }
    var newAgent by remember { mutableStateOf("") }
    var newInterval by remember { mutableStateOf("") }
    // Template form.
    var templateName by remember { mutableStateOf("") }
    var templatePrompt by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<String?>(null) }
    var pendingTemplateDelete by remember { mutableStateOf<String?>(null) }

    /** One tasks.* call. Returns the result frame, or null when refused. */
    fun call(
        type: String,
        name: String? = null,
        prompt: String? = null,
        taskType: String? = null,
        agent: String? = null,
        interval: Int? = null,
        enabled: Boolean? = null,
        onDone: () -> Unit = {},
        onResult: (ProtocolFrame?) -> Unit = {},
    ) {
        error = null
        result = null
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val frame = connection.request(requestId, timeoutMs = 120_000) {
                connection.sendTasks(requestId, type, name, prompt, taskType, agent, interval, enabled)
            }
            when {
                frame == null -> error = "Could not reach the host (not connected, or tasks.manage not granted)."
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

    fun refreshTasks() {
        loading = true
        call("tasks.list", onDone = { loading = false }) { frame ->
            tasks = frame?.resultObjects("entries", "tasks").orEmpty().mapNotNull { row ->
                val name = row.optNonBlank("name") ?: return@mapNotNull null
                val intervalValue = row.opt("interval")
                val intervalText = when (intervalValue) {
                    is Number -> intervalValue.toString()
                    is String -> intervalValue
                    else -> row.optNonBlank("effective_interval").orEmpty()
                }
                RemoteTask(
                    name = name,
                    prompt = row.optNonBlank("prompt").orEmpty().ifEmpty {
                        row.optNonBlank("description").orEmpty()
                    },
                    interval = intervalText,
                    enabled = if (row.has("enabled") && !row.isNull("enabled")) row.optBoolean("enabled") else true,
                    sandbox = row.optNonBlank("sandbox").orEmpty(),
                    type = row.optNonBlank("type").orEmpty().ifEmpty {
                        row.optNonBlank("task_type").orEmpty()
                    },
                )
            }
        }
    }

    fun refreshTemplates() {
        call("tasks.templates") { frame ->
            templates = frame?.resultObjects("entries", "templates").orEmpty().mapNotNull { row ->
                val name = row.optNonBlank("name") ?: return@mapNotNull null
                RemoteTaskTemplate(name = name, prompt = row.optNonBlank("prompt").orEmpty())
            }
        }
    }

    fun refreshAll() {
        refreshTasks()
        refreshTemplates()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tasks") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScopeGate(connection, PairingScope.MANAGE_TASKS, navController) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refreshAll() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh") }
                }
                ScreenError(error)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Scheduled tasks (${tasks.size})", style = MaterialTheme.typography.titleSmall)
                if (tasks.isEmpty() && !loading) {
                    Text("No scheduled tasks. Create one below, or tap Refresh.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.name }) { task ->
                        val selected = selectedName == task.name
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(task.name, style = MaterialTheme.typography.titleSmall)
                                    Switch(checked = task.enabled, onCheckedChange = null)
                                }
                                if (task.prompt.isNotBlank()) Text(task.prompt, style = MaterialTheme.typography.bodySmall)
                                val meta = listOfNotNull(
                                    "interval=${task.interval}".takeIf { task.interval.isNotBlank() },
                                    "sandbox=${task.sandbox}".takeIf { task.sandbox.isNotBlank() },
                                    "type=${task.type}".takeIf { task.type.isNotBlank() },
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        call("tasks.trigger", name = task.name) { frame ->
                                            result = frame?.optResultString("detail")
                                                ?: frame?.optResultString("message")
                                                ?: "Triggered ${task.name}"
                                            refreshTasks()
                                        }
                                    }) { Text("Trigger") }
                                    OutlinedButton(onClick = {
                                        selectedName = if (selected) null else task.name
                                        editInterval = task.interval.filter { c -> c.isDigit() }
                                        editEnabled = task.enabled
                                        pendingDelete = null
                                    }) { Text(if (selected) "Close" else "Edit") }
                                    if (pendingDelete == task.name) {
                                        Button(onClick = {
                                            call("tasks.delete", name = task.name) {
                                                result = "Deleted ${task.name}"
                                                pendingDelete = null
                                                selectedName = null
                                                refreshTasks()
                                            }
                                        }) { Text("Confirm delete") }
                                        OutlinedButton(onClick = { pendingDelete = null }) { Text("Keep") }
                                    } else {
                                        OutlinedButton(onClick = { pendingDelete = task.name }) { Text("Delete") }
                                    }
                                }
                                if (selected) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = editInterval,
                                            onValueChange = { editInterval = it.filter { c -> c.isDigit() } },
                                            modifier = Modifier.weight(1f),
                                            label = { Text("Interval (seconds)") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                        Text("Enabled", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = editEnabled, onCheckedChange = { editEnabled = it })
                                    }
                                    Button(
                                        onClick = {
                                            call(
                                                "tasks.update",
                                                name = task.name,
                                                interval = editInterval.toIntOrNull(),
                                                enabled = editEnabled,
                                            ) {
                                                result = "Updated ${task.name}"
                                                selectedName = null
                                                refreshTasks()
                                            }
                                        },
                                    ) { Text("Save changes") }
                                }
                            }
                        }
                    }
                    item {
                        Text("Create a task", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Task name") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = newType,
                            onValueChange = { newType = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Task type") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = newAgent,
                            onValueChange = { newAgent = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Agent") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = newInterval,
                            onValueChange = { newInterval = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Interval (seconds, optional)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Button(
                            enabled = newName.isNotBlank() && newType.isNotBlank() && newAgent.isNotBlank(),
                            onClick = {
                                call(
                                    "tasks.create",
                                    name = newName.trim(),
                                    taskType = newType.trim(),
                                    agent = newAgent.trim(),
                                    interval = newInterval.toIntOrNull(),
                                ) {
                                    result = "Created ${newName.trim()}"
                                    newName = ""
                                    newType = ""
                                    newAgent = ""
                                    newInterval = ""
                                    refreshTasks()
                                }
                            },
                        ) { Text("Create task") }
                    }
                    item {
                        Text("Templates (${templates.size})", style = MaterialTheme.typography.titleSmall)
                    }
                    items(templates, key = { "tpl:${it.name}" }) { template ->
                        val selected = selectedTemplate == template.name
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(template.name, style = MaterialTheme.typography.titleSmall)
                                if (template.prompt.isNotBlank()) Text(template.prompt, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        selectedTemplate = if (selected) null else template.name
                                        templateName = template.name
                                        templatePrompt = template.prompt
                                        pendingTemplateDelete = null
                                    }) { Text(if (selected) "Close" else "Edit") }
                                    if (pendingTemplateDelete == template.name) {
                                        Button(onClick = {
                                            call("tasks.template_delete", name = template.name) {
                                                result = "Deleted template ${template.name}"
                                                pendingTemplateDelete = null
                                                selectedTemplate = null
                                                refreshTemplates()
                                            }
                                        }) { Text("Confirm delete") }
                                        OutlinedButton(onClick = { pendingTemplateDelete = null }) { Text("Keep") }
                                    } else {
                                        OutlinedButton(onClick = { pendingTemplateDelete = template.name }) { Text("Delete") }
                                    }
                                }
                                if (selected) {
                                    OutlinedTextField(
                                        value = templatePrompt,
                                        onValueChange = { templatePrompt = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Prompt") },
                                        minLines = 3,
                                        maxLines = 6,
                                    )
                                    Button(
                                        onClick = {
                                            call("tasks.template_update", name = template.name, prompt = templatePrompt) {
                                                result = "Updated template ${template.name}"
                                                selectedTemplate = null
                                                refreshTemplates()
                                            }
                                        },
                                    ) { Text("Save template") }
                                }
                            }
                        }
                    }
                    item {
                        Text("Create a template", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = templateName,
                            onValueChange = { templateName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Template name") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = templatePrompt,
                            onValueChange = { templatePrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Prompt") },
                            minLines = 3,
                            maxLines = 6,
                        )
                        Button(
                            enabled = templateName.isNotBlank() && templatePrompt.isNotBlank(),
                            onClick = {
                                call("tasks.template_create", name = templateName.trim(), prompt = templatePrompt) {
                                    result = "Created template ${templateName.trim()}"
                                    templateName = ""
                                    templatePrompt = ""
                                    refreshTemplates()
                                }
                            },
                        ) { Text("Create template") }
                    }
                }
            }
        }
    }
}
