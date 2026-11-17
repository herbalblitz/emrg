package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import kotlinx.coroutines.launch

@Composable
fun PairScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var hostText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("56032") }
    var pinText by remember { mutableStateOf("") }
    var codeText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Pair host") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            TextField(
                value = hostText,
                onValueChange = { hostText = it },
                label = { Text(text = "Host") },
                placeholder = { Text(text = "192.168.1.42") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text(text = "Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = pinText,
                onValueChange = { pinText = it },
                label = { Text(text = "Certificate fingerprint (compare on host)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = codeText,
                onValueChange = { codeText = it },
                label = { Text(text = "One-shot pairing code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text(text = "Device name") },
                placeholder = { Text(text = "Android device") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    error = null
                    pairing = true
                    scope.launch {
                        try {
                            val port = portText.toIntOrNull() ?: 56032
                            repository.pair(
                                host = hostText,
                                port = port,
                                pin = pinText,
                                code = codeText,
                                deviceName = nameText,
                            )
                            navController.navigate("sessions")
                        } catch (e: GatewayRequestException) {
                            error = e.message
                        }
                        pairing = false
                    }
                },
                enabled = !pairing,
            ) {
                Text(text = if (pairing) "Pairing…" else "Pair")
            }
        }
    }
}
