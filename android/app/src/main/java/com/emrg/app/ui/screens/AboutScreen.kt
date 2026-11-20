package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.emrg.app.BuildConfig

@Composable
fun AboutScreen(
    navController: androidx.navigation.NavHostController,
) {
    androidx.compose.material3.Scaffold(
        topBar = { androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text(text = "About") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .horizontalAlignment(androidx.compose.ui.Alignment.CenterHorizontally),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            androidx.compose.material3.Text(
                text = "EMRG",
                style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
            )
            androidx.compose.material3.Text(
                text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.Text(
                text = "Android companion for the EMRG ecosystem",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.Text(
                text = "Connects to a host gateway over TLS. No Python runs on-device.",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
