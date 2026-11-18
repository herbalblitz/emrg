package ai.emrg.companion

/**
 * Transport boundary for the Android chat screen.
 * This demo implementation is local-only until secure pairing is reviewed.
 */
internal interface ChatTransport {
    suspend fun send(prompt: String): String

    suspend fun stream(prompt: String, onDelta: (String) -> Unit): String {
        val result = send(prompt)
        onDelta(result)
        return result
    }
}

/** Deterministic mock used by the UI preview and unit tests. */
internal class DemoTransport : ChatTransport {
    override suspend fun send(prompt: String): String =
        "Demo response received. Host connection and real agent execution are not yet enabled."
}

internal const val CHAT_FAILURE_MESSAGE =
    "Unable to complete the request. Please try again."

/** Converts transport exceptions into a user-safe chat response. */
internal suspend fun sendSafely(transport: ChatTransport, prompt: String): String =
    try {
        transport.send(prompt)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CHAT_FAILURE_MESSAGE
    }
