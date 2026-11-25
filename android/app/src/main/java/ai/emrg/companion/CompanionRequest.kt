package ai.emrg.companion

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.onAwait
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Correlate one request with its terminal reply without losing a fast response.
 *
 * The frame collector and connection-epoch watcher are subscribed before [send]
 * runs. Only a result/error carrying the same request ID completes the wait.
 * A disconnect/reconnect epoch change, refused send, or timeout returns null.
 * Send exceptions and parent cancellation propagate after both child collectors
 * have been cancelled and joined.
 */
internal suspend fun awaitCompanionReply(
    frames: Flow<ProtocolFrame>,
    requestId: String,
    timeoutMs: Long,
    connectionEpoch: StateFlow<Long>,
    expectedEpoch: Long,
    send: () -> Boolean,
): ProtocolFrame? = coroutineScope {
    require(timeoutMs > 0) { "timeout must be positive" }
    if (connectionEpoch.value != expectedEpoch) return@coroutineScope null

    val waiting = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeoutMs) {
            frames.first { frame ->
                frame.requestId == requestId &&
                    (frame.type?.endsWith(".result") == true || frame.type == "error")
            }
        }
    }
    val invalidated = async(start = CoroutineStart.UNDISPATCHED) {
        connectionEpoch.first { it != expectedEpoch }
    }

    try {
        if (connectionEpoch.value != expectedEpoch || !send()) return@coroutineScope null
        select<ProtocolFrame?> {
            // Prefer invalidation if a disconnect and a late frame become ready together.
            invalidated.onAwait { null }
            waiting.onAwait { reply ->
                if (connectionEpoch.value == expectedEpoch) reply else null
            }
        }
    } finally {
        withContext(NonCancellable) {
            waiting.cancelAndJoin()
            invalidated.cancelAndJoin()
        }
    }
}
