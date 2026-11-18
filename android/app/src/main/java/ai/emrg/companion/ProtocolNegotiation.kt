package ai.emrg.companion

/** A host offer received during a future explicit protocol negotiation handshake. */
internal data class ProtocolOffer(
    val version: String,
    val capabilities: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
)

internal data class ProtocolNegotiation(
    val compatible: Boolean,
    val acceptedCapabilities: Set<String>,
    val unsupportedRequiredCapabilities: Set<String> = emptySet(),
    val reason: String? = null,
)

/** Client features supported by the parser/state model; this does not imply transport availability. */
internal val CLIENT_PROTOCOL_CAPABILITIES: Set<String> = setOf(
    "request_correlation",
    "stream_deltas",
    "terminal_events",
    "tool_lifecycle_events",
    "cancellation_status",
    "unknown_optional_fields",
)

internal fun negotiateProtocol(offer: ProtocolOffer): ProtocolNegotiation {
    if (!isSupportedProtocolVersion(offer.version)) {
        return ProtocolNegotiation(false, emptySet(), reason = "Unsupported protocol version")
    }
    val requiredNotAdvertised = offer.requiredCapabilities - offer.capabilities
    if (requiredNotAdvertised.isNotEmpty()) {
        return ProtocolNegotiation(
            compatible = false,
            acceptedCapabilities = emptySet(),
            unsupportedRequiredCapabilities = requiredNotAdvertised,
            reason = "Host required capabilities were not advertised",
        )
    }
    val unsupportedRequired = offer.requiredCapabilities - CLIENT_PROTOCOL_CAPABILITIES
    if (unsupportedRequired.isNotEmpty()) {
        return ProtocolNegotiation(
            compatible = false,
            acceptedCapabilities = emptySet(),
            unsupportedRequiredCapabilities = unsupportedRequired,
            reason = "Unsupported required host capabilities",
        )
    }
    return ProtocolNegotiation(
        compatible = true,
        acceptedCapabilities = offer.capabilities.intersect(CLIENT_PROTOCOL_CAPABILITIES),
    )
}
