package ai.emrg.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteConnectionActionPolicyTest {
    @Test
    fun reconnectIsDisabledWhileConnectionIsInProgressOrAuthenticated() {
        val blocked = setOf(
            RemoteSocketState.CONNECTING,
            RemoteSocketState.TLS_IDENTITY_VERIFIED,
            RemoteSocketState.AUTHENTICATING,
            RemoteSocketState.AUTHENTICATED,
        )

        RemoteSocketState.values().forEach { state ->
            assertEquals(
                "Unexpected reconnect policy for $state",
                state !in blocked,
                canReconnectRemoteHost(state),
            )
        }
    }

    @Test
    fun refreshIsEnabledOnlyAfterAuthentication() {
        RemoteSocketState.values().forEach { state ->
            assertEquals(
                "Unexpected refresh policy for $state",
                state == RemoteSocketState.AUTHENTICATED,
                canRefreshRemoteHost(state),
            )
        }
    }
}
