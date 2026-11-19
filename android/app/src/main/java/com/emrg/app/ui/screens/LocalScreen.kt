package com.emrg.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private enum class LocalGatewayStatus {
    Checking,
    Running,
    NotRunning,
}

/** The one command that sets up a local host inside Termux (see android/local/). */
private const val LOCAL_SETUP_COMMAND =
    "curl -fsSL https://raw.githubusercontent.com/herbalblitz/emrg/feat/termux-local/android/local/setup-local.sh | bash"

/** Plain TCP probe: is anything listening on the local gateway port? */
private suspend fun isLocalGatewayUp(): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 56032), 2_000)
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun openTermux(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage("com.termux") ?: return false
    context.startActivity(intent)
    return true
}

/**
 * Local mode: EMRG runs on this phone itself.
 *
 * The daemon and the gateway run inside Termux (Python on localhost) and the
 * app pairs with them at 127.0.0.1:56032 exactly like a remote host. Remote
 * control is untouched: it still lives on the Hosts screen and the Pair
 * screen. This screen only adds the local path next to it.
 */
@Composable
fun LocalScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var status by remember { mutableStateOf(LocalGatewayStatus.Checking) }
    var termuxOpenFailed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    fun probe() {
        status = LocalGatewayStatus.Checking
        scope.launch {
            status = if (isLocalGatewayUp()) LocalGatewayStatus.Running else LocalGatewayStatus.NotRunning
        }
    }

    LaunchedEffect(Unit) { probe() }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Run EMRG locally") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = "Local mode runs the EMRG daemon and gateway on this phone itself, " +
                    "inside Termux — no PC needed. The app then pairs with it at " +
                    "127.0.0.1:56032, exactly like a remote host.",
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when (status) {
                    LocalGatewayStatus.Checking -> "Local gateway: checking…"
                    LocalGatewayStatus.Running -> "Local gateway: running on 127.0.0.1:56032."
                    LocalGatewayStatus.NotRunning -> "Local gateway: not running."
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (status == LocalGatewayStatus.Running) {
                Button(onClick = { navController.navigate("pair-local") }) {
                    Text(text = "Pair local host")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pairing still asks for the fingerprint and the one-shot code " +
                        "printed by emrg-gateway pair in Termux.",
                )
            } else if (status == LocalGatewayStatus.NotRunning) {
                Text(text = "To start it:")
                Text(text = "1. Install Termux from F-Droid (not the Play Store build).")
                Text(text = "2. In Termux, run the setup command below (once).")
                Text(text = "3. Start the daemon once: emrg — the first-run wizard sets your API key and model.")
                Text(text = "4. In a second Termux session, start the gateway: emrg-gateway serve (leave it running).")
                Text(text = "5. Get a pairing code: emrg-gateway pair, then tap Check again below.")
                Text(text = "One-time, for the app's one-tap 'Khởi động EMRG' button: in Termux run " +
                    "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings " +
                    "— without it Termux silently drops commands sent by other apps. The button also asks once for the " +
                    "'Run commands in Termux environment' permission.")
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = LOCAL_SETUP_COMMAND)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(LOCAL_SETUP_COMMAND))
                        copied = true
                    },
                ) {
                    Text(text = if (copied) "Copied" else "Copy setup command")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        termuxOpenFailed = !openTermux(context)
                    },
                ) {
                    Text(text = "Open Termux")
                }
                if (termuxOpenFailed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Could not open Termux from here — open it from your " +
                            "home screen instead. If it is not installed, get it from " +
                            "F-Droid: https://f-droid.org/en/packages/com.termux/",
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { probe() }) {
                Text(text = "Check again")
            }
        }
    }
}
