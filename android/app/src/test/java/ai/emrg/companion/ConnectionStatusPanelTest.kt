package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusPanelTest {
    @Test
    fun disconnectedStatusDoesNotImplyHostConnection() {
        val state = ReconnectionState()
        assertEquals("Disconnected", connectionPhaseLabel(state.phase))
        assertEquals("No daemon connection. Pairing and transport are not active.", connectionPhaseDescription(state))
    }

    @Test
    fun readyStatusExplainsSnapshotGate() {
        val state = ReconnectionState(phase = ConnectionPhase.READY)
        assertEquals("Ready", connectionPhaseLabel(state.phase))
        assertEquals(
            "Host snapshot reconciled. Controls may be enabled by a future transport integration.",
            connectionPhaseDescription(state)
        )
    }

    @Test
    fun unknownAndIdentityFailureAreExplicitlyFailClosed() {
        assertEquals(
            "Host state unknown",
            connectionPhaseLabel(ReconnectionState(phase = ConnectionPhase.STATE_UNKNOWN).phase)
        )
        assertTrue(connectionPhaseDescription(ReconnectionState(phase = ConnectionPhase.STATE_UNKNOWN))
            .contains("do not retry non-idempotent actions"))
        assertEquals(
            "Host identity mismatch",
            connectionPhaseLabel(ReconnectionState(phase = ConnectionPhase.IDENTITY_FAILED).phase)
        )
    }

    @Test
    fun retryDelayIsPresentedWithoutInventingAnActiveConnection() {
        val state = ReconnectionState(phase = ConnectionPhase.RETRY_WAIT, retryDelayMillis = 4_000)
        assertEquals("Waiting to retry", connectionPhaseLabel(state.phase))
        assertEquals("Retry scheduled in 4 seconds.", connectionPhaseDescription(state))
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
