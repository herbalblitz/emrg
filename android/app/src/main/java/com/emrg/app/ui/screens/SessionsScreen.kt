package com.emrg.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.ui.components.EmptyContent
import com.emrg.app.ui.components.LoadingContent
import com.emrg.app.ui.components.SessionListItem
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(
    repository: GatewayRepository,
    nav: com.emrg.app.AppNavigation,
) {
    val scope = rememberCoroutineScope()
    val cwd by repository.workspace.collectAsState()
    val __sessions = remember { mutableStateOf<List<com.emrg.app.model.SessionSummary>>(emptyList()) }
    var sessions: String
        get() = __sessions.value
        set(value) { __sessions.value = value }
    val __loading = remember { mutableStateOf(false) }
    var loading: Boolean
        get() = __loading.value
        set(value) { __loading.value = value }
    val __error = remember { mutableStateOf<String?>(null) }
    var error: String?
        get() = __error.value
        set(value) { __error.value = value }

    if (cwd.isBlank()) {
        androidx.compose.material3.Scaffold(
            topBar = { TopAppBar(title = { Text(text = "Sessions") }) },
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.foundation.layout.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(text = "Select a workspace first")
            }
        }
        return
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Sessions") },
                actions = {
                    IconButton(onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            sessions = repository.sessions(cwd = cwd)
                            loading = false
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        repository.newSessionId(cwd = cwd)
                        sessions = repository.sessions(cwd = cwd)
                        loading = false
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New session",
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (loading) LoadingContent(message = "Loading sessions…")
            if (sessions.isEmpty() && !loading) {
                EmptyContent(message = "No sessions in $cwd")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionListItem(
                            session = session,
                            onClick = {
                                nav.chatSessionId = session.id
                                nav.chatCwd = session.cwd
                                nav.goTo("chat")
                            },
                        )
                    }
                }
            }
        }
    }
}
