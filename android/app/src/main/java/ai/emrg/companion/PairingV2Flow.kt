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
) {
    fun requestChallenge(listener: PairingV2FlowListener): PairingV2ExchangeHandle {
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
                listener.onChallenge(challenge)
            }

            override fun onFailure(safeCode: String) = listener.onFailure(safeCode)
        })
    }

    /** Invoke only after the host has approved locally; this method cannot approve a request. */
    fun redeemAfterHostApproval(
        challenge: PairingV2Challenge,
        listener: PairingV2FlowListener,
    ): PairingV2ExchangeHandle {
        if (challenge.pairingId != pairingId) {
            listener.onFailure("PAIRING_TRANSACTION_MISMATCH")
            return PairingV2ExchangeHandle { }
        }
        val redeem = try {
            client.createRedeemFrame(pairingId, challenge.challengeId, challenge.encodedChallenge)
        } catch (_: Exception) {
            listener.onFailure("PAIRING_REDEEM_INVALID")
            return PairingV2ExchangeHandle { }
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
