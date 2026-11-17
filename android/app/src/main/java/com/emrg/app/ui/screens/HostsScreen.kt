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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.colors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add



import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.emrg.app.data.GatewayRepository
import com.emrg.app.ui.components.EmptyContent
import com.emrg.app.ui.components.ErrorContent
import com.emrg.app.ui.components.HostListItem
import com.emrg.app.ui.components.LoadingContent
import kotlinx.coroutines.launch
import androidx.compose.runtime.*

@Composable
fun HostsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val hosts by repository.hosts.collectAsState()
    val state by repository.state.collectAsState()

    val connected = state is com.emrg.app.net.LinkState.Online

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Hosts") }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("pair") },
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add host",
                )
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is com.emrg.app.net.LinkState.Offline -> {
                    ErrorContent(
                        message = state.reason,
                        onRetry = {},
                    )
                }
                is com.emrg.app.net.LinkState.Idle,
                is com.emrg.app.net.LinkState.Connecting,
                is com.emrg.app.net.LinkState.Online -> {
                    if (hosts.isEmpty()) {
                        EmptyContent(message = "No paired hosts yet. Add one to connect.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = padding,
                        ) {
                            items(hosts, key = { it.id }) { host ->
                                HostListItem(
                                    host = host,
                                    connected = connected,
                                    onClick = { navController.navigate("sessions") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
