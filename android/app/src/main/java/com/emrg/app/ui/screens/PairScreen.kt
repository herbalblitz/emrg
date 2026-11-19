package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar



import androidx.compose.ui.Modifier
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.net.int
import com.emrg.app.net.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth

/** One pairing payload as printed by `emrg-gateway pair` (JSON, type
 * "emrg-pair") and encoded in its QR code. */
private data class PairPayload(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val code: String,
)

private fun parsePairPayload(raw: String): PairPayload? {
    return try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        if (obj.str("type") != "emrg-pair") return null
        val host = obj.str("host") ?: return null
        val fingerprint = obj.str("fingerprint") ?: return null
        val code = obj.str("code") ?: return null
        PairPayload(host, obj.int("port") ?: 56032, fingerprint, code)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun PairScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
    host: String = "",
    title: String = "Pair host",
) {
    val scope = rememberCoroutineScope()
    // Re-pairing the same host keeps its certificate, so the fingerprint is
    // already known — prefill it and the user only types the fresh one-shot
    // code. A hand-typed 95-character fingerprint was the most likely reason
    // the first re-pair attempt failed.
    val hosts by repository.hosts.collectAsState()
    val savedPin = hosts.firstOrNull { it.host == host && it.port == 56032 }?.fingerprint ?: ""
    var hostText by remember { mutableStateOf(host) }
    var portText by remember { mutableStateOf("56032") }
    var pinText by remember { mutableStateOf(savedPin) }
    var codeText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }

    // A QR scan from QrScanScreen lands here through the saved state
    // handle; prefill the fields from the gateway's pairing payload
    // instead of typing the 64-hex fingerprint by hand. The fields stay
    // editable — the QR's host is the gateway's guess, so a local pair
    // can be changed back to 127.0.0.1.
    val qrPair by navController.currentBackStackEntry?.savedStateHandle
        ?.getStateFlow<String?>("qr_pair", null)?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    LaunchedEffect(qrPair) {
        if (!qrPair.isNullOrBlank()) {
            val parsed = parsePairPayload(qrPair!!)
            if (parsed != null) {
                hostText = parsed.host
                portText = parsed.port.toString()
                pinText = parsed.fingerprint
                codeText = parsed.code
            } else {
                error = "Mã QR không phải mã ghép nối EMRG."
            }
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("qr_pair")
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = title) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(
                    text = e,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = { navController.navigate("qr-scan") }) {
                Text(text = "Quét mã QR")
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
                            val profile = repository.pair(
                                host = hostText,
                                port = port,
                                pin = pinText,
                                code = codeText,
                                deviceName = nameText,
                            )
                            // Pairing alone stores the token; the socket is
                            // only opened here. Without this connect the
                            // Sessions screen had no workspace and no way
                            // to pick one.
                            repository.connect(profile)
                            navController.navigate("sessions")
                        } catch (e: GatewayRequestException) {
                            error = e.message
                        } catch (e: Exception) {
                            // Safety net: no pairing failure may ever escape
                            // this coroutine and crash the app again.
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.message ?: e.javaClass.simpleName
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
