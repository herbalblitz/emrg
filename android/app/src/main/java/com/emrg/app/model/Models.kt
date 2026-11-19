package com.emrg.app.model

import kotlinx.serialization.Serializable

/**
 * The app's own types. Everything that crosses the wire stays a `JsonObject`
 * in [com.emrg.app.net.GatewayClient]; these are the screens' vocabulary.
 */

@Serializable
data class HostProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val lastUsed: Long = 0L,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Online(val engineVersion: String, val apiVersion: String, val host: HostProfile) : ConnectionState
    data class Failed(val message: String, val host: HostProfile? = null) : ConnectionState
}

data class Workspace(
    val name: String,
    val path: String,
    val repo: String,
    val latestSessionAt: String,
)

data class HistoryMessage(
    val recordIndex: Int,
    val role: String,
    val content: String,
    val timestamp: String,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: String,
    val createdAt: String,
    val messageCount: Int,
    // Which agent owns the session; filled by list_all_sessions (upstream
    // 0.3.63), empty for the per-workspace list_sessions rows that carry none.
    val agent: String = "",
)

data class ModelInfo(
    val name: String,
    val model: String,
    val contextWindow: Int,
    val vision: Boolean,
)

data class MemoryEntry(val id: String, val title: String, val scope: String, val size: Long = 0L)

data class TaskInfo(
    val name: String,
    val prompt: String,
    val interval: String,
    val enabled: Boolean,
    val sandbox: String,
)

data class TaskTemplate(val name: String, val prompt: String)

data class SkillInfo(val name: String, val description: String, val source: String, val path: String)

data class DeviceInfo(
    val id: String,
    val name: String,
    val created: String,
    val lastSeen: String,
    val scopes: List<String>,
    val connected: Boolean,
    val activeTurn: Boolean,
)

data class GatewaySnapshot(
    val host: String,
    val port: Int,
    val defaultExecMode: String,
    val allowDangerExec: Boolean,
    val maxDevices: Int,
    val engineModel: String,
    val baseUrl: String,
    val apiKeySet: Boolean,
    val temperature: Double,
    val theme: String,
    val configPath: String,
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val timestamp: String = "",
    val streaming: Boolean = false,
    val error: Boolean = false,
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL }
}

data class ToolCall(
    val id: String,
    val name: String,
    val intent: String,
    val arguments: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val output: String = "",
    val error: Boolean = false,
) {
    enum class ToolStatus { RUNNING, DONE, FAILED }
}

data class ApprovalAsk(
    val requestId: String,
    val question: String,
    val timeoutSeconds: Int,
    val sessionId: String,
)

data class RantEntry(
    val timestamp: String,
    val message: String,
    val status: String,
    val agent: String,
)

data class EvolutionRecord(
    val timestamp: String,
    val task: String,
    val work: String,
    val toolCount: Int,
    val recommendSlowdown: Boolean,
)

data class LogPage(val source: String, val path: String, val lines: List<String>, val truncated: Boolean)

data class AuditEntry(val ts: String, val device: String, val action: String, val decision: String, val detail: String)

data class MemoryContent(val id: String, val title: String, val content: String)

data class SandboxState(
    val mode: String,
    val roots: List<String>,
    val grants: List<Pair<String, String>>,
    val withheld: List<String>,
    val notice: String? = null,
)

data class CommandResult(val ok: Boolean, val message: String)
