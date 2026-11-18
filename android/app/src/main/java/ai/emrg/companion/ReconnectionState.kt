package ai.emrg.companion

/** Connection state model only. No sockets, retry timers, or pairing transport are implemented here. */
internal enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    RECONCILING,
    READY,
    STATE_UNKNOWN,
    RETRY_WAIT,
    AUTH_FAILED,
    IDENTITY_FAILED,
}

internal data class HostSnapshot(
    val protocolVersion: String,
    val authorized: Boolean,
    val sessionIds: Set<String>,
    val activeRequestIds: Set<String>,
    val eventCursor: String? = null,
    val workspaceId: String? = null,
    val cwd: String? = null,
)

internal data class SnapshotValidation(
    val valid: Boolean,
    val reason: String? = null,
)

/** Validate snapshot fields and enforce the workspace/cwd binding expected by the caller. */
internal fun validateHostSnapshot(
    snapshot: HostSnapshot,
    expectedWorkspaceId: String? = null,
    expectedCwd: String? = null,
): SnapshotValidation {
    if (!snapshot.authorized) return SnapshotValidation(false, "Device authorization is not valid")
    if (!isSupportedProtocolVersion(snapshot.protocolVersion)) {
        return SnapshotValidation(false, "Unsupported or missing protocol version")
    }
    if (snapshot.sessionIds.any { it.isBlank() }) return SnapshotValidation(false, "Snapshot contains blank session ID")
    if (snapshot.activeRequestIds.any { it.isBlank() }) return SnapshotValidation(false, "Snapshot contains blank request ID")
    if (snapshot.eventCursor != null && snapshot.eventCursor.isBlank()) {
        return SnapshotValidation(false, "Snapshot event cursor is blank")
    }
    if (expectedWorkspaceId != null && snapshot.workspaceId != expectedWorkspaceId) {
        return SnapshotValidation(false, "Snapshot workspace does not match approved workspace")
    }
    if (expectedCwd != null && snapshot.cwd != expectedCwd) {
        return SnapshotValidation(false, "Snapshot cwd does not match approved working directory")
    }
    if (snapshot.workspaceId != null && snapshot.workspaceId.isBlank()) {
        return SnapshotValidation(false, "Snapshot workspace ID is blank")
    }
    if (snapshot.cwd != null && snapshot.cwd.isBlank()) {
        return SnapshotValidation(false, "Snapshot cwd is blank")
    }
    return SnapshotValidation(true)
}

internal data class ReconnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val connectionEpoch: Long = 0,
    val retryAttempt: Int = 0,
    val retryDelayMillis: Long? = null,
    val snapshot: HostSnapshot? = null,
    val lastError: String? = null,
    val userDisconnected: Boolean = false,
    val appInForeground: Boolean = true,
)

internal sealed interface ReconnectionAction {
    data object Connect : ReconnectionAction
    data class AttemptStarted(val epoch: Long) : ReconnectionAction
    data class Authenticated(val epoch: Long) : ReconnectionAction
    data class SnapshotReceived(val epoch: Long, val snapshot: HostSnapshot) : ReconnectionAction
    data class SnapshotUnavailable(val epoch: Long) : ReconnectionAction
    data class ConnectionLost(val epoch: Long, val reason: String? = null) : ReconnectionAction
    data class RetryScheduled(val epoch: Long, val attempt: Int) : ReconnectionAction
    data class RetryExhausted(val epoch: Long) : ReconnectionAction
    data class AuthenticationRejected(val epoch: Long) : ReconnectionAction
    data class HostIdentityMismatch(val epoch: Long) : ReconnectionAction
    data object UserDisconnect : ReconnectionAction
    data class AppForegroundChanged(val foreground: Boolean) : ReconnectionAction
}

/** Retry schedule is bounded: 1, 2, 4, 8, 16, 30 seconds, with caller-added jitter. */
internal fun reconnectBackoffMillis(attempt: Int): Long {
    if (attempt <= 0) return 1_000L
    val shift = (attempt - 1).coerceAtMost(5)
    return (1_000L shl shift).coerceAtMost(30_000L)
}

internal const val MAX_RECONNECT_ATTEMPTS = 6

/** Current contract accepts protocol major 1; minor versions are backward-compatible. */
internal fun isSupportedProtocolVersion(version: String): Boolean =
    Regex("^1(?:\\.(?:0|[1-9][0-9]*))?$", RegexOption.IGNORE_CASE).matches(version)

internal fun reduceReconnection(
    state: ReconnectionState,
    action: ReconnectionAction,
): ReconnectionState = when (action) {
    ReconnectionAction.Connect -> state.copy(
        phase = ConnectionPhase.CONNECTING,
        connectionEpoch = state.connectionEpoch + 1,
        retryAttempt = 0,
        retryDelayMillis = null,
        snapshot = null,
        lastError = null,
        userDisconnected = false,
    )
    is ReconnectionAction.AttemptStarted -> if (action.epoch != state.connectionEpoch ||
        state.userDisconnected || !state.appInForeground
    ) state else state.copy(phase = ConnectionPhase.CONNECTING, retryDelayMillis = null)
    is ReconnectionAction.Authenticated -> if (action.epoch != state.connectionEpoch ||
        state.phase != ConnectionPhase.CONNECTING
    ) state else state.copy(phase = ConnectionPhase.RECONCILING)
    is ReconnectionAction.SnapshotReceived -> if (action.epoch != state.connectionEpoch ||
        state.phase != ConnectionPhase.RECONCILING
    ) state else if (!action.snapshot.authorized) state.copy(
        phase = ConnectionPhase.AUTH_FAILED,
        snapshot = null,
        lastError = "Device authorization is no longer valid",
    ) else if (action.snapshot.protocolVersion.isBlank()) state.copy(
        phase = ConnectionPhase.STATE_UNKNOWN,
        snapshot = null,
        lastError = "Host snapshot is missing protocol version",
    ) else if (!isSupportedProtocolVersion(action.snapshot.protocolVersion)) state.copy(
        phase = ConnectionPhase.STATE_UNKNOWN,
        snapshot = null,
        lastError = "Unsupported host protocol version: ${action.snapshot.protocolVersion}",
    ) else state.copy(
        phase = ConnectionPhase.READY,
        snapshot = action.snapshot,
        retryAttempt = 0,
        retryDelayMillis = null,
        lastError = null,
    )
    is ReconnectionAction.SnapshotUnavailable -> if (action.epoch != state.connectionEpoch ||
        state.phase != ConnectionPhase.RECONCILING
    ) state else state.copy(
        phase = ConnectionPhase.STATE_UNKNOWN,
        snapshot = null,
        lastError = "Host state could not be reconciled",
    )
    is ReconnectionAction.ConnectionLost -> if (action.epoch != state.connectionEpoch ||
        state.userDisconnected
    ) state else state.copy(
        phase = ConnectionPhase.RETRY_WAIT,
        snapshot = null,
        retryDelayMillis = null,
        lastError = action.reason ?: "Connection lost",
    )
    is ReconnectionAction.RetryScheduled -> if (action.epoch != state.connectionEpoch ||
        state.userDisconnected || !state.appInForeground || action.attempt !in 1..MAX_RECONNECT_ATTEMPTS
    ) state else state.copy(
        phase = ConnectionPhase.RETRY_WAIT,
        retryAttempt = action.attempt,
        retryDelayMillis = reconnectBackoffMillis(action.attempt),
    )
    is ReconnectionAction.RetryExhausted -> if (action.epoch != state.connectionEpoch)
        state else state.copy(phase = ConnectionPhase.DISCONNECTED, retryDelayMillis = null)
    is ReconnectionAction.AuthenticationRejected -> if (action.epoch != state.connectionEpoch)
        state else state.copy(phase = ConnectionPhase.AUTH_FAILED, snapshot = null, retryDelayMillis = null,
            lastError = "Authentication rejected")
    is ReconnectionAction.HostIdentityMismatch -> if (action.epoch != state.connectionEpoch)
        state else state.copy(phase = ConnectionPhase.IDENTITY_FAILED, snapshot = null, retryDelayMillis = null,
            lastError = "Host identity changed; re-pairing required")
    ReconnectionAction.UserDisconnect -> state.copy(
        phase = ConnectionPhase.DISCONNECTED,
        snapshot = null,
        retryDelayMillis = null,
        userDisconnected = true,
    )
    is ReconnectionAction.AppForegroundChanged -> state.copy(appInForeground = action.foreground).let { updated ->
        if (!action.foreground && updated.phase == ConnectionPhase.RETRY_WAIT)
            updated.copy(retryDelayMillis = null)
        else updated
    }
}
