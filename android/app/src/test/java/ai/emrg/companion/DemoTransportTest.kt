package ai.emrg.companion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoTransportTest {
    @Test
    fun sendReturnsExplicitDemoOnlyResponse() = runTest {
        val transport = DemoTransport()

        val response = sendSafely(transport, "hello")

        assertEquals(
            "Demo response received. Host connection and real agent execution are not yet enabled.",
            response
        )
    }

    @Test
    fun transportFailureReturnsSafeMessage() = runTest {
        val failingTransport = object : ChatTransport {
            override suspend fun send(prompt: String): String {
                throw IllegalStateException("private transport details")
            }
        }

        assertEquals(CHAT_FAILURE_MESSAGE, sendSafely(failingTransport, "hello"))
    }
}

    @Test
    fun cancellationIsNotConvertedToFailureMessage() = runTest {
        val cancelledTransport = object : ChatTransport {
            override suspend fun send(prompt: String): String {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }

        try {
            sendSafely(cancelledTransport, "hello")
            throw AssertionError("Expected cancellation to propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation must not be swallowed by the safe error handler.
        }
    }
