package com.emrg.app.data

import android.content.Context
import com.emrg.app.model.AuditEntry
import com.emrg.app.model.DeviceInfo
import com.emrg.app.model.EvolutionRecord
import com.emrg.app.model.GatewaySnapshot
import com.emrg.app.model.HistoryMessage
import com.emrg.app.model.HostProfile
import com.emrg.app.model.LogPage
import com.emrg.app.model.MemoryContent
import com.emrg.app.model.MemoryEntry
import com.emrg.app.model.ModelInfo
import com.emrg.app.model.RantEntry
import com.emrg.app.model.SandboxState
import com.emrg.app.model.SessionSummary
import com.emrg.app.model.SkillInfo
import com.emrg.app.model.TaskInfo
import com.emrg.app.model.TaskTemplate
import com.emrg.app.model.Workspace
import com.emrg.app.net.GatewayClient
import com.emrg.app.net.GatewayProtocol
import com.emrg.app.net.LinkState
import com.emrg.app.net.Pairing
import com.emrg.app.net.PairingException
import com.emrg.app.net.arr
import com.emrg.app.net.asArray
import com.emrg.app.net.asObject
import com.emrg.app.net.bool
import com.emrg.app.net.contentOrNull
import com.emrg.app.net.has
import com.emrg.app.net.int
import com.emrg.app.net.obj
import com.emrg.app.net.str
import com.emrg.app.security.SecureTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The app's single door to the host.
 *
 * Screens talk to this class, never to [GatewayClient] directly, so a frame
 * shape is parsed in one place. Two kinds of call exist and the distinction is
 * the gateway's, not this class's:
 *
 * * the gateway's own operations (`gw_*`) are answered by their `rid`;
 * * the daemon's frames are answered by their response type — exactly what the
 *   desktop client does, because the daemon echoes no request id.
 *
 * Chat itself is read from [frames]; everything else here is a request/response
 * that resolves to a value or throws [GatewayRequestException].
 */
class GatewayRepository(context: Context) {

    private val app = context.applicationContext
    private val client = GatewayClient()
    private val hostStore = HostStore(app)
    private val tokens = SecureTokenStore(app)
    private val prefs = app.getSharedPreferences("emrg_repository", Context.MODE_PRIVATE)

    val state: StateFlow<LinkState> = client.state
    val frames: SharedFlow<JsonObject> = client.frames

    private val _hosts = MutableStateFlow(hostStore.load())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()

    private val _active = MutableStateFlow<HostProfile?>(null)
    val activeHost: StateFlow<HostProfile?> = _active.asStateFlow()

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _workspace = MutableStateFlow("")
    val workspace: StateFlow<String> = _workspace.asStateFlow()

    // ── hosts and pairing ────────────────────────────────────────────

    suspend fun pair(
        host: String,
        port: Int,
        pin: String,
        code: String,
        deviceName: String,
    ): HostProfile {
        // Pairing failures (refused code, unreachable host, bad fingerprint,
        // timeout) arrive as PairingException. The Pair screen only catches
        // GatewayRequestException, so an unmapped PairingException used to
        // escape the coroutine and crash the app instead of showing the
        // error — that is what happened on the phone on 2026-11-17.
        val outcome = try {
            Pairing.pair(
                host = host.trim(),
                port = port,
                pin = pin,
                code = code.trim(),
                deviceName = deviceName.trim().ifEmpty { "Android device" },
                appVersion = com.emrg.app.BuildConfig.VERSION_NAME,
            )
        } catch (e: PairingException) {
            throw GatewayRequestException(e.code, e.message ?: "pairing failed", retryable = true)
        }
        val profile = HostProfile(
            id = outcome.deviceId,
            name = deviceName.trim().ifEmpty { "Android device" },
            host = host.trim(),
            port = port,
            fingerprint = pin,
            deviceId = outcome.deviceId,
            lastUsed = System.currentTimeMillis(),
        )
        // A host re-paired after a revocation gets a new device id; the old
        // profile's token is dead, so keeping it would only offer a broken row.
        for (old in _hosts.value) {
            if (old.host.equals(profile.host, ignoreCase = true) && old.port == profile.port) {
                tokens.remove(old.id)
                hostStore.remove(old.id)
            }
        }
        tokens.put(profile.id, outcome.token)
        hostStore.save(profile)
        _hosts.value = hostStore.load()
        return profile
    }

    suspend fun connect(host: HostProfile) {
        val token = tokens.get(host.id)
            ?: throw GatewayRequestException(
                "no_token",
                "this host has no stored token — pair it again",
            )
        client.connect(host, token)
        _active.value = host
        hostStore.touch(host.id)
        _hosts.value = hostStore.load()
        restoreWorkspace(host)
        // Workspace discovery is best-effort: if the daemon cannot list
        // them, the socket is still up and the Sessions screen lets the
        // user pick a folder by hand instead of failing the whole connect.
        try {
            loadWorkspaces()
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        client.disconnect()
    }

    fun forget(host: HostProfile) {
        tokens.remove(host.id)
        hostStore.remove(host.id)
        _hosts.value = hostStore.load()
        prefs.edit().remove(workspaceKey(host.id)).apply()
        if (_active.value?.id == host.id) {
            client.disconnect()
            _active.value = null
            _workspace.value = ""
            _workspaces.value = emptyList()
        }
    }

    fun renameHost(host: HostProfile, name: String) {
        hostStore.save(host.copy(name = name))
        _hosts.value = hostStore.load()
        if (_active.value?.id == host.id) _active.value = host.copy(name = name)
    }

    private fun workspaceKey(hostId: String) = "workspace.$hostId"

    private fun restoreWorkspace(host: HostProfile) {
        val saved = prefs.getString(workspaceKey(host.id), "").orEmpty()
        _workspace.value = saved
    }

    fun selectWorkspace(path: String) {
        _workspace.value = path
        _active.value?.let { prefs.edit().putString(workspaceKey(it.id), path).apply() }
    }

    suspend fun loadWorkspaces() {
        val response = client.request("list_agents", expectType = "agents_list")
        val list = response.arr("agents").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val path = row.str("path").orEmpty()
            if (path.isEmpty()) return@mapNotNull null
            Workspace(
                name = row.str("name").orEmpty().ifEmpty { path.substringAfterLast('/') },
                path = path,
                repo = row.str("repo").orEmpty(),
                latestSessionAt = row.str("latest_session_at").orEmpty(),
            )
        }
        _workspaces.value = list
        val current = _workspace.value
        if (current.isEmpty() || list.none { it.path == current }) {
            list.firstOrNull()?.let { selectWorkspace(it.path) }
        }
    }

    fun currentWorkspace(): String = _workspace.value

    fun currentSessionCwd(sessionCwd: String? = null): String =
        sessionCwd?.takeIf { it.isNotBlank() } ?: _workspace.value

    // ── sessions ─────────────────────────────────────────────────────

    suspend fun sessions(cwd: String = currentWorkspace()): List<SessionSummary> {
        if (cwd.isBlank()) return emptyList()
        val response = client.request(
            "list_sessions",
            buildJsonObject { put("cwd", cwd) },
            expectType = "sessions_list",
        )
        return response.arr("sessions").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val id = row.str("session_id") ?: return@mapNotNull null
            SessionSummary(
                id = id,
                title = row.str("title").orEmpty().ifEmpty { "Untitled session" },
                cwd = row.str("cwd").orEmpty().ifEmpty { cwd },
                updatedAt = row.str("updated_at") ?: row.str("created_at").orEmpty(),
                createdAt = row.str("created_at").orEmpty(),
                messageCount = row.int("message_count") ?: 0,
            )
        }
    }

    suspend fun newSessionId(cwd: String): String {
        val stamp = SimpleDateFormat("yyMMdd_HHmm", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val id = "s_${stamp}_$suffix"
        // Resuming a client-generated id is what creates the directory; the
        // daemon owns the write, so the app only proposes the name.
        client.request(
            "resume_session",
            buildJsonObject {
                put("session_id", id)
                put("cwd", cwd)
            },
            expectType = "resume_result",
        )
        return id
    }

    suspend fun history(
        sessionId: String,
        cwd: String,
        limit: Int = 200,
    ): List<HistoryMessage> {
        val response = client.request(
            "list_history",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("include_assistant", true)
                put("limit", limit)
            },
            expectType = "history_list",
            timeoutMs = 30_000,
        )
        return response.arr("messages").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val role = row.str("role") ?: return@mapNotNull null
            HistoryMessage(
                recordIndex = row.int("record_index") ?: 0,
                role = role,
                content = row.str("content").orEmpty(),
                timestamp = row.str("timestamp").orEmpty(),
            )
        }
    }

    suspend fun rename(sessionId: String, cwd: String, title: String): String {
        val response = client.request(
            "rename_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("title", title)
            },
            expectType = "rename_result",
        )
        response.str("error")?.let { throw GatewayRequestException("rename_failed", it) }
        return response.str("title") ?: title
    }

    suspend fun deleteSession(sessionId: String, cwd: String) {
        val response = client.request(
            "delete_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
            },
            expectType = "session_deleted",
        )
        response.str("error")?.let { throw GatewayRequestException("delete_failed", it) }
    }

    suspend fun clear(sessionId: String, cwd: String) {
        val response = client.request(
            "clear_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
            },
            expectType = "clear_result",
        )
        response.str("error")?.let { throw GatewayRequestException("clear_failed", it) }
    }

    suspend fun compact(sessionId: String, cwd: String) {
        val response = client.request(
            "compact",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
            },
            expectType = "compact_result",
            timeoutMs = 120_000,
        )
        response.str("error")?.let { throw GatewayRequestException("compact_failed", it) }
    }

    suspend fun rewind(sessionId: String, cwd: String, recordIndex: Int) {
        val response = client.request(
            "rewind_session",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("record_index", recordIndex)
            },
            expectType = "rewind_result",
        )
        response.str("error")?.let { throw GatewayRequestException("rewind_failed", it) }
    }

    // ── chat ─────────────────────────────────────────────────────────

    fun sendPrompt(
        sessionId: String,
        cwd: String,
        prompt: String,
        images: List<String> = emptyList(),
        sandbox: String? = null,
    ): String {
        val id = "and-" + UUID.randomUUID().toString().take(12)
        val fields = buildJsonObject {
            put("id", id)
            put("session_id", sessionId)
            put("cwd", cwd)
            put("prompt", prompt)
            put("timestamp", System.currentTimeMillis().toString())
            if (images.isNotEmpty()) {
                put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
            }
            if (sandbox != null) put("sandbox", sandbox)
        }
        if (!client.send("task", fields)) {
            throw GatewayRequestException("offline", "the host is not connected", true)
        }
        return id
    }

    fun cancel(sessionId: String) {
        client.send(
            "cancel",
            buildJsonObject { put("session_id", sessionId) },
        )
    }

    fun answerApproval(requestId: String, approved: Boolean) {
        client.send(
            "approval_response",
            buildJsonObject {
                put("request_id", requestId)
                put("approved", approved)
            },
        )
    }

    // ── models and config ────────────────────────────────────────────

    suspend fun models(): Pair<List<ModelInfo>, String> {
        val response = client.request("list_models", expectType = "models_list")
        val models = response.arr("models").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val name = row.str("name") ?: row.str("model") ?: return@mapNotNull null
            ModelInfo(
                name = name,
                model = row.str("model") ?: name,
                contextWindow = row.int("context_window") ?: 0,
                vision = row.bool("vision") ?: false,
            )
        }
        return models to response.str("current").orEmpty()
    }

    suspend fun setModel(name: String): String {
        val response = client.request(
            "set_model",
            buildJsonObject { put("model", name) },
            expectType = "model_set",
        )
        response.str("error")?.let { throw GatewayRequestException("model_failed", it) }
        return response.str("model") ?: name
    }

    suspend fun gatewaySnapshot(): GatewaySnapshot {
        val response = client.call("gw_config_get")
        val gateway = response.obj("gateway") ?: JsonObject(emptyMap())
        val engine = response.obj("engine") ?: JsonObject(emptyMap())
        return GatewaySnapshot(
            host = gateway.str("host").orEmpty(),
            port = gateway.int("port") ?: 0,
            defaultExecMode = gateway.str("default_exec_mode") ?: "workspace-write",
            allowDangerExec = gateway.bool("allow_danger_exec") ?: false,
            maxDevices = gateway.int("max_devices") ?: 0,
            engineModel = engine.str("model").orEmpty(),
            baseUrl = engine.str("base_url").orEmpty(),
            apiKeySet = engine.bool("api_key_set") ?: false,
            temperature = engine["temperature"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            } ?: 0.0,
            theme = engine.obj("client")?.str("theme") ?: "auto",
            configPath = engine.str("config_path").orEmpty(),
        )
    }

    suspend fun saveEngineConfig(patch: Map<String, JsonElement>): JsonObject {
        return client.call(
            "gw_config_set",
            buildJsonObject {
                put("patch", JsonObject(patch))
            },
        )
    }

    suspend fun saveGatewayConfig(patch: Map<String, JsonElement>): JsonObject {
        return client.call(
            "gw_config_set",
            buildJsonObject {
                put("gateway", JsonObject(patch))
            },
        )
    }

    /** Reconnect to the last-used host now, instead of waiting for the
     * client's backoff loop. Used by the chat's offline banner. */
    suspend fun reconnectLastHost() {
        val target = activeHost.value ?: hosts.value.maxByOrNull { it.lastUsed }
            ?: throw GatewayRequestException("no_host", "no paired host to reconnect to", false)
        connect(target)
    }

    // ── memory ───────────────────────────────────────────────────────

    suspend fun memories(scope: String, sessionId: String, cwd: String): List<MemoryEntry> {
        val response = client.request(
            "list_memories",
            buildJsonObject {
                put("scope", scope)
                if (sessionId.isNotEmpty()) put("session_id", sessionId)
                if (cwd.isNotEmpty()) put("cwd", cwd)
            },
            expectType = "memories_list",
        )
        return response.arr("memories").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            MemoryEntry(
                id = row.str("id") ?: return@mapNotNull null,
                title = row.str("title").orEmpty().ifEmpty { row.str("file").orEmpty() },
                scope = scope,
                size = 0,
            )
        }
    }

    suspend fun readMemory(scope: String, memoryId: String, sessionId: String, cwd: String): MemoryContent {
        val response = client.request(
            "read_memory",
            buildJsonObject {
                put("scope", scope)
                put("memory_id", memoryId)
                if (sessionId.isNotEmpty()) put("session_id", sessionId)
                if (cwd.isNotEmpty()) put("cwd", cwd)
            },
            expectType = "memory_content",
        )
        response.str("error")?.let { throw GatewayRequestException("memory_failed", it) }
        return MemoryContent(
            id = memoryId,
            title = response.str("title").orEmpty().ifEmpty { memoryId },
            content = response.str("content").orEmpty(),
        )
    }

    // ── files ────────────────────────────────────────────────────────

    suspend fun listFiles(path: String): List<Triple<String, String, String>> {
        val response = client.request(
            "list_files",
            buildJsonObject { put("path", path) },
            expectType = "files_list",
        )
        response.str("error")?.let { throw GatewayRequestException("files_failed", it) }
        return response.arr("entries").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            Triple(
                row.str("name").orEmpty(),
                row.str("path").orEmpty(),
                row.str("type").orEmpty(),
            )
        }
    }

    suspend fun readFile(path: String): String {
        val response = client.call(
            "gw_read_file",
            buildJsonObject { put("path", path) },
        )
        response.str("error")?.let { throw GatewayRequestException("read_failed", it) }
        return response.str("content").orEmpty()
    }

    suspend fun fileDigest(path: String): String {
        val response = client.call(
            "gw_read_file",
            buildJsonObject { put("path", path) },
        )
        return response.str("sha256").orEmpty()
    }

    suspend fun writeFile(
        path: String,
        content: String,
        cwd: String,
        sessionId: String,
        expectedSha256: String? = null,
    ) {
        client.call(
            "gw_write_file",
            buildJsonObject {
                put("path", path)
                put("content", content)
                put("cwd", cwd)
                if (sessionId.isNotEmpty()) put("session_id", sessionId)
                if (expectedSha256 != null) put("expected_sha256", expectedSha256)
            },
        )
    }

    // ── terminal ─────────────────────────────────────────────────────

    data class ExecOutcome(
        val execId: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val signal: Int?,
        val timedOut: Boolean,
        val mode: String,
        val durationMs: Int,
        val sandbox: String,
    )

    suspend fun exec(
        command: String,
        cwd: String,
        sessionId: String,
        mode: String?,
        timeout: Int,
        confirmDanger: Boolean = false,
        confirmRisky: Boolean = false,
    ): ExecOutcome {
        val response = client.call(
            "gw_exec",
            buildJsonObject {
                put("command", command)
                put("cwd", cwd)
                if (sessionId.isNotEmpty()) put("session_id", sessionId)
                if (mode != null) put("mode", mode)
                put("timeout", timeout)
                if (confirmDanger) put("confirm_danger", true)
                if (confirmRisky) put("confirm_risky", true)
            },
            timeoutMs = (timeout + 15).toLong() * 1000,
        )
        val enforcement = response.obj("sandbox")?.str("enforcement").orEmpty()
        return ExecOutcome(
            execId = response.str("exec_id").orEmpty(),
            stdout = response.str("stdout").orEmpty(),
            stderr = response.str("stderr").orEmpty(),
            exitCode = response.int("exit_code"),
            signal = response.int("signal"),
            timedOut = response.bool("timed_out") ?: false,
            mode = response.str("mode").orEmpty(),
            durationMs = response.int("duration_ms") ?: 0,
            sandbox = enforcement,
        )
    }

    // ── sandbox ──────────────────────────────────────────────────────

    suspend fun sandbox(sessionId: String, cwd: String): SandboxState {
        val response = client.request(
            "set_sandbox_roots",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("op", "list")
            },
            expectType = "sandbox_roots",
        )
        return SandboxState(
            mode = "",
            roots = response.arr("roots").orEmpty().mapNotNull { it.asString() },
            grants = response.arr("grants").orEmpty().mapNotNull { element ->
                val row = element.asObject() ?: return@mapNotNull null
                row.str("path")?.let { it to (row.str("source") ?: row.str("kind") ?: "session") }
            },
            withheld = response.arr("withheld").orEmpty().mapNotNull { it.asString() },
            notice = response.str("notice") ?: response.str("error"),
        )
    }

    suspend fun setSandbox(sessionId: String, cwd: String, mode: String) {
        val response = client.request(
            "set_sandbox",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("mode", mode)
            },
            expectType = "sandbox_roots",
        )
        response.str("error")?.let { throw GatewayRequestException("sandbox_failed", it) }
    }

    suspend fun addSandboxRoot(sessionId: String, cwd: String, path: String, remove: Boolean = false) {
        val response = client.request(
            "set_sandbox_roots",
            buildJsonObject {
                put("session_id", sessionId)
                put("cwd", cwd)
                put("op", if (remove) "remove" else "add")
                put("path", path)
            },
            expectType = "sandbox_roots",
        )
        response.str("error")?.let { throw GatewayRequestException("roots_failed", it) }
    }

    // ── rants, evolution, tasks, skills ──────────────────────────────

    suspend fun rants(): List<RantEntry> {
        val response = client.request("list_rants", expectType = "rants_list")
        return response.arr("rants").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            RantEntry(
                timestamp = row.str("timestamp").orEmpty(),
                message = row.str("message").orEmpty(),
                status = row.str("status").orEmpty(),
                agent = row.str("agent").orEmpty(),
            )
        }
    }

    suspend fun submitRant(message: String, agent: String = ""): Int {
        val response = client.sendAndAwait(
            "rant",
            buildJsonObject {
                put("message", message)
                if (agent.isNotEmpty()) put("agent", agent)
            },
            match = { it.has("ok") },
            timeoutMessage = "the rant was not acknowledged",
        )
        response.str("error")?.let { throw GatewayRequestException("rant_failed", it) }
        return response.int("count") ?: 0
    }

    suspend fun evolution(): Pair<Int, List<EvolutionRecord>> {
        val response = client.request("evolution_summary", expectType = "evolution_summary")
        val records = response.arr("recent").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            EvolutionRecord(
                timestamp = row.str("timestamp").orEmpty(),
                task = row.str("task").orEmpty(),
                work = row.str("work").orEmpty(),
                toolCount = row.int("tool_count") ?: 0,
                recommendSlowdown = row.bool("recommend_slowdown") ?: false,
            )
        }
        return (response.int("count") ?: 0) to records
    }

    suspend fun tasks(): List<TaskInfo> {
        val response = client.request("list_tasks", expectType = "tasks_list")
        return response.arr("tasks").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            TaskInfo(
                name = row.str("name") ?: return@mapNotNull null,
                prompt = row.str("prompt").orEmpty(),
                interval = row.str("interval")?.toString()
                    ?: row.str("effective_interval").orEmpty(),
                enabled = row.bool("enabled") ?: true,
                sandbox = row.str("sandbox").orEmpty(),
            )
        }
    }

    suspend fun templates(): List<TaskTemplate> {
        val response = client.request("task_template_list", expectType = "templates_list")
        return response.arr("templates").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            TaskTemplate(
                name = row.str("name") ?: return@mapNotNull null,
                prompt = row.str("prompt").orEmpty(),
            )
        }
    }

    suspend fun triggerTask(name: String): String {
        val response = client.request(
            "trigger_task",
            buildJsonObject { put("name", name) },
            expectType = "trigger_result",
        )
        response.str("error")?.let { throw GatewayRequestException("trigger_failed", it) }
        return response.str("message").orEmpty().ifEmpty { "triggered $name" }
    }

    suspend fun skills(): List<SkillInfo> {
        val response = client.request(
            "list_skills",
            buildJsonObject { put("cwd", currentWorkspace()) },
            expectType = "skills_list",
        )
        return response.arr("skills").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            SkillInfo(
                name = row.str("name") ?: return@mapNotNull null,
                description = row.str("description").orEmpty(),
                source = row.str("source").orEmpty(),
                path = row.str("path").orEmpty(),
            )
        }
    }

    // ── logs, devices, audit ─────────────────────────────────────────

    suspend fun logs(source: String, lines: Int = 400): LogPage {
        val response = client.call(
            "gw_logs_tail",
            buildJsonObject {
                put("source", source)
                put("lines", lines)
            },
        )
        return LogPage(
            source = response.str("source") ?: source,
            path = response.str("path").orEmpty(),
            lines = response.arr("lines").orEmpty().mapNotNull { it.asString() },
            truncated = response.bool("truncated") ?: false,
        )
    }

    suspend fun audit(lines: Int = 400): List<AuditEntry> {
        val response = client.call(
            "gw_audit_tail",
            buildJsonObject { put("lines", lines) },
        )
        return response.arr("entries").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            AuditEntry(
                ts = row.str("ts").orEmpty(),
                device = row.str("device_name").orEmpty().ifEmpty { row.str("device_id").orEmpty() },
                action = row.str("action").orEmpty(),
                decision = row.str("decision").orEmpty(),
                detail = row["detail"]?.toString().orEmpty(),
            )
        }
    }

    suspend fun devices(): List<DeviceInfo> {
        val response = client.call("gw_devices")
        return response.arr("devices").orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            DeviceInfo(
                id = row.str("id") ?: return@mapNotNull null,
                name = row.str("name").orEmpty(),
                created = row.str("created").orEmpty(),
                lastSeen = row.str("last_seen").orEmpty(),
                scopes = row.arr("scopes").orEmpty().mapNotNull { it.asString() },
                connected = row.bool("connected") ?: false,
                activeTurn = row.bool("active_turn") ?: false,
            )
        }
    }

    suspend fun revokeDevice(deviceId: String): Boolean {
        val response = client.call(
            "gw_revoke_device",
            buildJsonObject { put("device_id", deviceId) },
        )
        return response.bool("revoked") ?: false
    }

    fun close() = client.close()
}

private fun JsonElement?.asString(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/**
 * Lỗi do gateway trả về (mã kết quả, thông báo, có thể retry).
 * Định nghĩa ở gói `data` để cả repository lẫn client (net) đều dùng được
 * mà không cần import chéo gói.
 */
class GatewayRequestException(
    val code: String,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)
