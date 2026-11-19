package com.emrg.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar



import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emrg.app.data.GatewayRepository
import com.emrg.app.data.GatewayRequestException
import com.emrg.app.model.GatewaySnapshot
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row

@Composable
fun LogsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("daemon") }
    var lines by remember { mutableStateOf<com.emrg.app.model.LogPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    scope.launch {
        try {
            lines = repository.logs(source = source)
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Logs") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            androidx.compose.material3.Text(
                text = "Source: ${source.take(1).uppercase()}${source.drop(1)}",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Button(onClick = { /* tail logs */ }) {
                Text(text = "Tail")
            }
            lines?.let { page ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    items(page.lines, key = { it }) { line ->
                        androidx.compose.material3.Text(text = line, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun AuditScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<com.emrg.app.model.AuditEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    scope.launch {
        try {
            entries = repository.audit()
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Audit log") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(entries, key = { it.ts }) { e ->
                    androidx.compose.material3.Text(
                        text = "${e.ts}  ${e.device}  ${e.action}  [${e.decision}]",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<com.emrg.app.model.DeviceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    scope.launch {
        try {
            devices = repository.devices()
        } catch (e: GatewayRequestException) {
            error = e.message
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Devices") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let { e ->
                androidx.compose.material3.Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(devices, key = { it.id }) { d ->
                    androidx.compose.material3.Text(
                        text = "${d.name}  (${d.id})  ${if (d.connected) "online" else "offline"}",
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    repository: GatewayRepository,
    navController: androidx.navigation.NavHostController,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<GatewaySnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            snapshot = repository.gatewaySnapshot()
        } catch (e: GatewayRequestException) {
            loadError = e.message
        }
    }

    // ── Gateway: danger-full-access ──────────────────────────────
    // This switch used to be a dead local state: toggling it changed
    // nothing on the host. It now writes the real gateway option
    // (gw_config_set); the gateway reloads its config immediately, no
    // restart needed. Danger still needs a per-turn approval in chat —
    // the switch only permits it.
    var danger by remember { mutableStateOf(false) }
    var dangerBusy by remember { mutableStateOf(false) }
    var dangerError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot) { snapshot?.let { danger = it.allowDangerExec } }

    // ── LLM ──────────────────────────────────────────────────────
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf("") }
    var llmStatus by remember { mutableStateOf<String?>(null) }
    var llmBusy by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot) {
        snapshot?.let {
            baseUrl = it.baseUrl
            model = it.engineModel
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            loadError?.let { e ->
                Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            Text(text = "Gateway settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Switch(
                    checked = danger,
                    enabled = !dangerBusy,
                    onCheckedChange = { want ->
                        dangerBusy = true
                        dangerError = null
                        scope.launch {
                            try {
                                repository.saveGatewayConfig(
                                    mapOf("allow_danger_exec" to JsonPrimitive(want)),
                                )
                                danger = want
                            } catch (e: GatewayRequestException) {
                                dangerError = e.message
                            }
                            dangerBusy = false
                        }
                    },
                )
                Text(text = "Allow danger-full-access exec")
            }
            dangerError?.let { e ->
                Text(text = e, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            Text(
                text = "Cho phép agent chạy lệnh không có sandbox khi bạn duyệt từng lần trong chat. " +
                    "Trên điện thoại không có sandbox (bwrap) nên không bật thì agent không chạy được lệnh nào.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )

            Text(text = "LLM", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(
                text = "Key chỉ gửi tới gateway qua kết nối đã ghép nối và lưu vào file config trên host — không lưu trong app.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    baseUrl = "https://openrouter.ai/api/v1"
                    model = "openrouter/free"
                }) { Text(text = "OpenRouter") }
                Button(onClick = {
                    baseUrl = "https://api.groq.com/openai/v1"
                    model = "openai/gpt-oss-120b"
                }) { Text(text = "Groq") }
                Button(onClick = {
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                    model = "gemini-2.0-flash"
                }) { Text(text = "Gemini") }
            }
            TextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(text = "Base URL (OpenAI-compatible)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(text = "API key") },
                placeholder = {
                    Text(text = if (snapshot?.apiKeySet == true) "Đã đặt — để trống = giữ nguyên" else "Chưa đặt key")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(text = "Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter { ch -> ch.isDigit() } },
                label = { Text(text = "Max tokens (để trống = giữ nguyên)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Groq free: để 1024 (giới hạn 8.000 token/phút). OpenRouter/Gemini: 4096–8192.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    llmBusy = true
                    llmStatus = null
                    scope.launch {
                        try {
                            val patch = mutableMapOf<String, JsonElement>()
                            if (baseUrl.isNotBlank() && baseUrl.trim() != snapshot?.baseUrl) {
                                patch["llm.base_url"] = JsonPrimitive(baseUrl.trim())
                            }
                            if (apiKey.isNotBlank()) {
                                patch["llm.api_key"] = JsonPrimitive(apiKey.trim())
                            }
                            val modelChanged = model.isNotBlank() && model.trim() != snapshot?.engineModel
                            if (modelChanged) {
                                patch["llm.model"] = JsonPrimitive(model.trim())
                            }
                            val maxTok = maxTokens.trim().toIntOrNull()
                            if (maxTok != null) {
                                patch["llm.max_tokens"] = JsonPrimitive(maxTok)
                            }
                            if (patch.isEmpty()) {
                                llmStatus = "Không có gì để đổi."
                            } else {
                                repository.saveEngineConfig(patch)
                                if (modelChanged) {
                                    // The patch persists the model; /model
                                    // applies it now without a restart.
                                    try {
                                        repository.setModel(model.trim())
                                    } catch (e: GatewayRequestException) {
                                        llmStatus = "Đã lưu model vào file, nhưng đổi ngay thất bại (${e.message}) — chạy `emrg server restart` trong Termux."
                                        apiKey = ""
                                        maxTokens = ""
                                        snapshot = repository.gatewaySnapshot()
                                        llmBusy = false
                                        return@launch
                                    }
                                }
                                apiKey = ""
                                maxTokens = ""
                                snapshot = repository.gatewaySnapshot()
                                llmStatus = "Đã lưu — daemon nhận trong ~2 giây."
                            }
                        } catch (e: GatewayRequestException) {
                            llmStatus = "Lỗi: ${e.message}"
                        }
                        llmBusy = false
                    }
                },
                enabled = !llmBusy,
            ) {
                Text(text = if (llmBusy) "Đang lưu…" else "Lưu cài đặt LLM")
            }
            llmStatus?.let { s ->
                Text(text = s)
            }
        }
    }
}

@Composable
fun AboutScreen(
    navController: androidx.navigation.NavHostController,
) {
    androidx.compose.material3.Scaffold(
        topBar = { TopAppBar(title = { Text(text = "About") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.Text(text = "EMRG Android", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.material3.Text(text = "Version 0.3.63 (build 363)")
            androidx.compose.material3.Text(text = "Remote-first EMRG client. Connects to emrg-gateway on a host over pinned TLS.")
        }
    }
}
