package ai.emrg.companion

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Correlate one request with its terminal reply without losing a fast response.
 *
 * The collector is subscribed before [send] runs. Only a result/error carrying
 * the same request ID completes the wait; progress and unrelated replies are
 * ignored. A refused send or timeout returns null. Parent cancellation and
 * exceptions from [send] propagate, and the child collector is always cancelled.
 */
internal suspend fun awaitCompanionReply(
    frames: Flow<ProtocolFrame>,
    requestId: String,
    timeoutMs: Long,
    send: () -> Boolean,
): ProtocolFrame? = coroutineScope {
    val waiting = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeoutMs) {
            frames.first { frame ->
                frame.requestId == requestId &&
                    (frame.type?.endsWith(".result") == true || frame.type == "error")
            }
        }
    }
    if (!send()) {
        waiting.cancel()
        return@coroutineScope null
    }
    waiting.await()
}
