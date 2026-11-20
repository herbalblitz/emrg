package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.model.RantEntry
import kotlinx.coroutines.launch

@Composable
fun RantScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __rants = remember { mutableStateOf(emptyList<RantEntry>()) }
    var rants: List<RantEntry> by __rants
    val __loading = remember { mutableStateOf(true) }
    var loading: Boolean by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? by __error
    val __input = remember { mutableStateOf("") }
    var input: String by __input
    val __agent = remember { mutableStateOf("") }
    var agent: String by __agent
    val __submitting = remember { mutableStateOf(false) }
    var submitting: Boolean by __submitting

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                rants = repository.rants()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Rant") }) },
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
                    text = "Rants (${rants.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(0.6f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(rants, key = { it.timestamp }) { rant ->
                        androidx.compose.material3.Text(
                            text = "[${rant.timestamp}] ${rant.message}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            androidx.compose.material3.Divider()
            TextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(text = "Message") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
            )
            TextField(
                value = agent,
                onValueChange = { agent = it },
                label = { Text(text = "Agent (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    error = null
                    submitting = true
                    scope.launch {
                        try {
                            repository.submitRant(message = input, agent = agent)
                            input = ""
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting && input.isNotBlank(),
            ) {
                Text(text = if (submitting) "Sending\u2026" else "Submit Rant")
            }
        }
    }
}
