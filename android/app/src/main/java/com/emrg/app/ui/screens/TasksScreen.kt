package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.emrg.app.data.GatewayRepository
import com.emrg.app.model.TaskInfo
import kotlinx.coroutines.launch

@Composable
fun TasksScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __tasks = remember { mutableStateOf(emptyList<TaskInfo>()) }
    var tasks by __tasks
    val __loading = remember { mutableStateOf(true) }
    var loading by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error by __error

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                tasks = repository.tasks()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Tasks") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (!loading && error == null) {
                Text(
                    text = "Scheduled tasks (${tasks.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(tasks, key = { it.name }) { task ->
                        androidx.compose.material3.Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = task.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = task.prompt.take(60),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text =
                                        "interval=${task.interval} enabled=${task.enabled} sandbox=${task.sandbox}",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                )
                            }
                            Switch(
                                checked = task.enabled,
                                onCheckedChange = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
