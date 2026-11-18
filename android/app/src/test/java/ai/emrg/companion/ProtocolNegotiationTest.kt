package ai.emrg.companion

import org.junit.Assert.*
import org.junit.Test

class ProtocolNegotiationTest {
    @Test fun negotiatesSupportedCapabilitiesAndIgnoresUnknownOptionalOnes() {
        val result = negotiateProtocol(ProtocolOffer(
            version = "1.2",
            capabilities = setOf("stream_deltas", "request_correlation", "future_optional"),
            requiredCapabilities = setOf("stream_deltas"),
        ))
        assertTrue(result.compatible)
        assertEquals(setOf("stream_deltas", "request_correlation"), result.acceptedCapabilities)
        assertTrue(result.unsupportedRequiredCapabilities.isEmpty())
        assertNull(result.reason)
    }

    @Test fun failsClosedForUnsupportedMajor() {
        val result = negotiateProtocol(ProtocolOffer("2.0", setOf("stream_deltas")))
        assertFalse(result.compatible)
        assertTrue(result.acceptedCapabilities.isEmpty())
        assertEquals("Unsupported protocol version", result.reason)
    }

    @Test fun failsClosedWhenHostRequiresUnknownCapability() {
        val result = negotiateProtocol(ProtocolOffer(
            version = "1",
            capabilities = setOf("stream_deltas", "execute_remote"),
            requiredCapabilities = setOf("execute_remote"),
        ))
        assertFalse(result.compatible)
        assertEquals(setOf("execute_remote"), result.unsupportedRequiredCapabilities)
        assertTrue(result.acceptedCapabilities.isEmpty())
    }

    @Test fun rejectsRequiredCapabilitiesNotIncludedInOffer() {
        val result = negotiateProtocol(ProtocolOffer(
            version = "1",
            capabilities = setOf("stream_deltas"),
            requiredCapabilities = setOf("tool_lifecycle_events"),
        ))
        assertFalse(result.compatible)
        assertEquals(setOf("tool_lifecycle_events"), result.unsupportedRequiredCapabilities)
        assertEquals("Host required capabilities were not advertised", result.reason)
    }

    @Test fun optionalUnknownCapabilitiesDoNotBreakCompatibility() {
        val result = negotiateProtocol(ProtocolOffer(
            version = "1",
            capabilities = setOf("unknown_optional"),
        ))
        assertTrue(result.compatible)
        assertTrue(result.acceptedCapabilities.isEmpty())
    }
}
