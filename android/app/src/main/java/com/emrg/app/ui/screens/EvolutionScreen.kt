package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun EvolutionScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __count = remember { mutableStateOf(0) }
    var count: Int = 0
        get() = __count.value
        set(value) { __count.value = value }
    val __records = remember { mutableStateOf(emptyList<com.emrg.app.model.EvolutionRecord>()) }
    var records: List<com.emrg.app.model.EvolutionRecord>
        get() = __records.value
        set(value) { __records.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }

    scope.launch {
        try {
            val (c, r) = repository.evolution()
            count = c
            records = r
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Evolution") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            androidx.compose.material3.Text(text = "Total cycles: $count", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(records, key = { it.timestamp }) { r ->
                    androidx.compose.material3.Text(
                        text = "${r.timestamp}  ${r.task}  (${r.toolCount} tools)",
                    )
                }
            }
        }
    }
}

@Composable
fun TasksScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __tasks = remember { mutableStateOf(emptyList<com.emrg.app.model.TaskInfo>()) }
    var tasks: List<com.emrg.app.model.TaskInfo>
        get() = __tasks.value
        set(value) { __tasks.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }
    val __taskName = remember { mutableStateOf("") }
    var taskName: String? = null
        get() = __taskName.value
        set(value) { __taskName.value = value }

    scope.launch {
        try {
            tasks = repository.tasks()
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    fun trigger() {
        val name = taskName.trim()
        if (name.isEmpty()) return
        scope.launch {
            try {
                repository.triggerTask(name)
            } catch (e: GatewayRequestException) {
                error = e.message
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Tasks") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            TextField(
                value = taskName,
                onValueChange = { taskName = it },
                label = { Text(text = "Task name to trigger") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { trigger() }) { Text(text = "Trigger") }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(tasks, key = { it.name }) { t ->
                    androidx.compose.material3.Text(
                        text = "${t.name}  [${if (t.enabled) "enabled" else "disabled"}]",
                    )
                }
            }
        }
    }
}

@Composable
fun SkillsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __skills = remember { mutableStateOf(emptyList<com.emrg.app.model.SkillInfo>()) }
    var skills: List<com.emrg.app.model.SkillInfo>
        get() = __skills.value
        set(value) { __skills.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? = null
        get() = __error.value
        set(value) { __error.value = value }

    scope.launch {
        try {
            skills = repository.skills()
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Skills") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(skills, key = { it.name }) { s ->
                    androidx.compose.material3.Text(
                        text = "${s.name} \u2014 ${s.description}",
                    )
                }
            }
        }
    }
}
