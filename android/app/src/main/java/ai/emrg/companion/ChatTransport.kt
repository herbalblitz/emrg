package ai.emrg.companion

/**
 * Transport boundary for the Android chat screen.
 * This demo implementation is local-only until secure pairing is reviewed.
 */
internal interface ChatTransport {
    suspend fun send(prompt: String): String
}

/** Deterministic mock used by the UI preview and unit tests. */
internal class DemoTransport : ChatTransport {
    override suspend fun send(prompt: String): String =
        "Demo response received. Host connection and real agent execution are not yet enabled."
}
