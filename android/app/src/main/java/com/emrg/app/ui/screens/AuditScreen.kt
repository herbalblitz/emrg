package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.model.AuditEntry
import kotlinx.coroutines.launch

@Composable
fun AuditScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val __entries = remember { mutableStateOf(emptyList<AuditEntry>()) }
    var entries: List<AuditEntry> by __entries
    val __loading = remember { mutableStateOf(true) }
    var loading: Boolean by __loading
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String? by __error

    LaunchedEffect(Unit) {
        scope.launch {
            loading = true
            try {
                entries = repository.audit()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Audit") }) },
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
                    text = "Audit log (${entries.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(entries, key = { it.ts }) { entry ->
                        androidx.compose.material3.Text(
                            text =
                                "[${entry.ts}] ${entry.device} | ${entry.action} | ${entry.decision}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        )
                        if (entry.detail.isNotBlank()) {
                            androidx.compose.material3.Text(
                                text = entry.detail,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
