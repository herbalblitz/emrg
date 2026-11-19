package ai.emrg.companion

import org.json.JSONObject

/** Injectable boundary keeps the protocol orchestration testable without opening a socket. */
internal interface PairingV2FrameExchange {
    fun exchange(
        endpoint: String,
        fingerprintSha256: String,
        requestFrame: JSONObject,
        listener: PairingV2ExchangeListener,
    ): PairingV2ExchangeHandle
}

internal interface PairingV2SessionTransport {
    val requestedScopes: Set<String>
    fun requestChallenge(listener: PairingV2FlowListener): PairingV2ExchangeHandle
    fun redeem(
        challenge: PairingV2Challenge,
        listener: PairingV2FlowListener,
    ): PairingV2ExchangeHandle
}

internal interface PairingV2FlowListener {
    fun onChallenge(challenge: PairingV2Challenge)
    fun onCredential(credential: String)
    fun onFailure(safeCode: String)
}

/**
 * Composes the device-key protocol helper with the pinned one-frame WSS transport.
 *
 * Host approval is enforced by the host server, never inferred from Android UI state.
 * Credential persistence/state transition remains the responsibility of
 * SecurePairingCoordinator after a validated credential response.
 */
internal class PairingV2Flow(
    private val endpoint: String,
    private val fingerprintSha256: String,
    private val pairingId: String,
    private val client: PairingV2Client,
    private val exchange: PairingV2FrameExchange,
) : PairingV2SessionTransport {
    override val requestedScopes: Set<String> get() = client.requestedScopes
    private var acceptedChallenge: PairingV2Challenge? = null
    private var cachedRedeemFrame: JSONObject? = null

    @Synchronized
    override fun requestChallenge(listener: PairingV2FlowListener): PairingV2ExchangeHandle {
        acceptedChallenge = null
        cachedRedeemFrame = null
        val request = try {
            client.createRequestFrame(pairingId)
        } catch (_: Exception) {
            listener.onFailure("PAIRING_REQUEST_INVALID")
            return PairingV2ExchangeHandle { }
        }
        return exchange.exchange(endpoint, fingerprintSha256, request, object : PairingV2ExchangeListener {
            override fun onResponse(response: JSONObject) {
                if (response.opt("type") == "pairing.error") {
                    listener.onFailure("PAIRING_UNAVAILABLE")
                    return
                }
                val challenge = try {
                    client.parseChallengeResponse(pairingId, response)
                } catch (_: Exception) {
                    listener.onFailure("PAIRING_CHALLENGE_INVALID")
                    return
                }
                synchronized(this@PairingV2Flow) {
                    acceptedChallenge = challenge
                    cachedRedeemFrame = null
                }
                listener.onChallenge(challenge)
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        })
    }

    /**
     * Sends the signed proof for the accepted challenge. The host is the sole approval
     * authority and returns only a generic unavailable response until its local approval.
     * Repeated calls reuse the exact same signed frame for idempotent host redemption.
     */
    override fun redeem(
        challenge: PairingV2Challenge,
        listener: PairingV2FlowListener,
    ): PairingV2ExchangeHandle {
        val redeem = synchronized(this) {
            if (acceptedChallenge != challenge || challenge.pairingId != pairingId) {
                listener.onFailure("PAIRING_TRANSACTION_MISMATCH")
                return PairingV2ExchangeHandle { }
            }
            cachedRedeemFrame?.let { JSONObject(it.toString()) } ?: try {
                client.createRedeemFrame(pairingId, challenge.challengeId, challenge.encodedChallenge)
                    .also { cachedRedeemFrame = JSONObject(it.toString()) }
            } catch (_: Exception) {
                listener.onFailure("PAIRING_REDEEM_INVALID")
                return PairingV2ExchangeHandle { }
            }
        }
        return exchange.exchange(endpoint, fingerprintSha256, redeem, object : PairingV2ExchangeListener {
            override fun onResponse(response: JSONObject) {
                if (response.opt("type") == "pairing.error") {
                    listener.onFailure("PAIRING_UNAVAILABLE")
                    return
                }
                val credential = try {
                    client.parseCredentialResponse(pairingId, response)
                } catch (_: Exception) {
                    listener.onFailure("PAIRING_CREDENTIAL_INVALID")
                    return
                }
                listener.onCredential(credential)
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        })
    }
}
