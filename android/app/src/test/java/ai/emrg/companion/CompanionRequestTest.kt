package ai.emrg.companion

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRequestTest {
    @Test
    fun waitsForMatchingTerminalReplyAndIgnoresProgressAndOtherRequests() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 8)

        val reply = awaitCompanionReply(frames, "wanted", 1_000) {
            assertTrue(frames.tryEmit(frame("chat.delta", "wanted")))
            assertTrue(frames.tryEmit(frame("devices.result", "another-request")))
            assertTrue(frames.tryEmit(frame("devices.result", "wanted")))
            true
        }

        assertEquals("devices.result", reply?.type)
        assertEquals("wanted", reply?.requestId)
    }

    @Test
    fun matchingErrorFrameCompletesTheRequest() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 1)

        val reply = awaitCompanionReply(frames, "failed-request", 1_000) {
            assertTrue(frames.tryEmit(frame("error", "failed-request")))
            true
        }

        assertEquals("error", reply?.type)
    }

    @Test
    fun refusedSendReturnsNullAndCancelsTheCollector() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 1)

        val reply = awaitCompanionReply(frames, "refused", 1_000) { false }

        assertNull(reply)
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test
    fun timeoutReturnsNull() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 1)

        val reply = awaitCompanionReply(frames, "timed-out", 100) { true }

        assertNull(reply)
        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test
    fun parentCancellationCancelsThePendingCollector() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 1)
        val request = launch {
            awaitCompanionReply(frames, "cancelled", 30_000) { true }
        }

        runCurrent()
        assertEquals(1, frames.subscriptionCount.value)

        request.cancelAndJoin()

        assertEquals(0, frames.subscriptionCount.value)
    }

    @Test
    fun sendExceptionPropagatesAndCancelsThePendingCollector() = runTest {
        val frames = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 1)
        val failure = IllegalStateException("synthetic send failure")
        val actual = try {
            awaitCompanionReply(frames, "send-failed", 1_000) { throw failure }
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertSame(failure, actual)
        assertEquals(0, frames.subscriptionCount.value)
    }

    private fun frame(type: String, requestId: String): ProtocolFrame =
        ProtocolFrame.fromJson(
            JSONObject()
                .put("type", type)
                .put("request_id", requestId),
        )
}
