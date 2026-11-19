package com.emrg.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.unit.dp



import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.ui.components.EmptyContent
import com.emrg.app.ui.components.LoadingContent
import com.emrg.app.ui.components.SessionListItem
import kotlinx.coroutines.launch
import androidx.compose.runtime.*

@Composable
fun SessionsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val cwd by repository.workspace.collectAsState()
    var sessions by remember { mutableStateOf<List<com.emrg.app.model.SessionSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (cwd.isBlank()) {
        // No workspace yet: offer the daemon's registered folders, or let the
        // user type one by hand. Without this picker the screen was a dead
        // end: pairing never connected, so no workspace was ever selected.
        val workspaces by repository.workspaces.collectAsState()
        var manualPath by remember { mutableStateOf("") }
        var pickerLoading by remember { mutableStateOf(false) }
        var pickerError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (workspaces.isEmpty()) {
                pickerLoading = true
                try {
                    repository.loadWorkspaces()
                } catch (e: GatewayRequestException) {
                    pickerError = e.message
                }
                pickerLoading = false
            }
        }

        androidx.compose.material3.Scaffold(
            topBar = { TopAppBar(title = { Text(text = "Sessions") }) },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            ) {
                Text(text = "Select a workspace first")
                Text(
                    text = "A workspace is a folder on the host where EMRG keeps its " +
                        "sessions. On this phone, Termux home is " +
                        "/data/data/com.termux/files/home.",
                )
                pickerError?.let { e ->
                    androidx.compose.material3.Text(
                        text = e,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                }
                if (pickerLoading) {
                    LoadingContent(message = "Loading workspaces…")
                }
                if (workspaces.isEmpty() && !pickerLoading) {
                    Text(
                        text = "No workspaces found on the host yet. Start a session " +
                            "first (in Termux, or from the chat), or type a folder below.",
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(workspaces, key = { it.path }) { workspace ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    repository.selectWorkspace(workspace.path)
                                }.padding(8.dp),
                            ) {
                                Text(text = workspace.name)
                                Text(text = workspace.path)
                            }
                        }
                    }
                }
                TextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    label = { Text(text = "Folder on the host") },
                    placeholder = { Text(text = "/data/data/com.termux/files/home") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (manualPath.isNotBlank()) {
                            repository.selectWorkspace(manualPath.trim())
                        }
                    },
                ) {
                    Text(text = "Use this folder")
                }
                Button(
                    onClick = {
                        scope.launch {
                            pickerLoading = true
                            pickerError = null
                            try {
                                repository.loadWorkspaces()
                            } catch (e: GatewayRequestException) {
                                pickerError = e.message
                            }
                            pickerLoading = false
                        }
                    },
                ) {
                    Text(text = "Refresh")
                }
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
            Text(
                text = "Workspace: $cwd (change)",
                modifier = Modifier.clickable { repository.selectWorkspace("") }.padding(8.dp),
            )
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
                                navController.navigate("chat?session_id=${session.id}&cwd=${session.cwd}")
                            },
                        )
                    }
                }
            }
        }
    }
}
