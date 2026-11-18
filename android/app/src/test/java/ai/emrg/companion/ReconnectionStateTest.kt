package ai.emrg.companion

import org.junit.Assert.*
import org.junit.Test

class ReconnectionStateTest {
    private val snapshot = HostSnapshot(
        protocolVersion = "1",
        authorized = true,
        sessionIds = setOf("s1"),
        activeRequestIds = setOf("r1"),
        eventCursor = "cursor-1",
    )

    @Test fun requiresAuthenticationAndSnapshotBeforeReady() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        val epoch = state.connectionEpoch
        assertEquals(ConnectionPhase.CONNECTING, state.phase)
        state = reduceReconnection(state, ReconnectionAction.Authenticated(epoch))
        assertEquals(ConnectionPhase.RECONCILING, state.phase)
        state = reduceReconnection(state, ReconnectionAction.SnapshotReceived(epoch, snapshot))
        assertEquals(ConnectionPhase.READY, state.phase)
        assertEquals(snapshot, state.snapshot)
    }

    @Test fun missingSnapshotIsUnknownAndNeverReady() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        state = reduceReconnection(state, ReconnectionAction.Authenticated(state.connectionEpoch))
        state = reduceReconnection(state, ReconnectionAction.SnapshotUnavailable(state.connectionEpoch))
        assertEquals(ConnectionPhase.STATE_UNKNOWN, state.phase)
        assertNull(state.snapshot)
    }

    @Test fun rejectsSnapshotWhenDeviceAuthorizationIsRevoked() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        state = reduceReconnection(state, ReconnectionAction.Authenticated(state.connectionEpoch))
        state = reduceReconnection(state, ReconnectionAction.SnapshotReceived(
            state.connectionEpoch, snapshot.copy(authorized = false),
        ))
        assertEquals(ConnectionPhase.AUTH_FAILED, state.phase)
        assertNull(state.snapshot)
    }

    @Test fun staleEpochEventsCannotChangeCurrentConnection() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        val oldEpoch = state.connectionEpoch
        state = reduceReconnection(state, ReconnectionAction.Connect)
        assertEquals(oldEpoch + 1, state.connectionEpoch)
        val current = state
        state = reduceReconnection(state, ReconnectionAction.Authenticated(oldEpoch))
        state = reduceReconnection(state, ReconnectionAction.ConnectionLost(oldEpoch, "stale"))
        assertEquals(current, state)
    }

    @Test fun identityAndAuthFailuresAreTerminalWithoutRetryDelay() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        state = reduceReconnection(state, ReconnectionAction.AuthenticationRejected(state.connectionEpoch))
        assertEquals(ConnectionPhase.AUTH_FAILED, state.phase)
        assertNull(state.retryDelayMillis)

        state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        state = reduceReconnection(state, ReconnectionAction.HostIdentityMismatch(state.connectionEpoch))
        assertEquals(ConnectionPhase.IDENTITY_FAILED, state.phase)
        assertNull(state.retryDelayMillis)
    }

    @Test fun retryScheduleIsBoundedAndAttemptsAreValidated() {
        assertEquals(1_000L, reconnectBackoffMillis(1))
        assertEquals(2_000L, reconnectBackoffMillis(2))
        assertEquals(4_000L, reconnectBackoffMillis(3))
        assertEquals(8_000L, reconnectBackoffMillis(4))
        assertEquals(16_000L, reconnectBackoffMillis(5))
        assertEquals(30_000L, reconnectBackoffMillis(6))
        assertEquals(30_000L, reconnectBackoffMillis(99))

        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        val epoch = state.connectionEpoch
        state = reduceReconnection(state, ReconnectionAction.RetryScheduled(epoch, 2))
        assertEquals(2, state.retryAttempt)
        assertEquals(2_000L, state.retryDelayMillis)
        val unchanged = reduceReconnection(state, ReconnectionAction.RetryScheduled(epoch, 0))
        assertEquals(state, unchanged)
    }

    @Test fun manualDisconnectPreventsRetriesAndClearsHostSnapshot() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        state = reduceReconnection(state, ReconnectionAction.Authenticated(state.connectionEpoch))
        state = reduceReconnection(state, ReconnectionAction.SnapshotReceived(state.connectionEpoch, snapshot))
        state = reduceReconnection(state, ReconnectionAction.UserDisconnect)
        assertEquals(ConnectionPhase.DISCONNECTED, state.phase)
        assertTrue(state.userDisconnected)
        assertNull(state.snapshot)
        val unchanged = reduceReconnection(state, ReconnectionAction.RetryScheduled(state.connectionEpoch, 1))
        assertEquals(state, unchanged)
    }

    @Test fun retryIsSuspendedWhenAppIsBackgrounded() {
        var state = reduceReconnection(ReconnectionState(), ReconnectionAction.Connect)
        val epoch = state.connectionEpoch
        state = reduceReconnection(state, ReconnectionAction.AppForegroundChanged(false))
        state = reduceReconnection(state, ReconnectionAction.RetryScheduled(epoch, 1))
        assertEquals(ConnectionPhase.CONNECTING, state.phase)
        assertNull(state.retryDelayMillis)
        state = reduceReconnection(state, ReconnectionAction.AppForegroundChanged(true))
        state = reduceReconnection(state, ReconnectionAction.RetryScheduled(epoch, 1))
        assertEquals(1_000L, state.retryDelayMillis)
    }
}
