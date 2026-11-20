package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository

@Composable
fun SettingsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val __darkTheme = remember { mutableStateOf(true) }
    var darkTheme: Boolean by __darkTheme
    val __clearText = remember { mutableStateOf(false) }
    var clearText: Boolean by __clearText

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Settings") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Appearance",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            androidx.compose.material3.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Dark theme")
                androidx.compose.material3.Switch(
                    checked = darkTheme,
                    onCheckedChange = { darkTheme = it },
                )
            }
            androidx.compose.material3.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Allow cleartext traffic")
                androidx.compose.material3.Switch(
                    checked = clearText,
                    onCheckedChange = { clearText = it },
                )
            }
            Text(
                text = "Workspace: ${repository.currentWorkspace()}",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Build: com.emrg.app",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
