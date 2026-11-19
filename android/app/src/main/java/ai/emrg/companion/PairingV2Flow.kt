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
    fun confirmHostApproval(challenge: PairingV2Challenge): Boolean
    override fun redeemAfterHostApproval(
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
 * Host approval is deliberately not inferred here: callers may redeem only after the
 * trusted host UI has approved the transaction. Credential persistence/state transition
 * remains the responsibility of SecurePairingCoordinator after onCredential.
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
    private var hostApprovalConfirmed = false
    private var cachedRedeemFrame: JSONObject? = null

    @Synchronized
    override fun requestChallenge(listener: PairingV2FlowListener): PairingV2ExchangeHandle {
        acceptedChallenge = null
        hostApprovalConfirmed = false
        cachedRedeemFrame = null
        val request = try {
            client.createRequestFrame(pairingId)
        } catch (_: Exception) {
            listener.onFailure("PAIRING_REQUEST_INVALID")
            return PairingV2ExchangeHandle { }
        }
        return exchange.exchange(endpoint, fingerprintSha256, request, object : PairingV2ExchangeListener {
            override fun onResponse(response: JSONObject) {
                val challenge = try {
                    client.parseChallengeResponse(pairingId, response)
                } catch (_: Exception) {
                    listener.onFailure("PAIRING_CHALLENGE_INVALID")
                    return
                }
                synchronized(this@PairingV2Flow) {
                    acceptedChallenge = challenge
                    hostApprovalConfirmed = false
                    cachedRedeemFrame = null
                }
                listener.onChallenge(challenge)
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        })
    }

    /**
     * Records the user's confirmation that approval was completed in the trusted host UI.
     * The host still independently enforces approval; this flag is not proof to the server.
     */
    @Synchronized
    override fun confirmHostApproval(challenge: PairingV2Challenge): Boolean {
        if (acceptedChallenge != challenge) return false
        hostApprovalConfirmed = true
        return true
    }

    /** Redeem is unavailable until the matching challenge has explicit host-approval confirmation. */
    fun redeemAfterHostApproval(
        challenge: PairingV2Challenge,
        listener: PairingV2FlowListener,
    ): PairingV2ExchangeHandle {
        val redeem = synchronized(this) {
            if (acceptedChallenge != challenge || challenge.pairingId != pairingId) {
                listener.onFailure("PAIRING_TRANSACTION_MISMATCH")
                return PairingV2ExchangeHandle { }
            }
            if (!hostApprovalConfirmed) {
                listener.onFailure("HOST_APPROVAL_NOT_CONFIRMED")
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
