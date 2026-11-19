package com.emrg.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add



import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.model.HostProfile
import com.emrg.app.ui.components.EmptyContent
import com.emrg.app.ui.components.HostListItem
import com.emrg.app.ui.components.LoadingContent
import kotlinx.coroutines.launch
import androidx.compose.runtime.*

/** Start the local daemon and gateway inside Termux without the user
 * typing anything, via Termux's RUN_COMMAND intent. Both commands are
 * idempotent: `emrg server restart` starts the daemon if it is down and
 * verifies the port before it reports success, and a second
 * `emrg-gateway serve` just fails to bind the port if one is already
 * running. They run in background Termux sessions, so the user stays in
 * this app while the services come up; the client's own reconnect loop
 * then attaches on its own.
 *
 * Two Termux-side gates, both one-time:
 * - RUN_COMMAND is a *broadcast* intent (a receiver handles it, not an
 *   activity — sending it with startActivity throws
 *   ActivityNotFoundException), so this uses sendBroadcast.
 * - `com.termux.permission.RUN_COMMAND` is a *dangerous* permission:
 *   declaring it in the manifest is not enough, the user must grant it
 *   at runtime — the caller requests it first and only sends when it
 *   is held.
 * - Termux also refuses external commands unless
 *   `allow-external-apps=true` is set in `~/.termux/termux.properties`
 *   (one line, run once in Termux). Without it the broadcast is
 *   silently dropped: the button reports "sent" but the host stays
 *   Offline, which is the signal to run that line. */
private fun startLocalEmrg(context: Context): Boolean {
    val home = "/data/data/com.termux/files/home"
    val venvBin = "$home/emrg/.venv/bin"
    val commands = listOf(
        "$venvBin/emrg server restart",
        "$venvBin/emrg-gateway serve",
    )
    return try {
        for (command in commands) {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setPackage("com.termux")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.sendBroadcast(intent)
        }
        true
    } catch (e: Exception) {
        false
    }
}

private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

@Composable
fun HostsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    val hosts by repository.hosts.collectAsState()
    val state by repository.state.collectAsState()
    val activeHost by repository.activeHost.collectAsState()
    var connectError by remember { mutableStateOf<String?>(null) }

    val lastHost = hosts.maxByOrNull { it.lastUsed }
    val connectTo: (HostProfile) -> Unit = { target ->
        connectError = null
        scope.launch {
            try {
                // Tapping a host must open the socket first; without
                // this the Sessions screen had no workspace at all.
                repository.connect(target)
                navController.navigate("sessions")
            } catch (e: GatewayRequestException) {
                connectError = e.message
            }
        }
    }

    val connected = state is com.emrg.app.net.LinkState.Online

    // A failed tap leaves its error on screen even after the client's own
    // reconnect loop brings the socket back — the user then sees a red
    // "client closing" above a host card that already says Online.
    // Clear it as soon as the link is up again.
    LaunchedEffect(state) {
        if (state is com.emrg.app.net.LinkState.Online) connectError = null
    }

    // Seamless connect: opening the app used to leave the user staring
    // at the host list until they tapped a host, and a dead gateway
    // meant a trip to Termux first. Connect to the last-used host on
    // launch instead. The client's reconnect loop keeps retrying while
    // the link is wanted, so if the gateway is down the offline banner
    // shows and the Start button below can revive it — the user never
    // types a Termux command. Only one attempt per screen entry; a
    // missing token still fails loudly ("pair it again") instead of
    // looping.
    var triedAutoConnect by remember { mutableStateOf(false) }
    LaunchedEffect(hosts) {
        if (!triedAutoConnect && hosts.isNotEmpty() &&
            state is com.emrg.app.net.LinkState.Idle
        ) {
            triedAutoConnect = true
            lastHost?.let { connectTo(it) }
        }
    }

    val context = LocalContext.current
    var startMessage by remember { mutableStateOf<String?>(null) }

    // RUN_COMMAND is a dangerous permission: it must be granted at
    // runtime, not just declared. Ask on first tap; once granted, the
    // button sends the start commands right away.
    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        startMessage = if (granted) {
            if (startLocalEmrg(context)) {
                "Đã gửi lệnh khởi động tới Termux — chờ vài giây để app tự nối lại."
            } else {
                "Không gửi được lệnh tới Termux — mở Termux kiểm tra."
            }
        } else {
            "Chưa được cấp quyền chạy lệnh trong Termux — bật ở Cài đặt → Ứng dụng → EMRG → Quyền → Quyền bổ sung → 'Run commands in Termux environment'."
        }
    }

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
            connectError?.let { e ->
                Text(
                    text = e,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            // Feature 1 — local mode: EMRG on this phone (Termux). Remote hosts
            // below are feature 2 and are unchanged.
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "Run EMRG on this phone",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Local mode runs the daemon and gateway on this phone in Termux — no PC needed.",
                )
                // Settings has no other entry point: there is no drawer
                // or bottom bar, and no screen linked to it, so the
                // danger switch and LLM settings were unreachable.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { navController.navigate("local") }) {
                        Text(text = "Local setup")
                    }
                    Button(onClick = { navController.navigate("settings") }) {
                        Text(text = "Settings")
                    }
                }
                Text(
                    text = "Remote hosts",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Offline used to replace the whole list with a full-screen
            // error whose Retry button did nothing, so after a failed
            // connect the user could not see or tap any host at all. The
            // error is now a banner above the list, and Retry reconnects
            // the last-used host. The client already retries the socket on
            // its own — but a dead gateway stays dead until someone
            // restarts it in Termux, so the banner says exactly that.
            if (state is com.emrg.app.net.LinkState.Offline && hosts.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (state as com.emrg.app.net.LinkState.Offline).reason,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            val target = activeHost ?: lastHost ?: return@Button
                            connectTo(target)
                        }) {
                            Text(text = "Retry")
                        }
                    }
                    Text(
                        text = "Gateway đã ngắt. Mở Termux, chạy lại `emrg-gateway serve` — app sẽ tự nối lại khi gateway chạy.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // One tap revives the local services instead of the
                    // Termux round-trip: the daemon and the gateway are
                    // started inside Termux by broadcast, and the
                    // reconnect loop attaches once the gateway answers.
                    // First tap asks for the dangerous RUN_COMMAND
                    // permission; Termux also needs
                    // allow-external-apps=true in ~/.termux/
                    // termux.properties (one line, once) or it drops
                    // the broadcast silently.
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                TERMUX_RUN_COMMAND_PERMISSION,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startMessage = if (startLocalEmrg(context)) {
                                "Đã gửi lệnh khởi động tới Termux — chờ vài giây để app tự nối lại."
                            } else {
                                "Không gửi được lệnh tới Termux — mở Termux kiểm tra."
                            }
                        } else {
                            termuxPermissionLauncher.launch(TERMUX_RUN_COMMAND_PERMISSION)
                        }
                    }) {
                        Text(text = "Khởi động EMRG")
                    }
                    startMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (hosts.isEmpty()) {
                EmptyContent(message = "No paired hosts yet. Add one to connect.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = padding,
                ) {
                    items(hosts, key = { it.id }) { host ->
                        HostListItem(
                            host = host,
                            connected = connected,
                            onClick = { connectTo(host) },
                        )
                    }
                }
            }
        }
    }
}
