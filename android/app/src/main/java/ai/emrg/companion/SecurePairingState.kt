package ai.emrg.companion

import java.net.URI

/** A verified host identity is required before any credential can be stored or used. */
internal data class PairedHost(
    val endpoint: String,
    val hostFingerprintSha256: String,
    val deviceId: String,
    val credentialAlias: String,
    val scopes: Set<PairingScope>,
    val approvedWorkspaceId: String? = null,
    val approvedCwd: String? = null,
)

/** The 20 companion scopes (frozen wire contract). Each value carries its exact wire name. */
internal enum class PairingScope(val wireName: String?, val description: String) {
    READ_STATUS(
        "status",
        "Host status — read-only status snapshot.",
    ),
    LIST_SESSIONS(
        "sessions.list",
        "List sessions — read-only session metadata.",
    ),
    MANAGE_SESSIONS(
        "sessions.manage",
        "Manage sessions — resume, rename, delete, clear, compact, rewind, history.",
    ),
    SEND_CHAT(
        "chat.send",
        "Chat — send prompts, cancel turns, answer tool-approval requests.",
    ),
    MONITOR_TOOLS(
        "tools.monitor",
        "Tool monitor — receive tool-activity stream frames. Read-only; never executes.",
    ),
    READ_FILES(
        "files.read",
        "Read files — list and read files inside the approved workspace.",
    ),
    WRITE_FILES(
        "files.write",
        "Write files — create, overwrite and delete files inside the approved workspace.",
    ),
    EXEC_TERMINAL(
        "terminal.exec",
        "Terminal — run commands inside the approved workspace. Dangerous tiers need a per-run confirmation.",
    ),
    MANAGE_SANDBOX(
        "sandbox.manage",
        "Sandbox — change the sandbox mode and its roots (roots stay inside the approved workspace).",
    ),
    LIST_HOSTS(
        "hosts.list",
        "List hosts — read-only host/agent list.",
    ),
    READ_MEMORY(
        "memory.read",
        "Read memory — read-only agent memories.",
    ),
    MANAGE_MODELS(
        "models.manage",
        "Manage models — list models and switch the engine model (global engine state).",
    ),
    SUBMIT_RANT(
        "rant.submit",
        "Rants — read the rants log and submit a rant.",
    ),
    READ_EVOLUTION(
        "evolution.read",
        "Evolution — read-only evolution summary.",
    ),
    MANAGE_TASKS(
        "tasks.manage",
        "Manage tasks — list, trigger, create, update and delete scheduled tasks and templates.",
    ),
    MANAGE_SKILLS(
        "skills.manage",
        "Manage skills — list, install and update skills (install fetches code).",
    ),
    READ_LOGS(
        "logs.read",
        "Read logs — read-only log tail.",
    ),
    READ_AUDIT(
        "audit.read",
        "Read audit — read-only audit log tail.",
    ),
    MANAGE_DEVICES(
        "devices.manage",
        "Manage devices — list paired devices and revoke them.",
    ),
    MANAGE_SETTINGS(
        "settings.manage",
        "Manage settings — read and change host settings.",
    ),
    /** Sketched in the first pairing draft, never wired to the host. Kept so old stored state still parses; never requestable. */
    CONTINUE_SESSIONS(
        null,
        "Legacy placeholder — never granted.",
    ),
    /** Sketched in the first pairing draft, never wired to the host. Kept so old stored state still parses; never requestable. */
    VIEW_TOOL_ACTIVITY(
        null,
        "Legacy placeholder — never granted.",
    );

    companion object {
        /** The 20 requestable scopes, in the order the scope picker shows them. */
        val REQUESTABLE: List<PairingScope> = entries.filter { it.wireName != null }

        /** Least-privilege fresh-pairing default; every other scope needs explicit opt-in. */
        val DEFAULT_REQUESTED: Set<PairingScope> = setOf(READ_STATUS)

        /**
         * The 20 requestable scopes grouped by the screen that needs them, in
         * hub order. The scope picker renders one section per group; every
         * group maps to a real screen (Status/Hosts are hub-level, not screens).
         */
        val GROUPED_BY_SCREEN: List<Pair<String, List<PairingScope>>> = listOf(
            "Status" to listOf(READ_STATUS),
            "Sessions" to listOf(LIST_SESSIONS, MANAGE_SESSIONS),
            "Chat" to listOf(SEND_CHAT),
            "Tool monitor" to listOf(MONITOR_TOOLS),
            "Files" to listOf(READ_FILES, WRITE_FILES),
            "Terminal" to listOf(EXEC_TERMINAL),
            "Sandbox" to listOf(MANAGE_SANDBOX),
            "Hosts" to listOf(LIST_HOSTS),
            "Memory" to listOf(READ_MEMORY),
            "Models" to listOf(MANAGE_MODELS),
            "Rant" to listOf(SUBMIT_RANT),
            "Evolution" to listOf(READ_EVOLUTION),
            "Tasks" to listOf(MANAGE_TASKS),
            "Skills" to listOf(MANAGE_SKILLS),
            "Logs" to listOf(READ_LOGS),
            "Audit" to listOf(READ_AUDIT),
            "Devices" to listOf(MANAGE_DEVICES),
            "Settings" to listOf(MANAGE_SETTINGS),
        )

        /** All 20 wire names, the single client-side allowlist. */
        val ALL_WIRE_NAMES: Set<String> = REQUESTABLE.mapNotNull { it.wireName }.toSet()

        fun fromWireName(wireName: String): PairingScope? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * Resolve the scope checkboxes for a pairing attempt.
 *
 * A fresh pairing starts with read-only status only. Re-pair may supply the
 * existing grants (and one explicitly requested additional scope). Legacy
 * placeholders are filtered, and an empty selection falls back to status so
 * the signed request can never contain zero scopes.
 */
internal fun resolveInitialPairingScopes(initialScopes: Set<PairingScope>?): Set<PairingScope> =
    initialScopes?.filter { it in PairingScope.REQUESTABLE }?.toSet()
        ?.takeIf { it.isNotEmpty() } ?: PairingScope.DEFAULT_REQUESTED

internal enum class PairingPhase {
    DISCONNECTED,
    AWAITING_HOST_VERIFICATION,
    AWAITING_HOST_APPROVAL,
    HOST_APPROVED,
    PAIRED,
    REVOKED,
    FAILED,
}

internal data class PairingState(
    val phase: PairingPhase = PairingPhase.DISCONNECTED,
    val candidateEndpoint: String? = null,
    val candidateFingerprint: String? = null,
    val pairedHost: PairedHost? = null,
    val errorCode: String? = null,
    /** Opaque v2 transaction identifier from the QR descriptor. */
    val candidatePairingId: String? = null,
)

internal sealed interface PairingAction {
    data class PairingQrReceived(
        val endpoint: String,
        val fingerprintSha256: String,
        val pairingId: String? = null,
    ) : PairingAction
    data class HostIdentityConfirmed(val fingerprintSha256: String) : PairingAction
    data object HostApproved : PairingAction
    data class CredentialIssued(val host: PairedHost) : PairingAction
    data object Revoked : PairingAction
    data object Failed : PairingAction
}

internal fun isSecurePairingEndpoint(endpoint: String): Boolean = try {
    val uri = URI(endpoint)
    uri.scheme.equals("wss", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
} catch (_: Exception) {
    false
}

internal fun isSha256Fingerprint(value: String): Boolean =
    value.matches(Regex("(?i)([0-9a-f]{2}:){31}[0-9a-f]{2}"))

internal fun reducePairingState(state: PairingState, action: PairingAction): PairingState =
    when (action) {
        is PairingAction.PairingQrReceived -> {
            val validPairingId = action.pairingId == null || try {
                val decoded = java.util.Base64.getUrlDecoder().decode(action.pairingId)
                decoded.size == 32 &&
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == action.pairingId
            } catch (_: IllegalArgumentException) {
                false
            }
            if (!isSecurePairingEndpoint(action.endpoint) ||
                !isSha256Fingerprint(action.fingerprintSha256) || !validPairingId
            ) {
                // Invalid input starts a fresh failed transaction; never retain a prior host or QR ID.
                PairingState(phase = PairingPhase.FAILED, errorCode = "INVALID_PAIRING_DESCRIPTOR")
            } else {
                PairingState(
                    phase = PairingPhase.AWAITING_HOST_VERIFICATION,
                    candidateEndpoint = action.endpoint,
                    candidateFingerprint = action.fingerprintSha256.uppercase(),
                    candidatePairingId = action.pairingId,
                )
            }
        }
        is PairingAction.HostIdentityConfirmed ->
            if (state.phase != PairingPhase.AWAITING_HOST_VERIFICATION ||
                !action.fingerprintSha256.equals(state.candidateFingerprint, ignoreCase = true)
            ) state.copy(phase = PairingPhase.FAILED, errorCode = "HOST_IDENTITY_MISMATCH")
            else state.copy(phase = PairingPhase.AWAITING_HOST_APPROVAL)
        PairingAction.HostApproved ->
            if (state.phase == PairingPhase.AWAITING_HOST_APPROVAL)
                state.copy(phase = PairingPhase.HOST_APPROVED)
            else state
        is PairingAction.CredentialIssued ->
            if (state.phase != PairingPhase.HOST_APPROVED ||
                action.host.endpoint != state.candidateEndpoint ||
                !action.host.hostFingerprintSha256.equals(state.candidateFingerprint, ignoreCase = true) ||
                action.host.deviceId.isBlank() ||
                action.host.credentialAlias.isBlank() ||
                action.host.scopes.isEmpty() ||
                // Every scope except plain status is bound to the approved workspace.
                // The host cwd is deliberately never sent to the client (the server
                // supplies it from the pairing record), so no scope may require it.
                (action.host.scopes.any { it != PairingScope.READ_STATUS } &&
                    action.host.approvedWorkspaceId?.matches(Regex("[A-Za-z0-9._:-]{1,128}")) != true)
            ) state.copy(phase = PairingPhase.FAILED, errorCode = "INVALID_DEVICE_CREDENTIAL")
            else state.copy(phase = PairingPhase.PAIRED, pairedHost = action.host, errorCode = null)
        PairingAction.Revoked -> PairingState(phase = PairingPhase.REVOKED)
        PairingAction.Failed -> PairingState(phase = PairingPhase.FAILED, errorCode = "PAIRING_FAILED")
    }
