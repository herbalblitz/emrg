# EMRG Remote Companion Host Protocol (Draft v1)

Status: **specification only**. This document is not a running listener or a supported Android connection.
Branch: `feat/android-windows-platform-audit`.

## Design boundary

Remote clients connect to a dedicated opt-in companion listener. The listener MUST NOT bind, proxy, or expose `127.0.0.1:56031` or the daemon's desktop token-auth protocol. The desktop daemon remains loopback-only and is reached by the host service over its existing local authenticated connection.

The companion listener initially exposes only:
- authentication and protocol/capability negotiation;
- status for the authenticated device;
- session listing scoped to the approved workspace;
- chat task submission to that workspace;
- stream events for the submitted request.

No filesystem browsing, raw daemon command forwarding, tool allowlist manipulation, sandbox changes, shell execution, approvals, or destructive session operations are part of v1.

## Host opt-in and identity

- Remote access is disabled by default and must be enabled explicitly from host UI/console.
- Listener binds only to an explicitly selected interface/address; no implicit `0.0.0.0` binding.
- Host generates a self-signed or locally provisioned TLS identity and persists it with restrictive OS permissions. The host identity fingerprint is shown locally and included in the QR pairing descriptor.
- Android validates the WSS certificate chain as applicable AND the exact pinned SHA-256 leaf fingerprint before sending any credential. A changed identity requires an explicit re-pairing flow.
- Host firewall instructions/controls must be explicit; listener shutdown closes sockets and invalidates pending pairing sessions.

## Pairing window

Pairing transaction is local-host initiated, single-use and expires within 180 seconds:
1. Host generates a random transaction ID and at least 256 bits of pairing secret; stores only a verifier (not the raw secret) where feasible.
2. Host presents QR: protocol version, WSS endpoint, host ID, TLS leaf fingerprint, transaction ID, secret, expiry. QR content is never logged or persisted.
3. Android submits the secret over pinned WSS. The host limits failed attempts (maximum 5 per transaction), consumes the transaction on successful pairing, and displays device name/public-key fingerprint and requested scopes for explicit local approval.
4. Pairing does not grant access before local approval. On approval, host creates a device record with opaque ID, credential verifier, workspace ID, canonical approved cwd, scopes, expiry, created/last-used timestamps and revocation state.
5. Pairing secret and transaction are invalidated on approval, rejection, expiry, listener shutdown or attempt-limit exhaustion.

Use standard cryptographic primitives and platform TLS. Do not invent a bespoke key exchange or authentication protocol. The QR secret is short-lived bootstrap authorization only; the issued credential is device-specific.

## Credential lifecycle

- Generate a cryptographically random credential of at least 256 bits.
- Host stores a verifier (e.g. keyed hash with a host-protected secret or a secure password-hash construction appropriate for high-entropy tokens), not plaintext.
- Android stores the credential in Keystore-backed encrypted storage; exclude it from backups.
- Credential is scoped to one device, one approved workspace/cwd and an explicit expiry. Never reuse the desktop token.
- Revocation is checked for every authenticated connection and on active-session events. Revoke-device and revoke-all must terminate active sockets. Rotation invalidates the previous credential.
- Host-side device list shows name, scopes, workspace, expiry and last activity. Secrets and QR values are never shown again.

## Frames and state machine

Frames are JSON objects with a required `type` string. Reject non-object JSON, duplicate keys, any unknown fields, malformed identifiers, and frames over the configured limit (proposed 256 KiB). Unknown frame types are rejected with a generic protocol error. Strict field allowlists are intentional for v1; extensions require a protocol-versioned change. Use per-connection request IDs and monotonic event sequence numbers.

### First frame: auth

```json
{"type":"auth","protocol":"1.0","device_id":"<id>","credential":"<opaque>","client_nonce":"<random>"}
```

Host validates device, credential, expiry, revocation and protocol major version before granting access. Credential must only be sent over verified TLS. Failed auth is terminal and rate-limited; no error reveals which credential check failed.

Success response:

```json
{"type":"auth_ok","protocol":"1.0","host_id":"<id>","connection_id":"<id>","capabilities":["status","sessions.list","chat.send","chat.stream"],"workspace_id":"<id>","cwd":"<canonical approved cwd>"}
```

The Android client verifies host ID, protocol compatibility and exact workspace/cwd binding. Auth requests include a protocol version and a fresh client nonce. Capabilities are a strict allowlist; client does not infer extra rights from unknown capabilities.

### Status

Request: `{"type":"status","request_id":"<uuid>"}`

Response: `{"type":"status_result","request_id":"<uuid>","state":"ready"}`

Do not expose host environment variables, secrets, private paths beyond the approved cwd, or provider configuration.

### Session list

Request: `{"type":"sessions.list","request_id":"<uuid>","workspace_id":"<id>"}`

Response: `{"type":"sessions.list_result","request_id":"<uuid>","workspace_id":"<id>","sessions":[{"session_id":"<id>","title":"...","updated_at":"..."}]}`

Host derives/validates cwd from the device record; client cannot override it. Session IDs must be validated and associated with the approved workspace.

### Chat send

Request:

```json
{"type":"chat.send","request_id":"<uuid>","session_id":"<id-or-null>","workspace_id":"<id>","prompt":"..."}
```

Host enforces:
- device has `chat.send` scope and remains active;
- exact workspace ID and approved canonical cwd;
- prompt size limit, rate limit and concurrent-task limit;
- valid session association or creates a host-side session in the approved cwd;
- host sandbox policy and tool approval rules unchanged;
- no client-supplied tools, sandbox tier, filesystem roots, provider secrets or arbitrary daemon command.

Acknowledgement: `{"type":"chat.accepted","request_id":"<uuid>","host_request_id":"<id>","session_id":"<id>"}`

Stream events use the same request ID:
- `chat.delta`: incremental text;
- `chat.tool_start` / `chat.tool_end`: sanitized tool activity, no raw arguments or secrets;
- `chat.done`: terminal completion;
- `chat.error`: sanitized terminal error;
- `chat.cancelled`: terminal cancellation.

Only host-approved tool activity is relayed. Client disconnect does not imply task cancellation. A cancellation operation is deliberately omitted from v1 until host semantics and idempotency are specified.

## Reconciliation and replay

No automatic replay of chat-send requests on reconnect. A future status/snapshot API must be host-authoritative and scoped to the same device/workspace. Request IDs are correlation IDs, not proof of idempotency. Unknown task status after disconnect is displayed as unknown/interrupted; user must explicitly decide whether to retry.

## Operational limits (initial proposal)

- Max WebSocket frame: 256 KiB; max prompt: 64 KiB (configurable lower).
- Pairing attempts: 5 per transaction, then invalidate; rate limit by transaction and source.
- Concurrent authenticated devices and active chat requests are bounded and configurable.
- Idle timeout and absolute session lifetime; re-authenticate after credential rotation/revocation.
- No payload logging. Diagnostics may record event type, result, device ID and timing only; redact endpoint, cwd, prompt, credential and tool arguments.
- Listener shutdown drains or terminates connections according to host policy and removes all pending pairing state.

## Host credential-store primitive

`emrg/server/companion_credentials.py` now provides a file-backed registry that issues high-entropy credentials once, stores only salted SHA-256 verifiers, binds each device to workspace/cwd and an explicit scope set, supports rotation/revocation and exposes safe device metadata. It uses atomic replacement and requests POSIX-style file mode `0600`; Windows ACL hardening and integration with pairing approval/authentication are still outstanding. The registry is not currently used by a listener. Tests were added but have not yet been executed in CI.

## Pairing transaction primitive

`emrg/server/companion_pairing.py` implements a thread-safe in-memory transaction: pairing secret is returned only to the trusted local caller, expires after 180 seconds, requires explicit local approval, is redeemed once, allows at most five failed secret attempts, and can be cancelled. Successful redemption issues a workspace/cwd/scope-bound device credential through the registry. The primitive is not yet wired to local UI, TLS listener or live host authentication. Pairing secrets are not persisted. Tests have been added; execution/CI evidence is pending.

## Implementation gates

1. Threat-model/security review of pairing and host credential store.
2. Implement isolated listener module and unit tests (disabled by default).
3. Add TLS identity persistence and fingerprint UI; test identity mismatch.
4. Implement pairing approval/device management/revocation, expiry and rate limits.
5. Add daemon bridge that maps only this allowlisted API; never proxy arbitrary daemon frames.
6. Contract tests using the same JSON fixtures on Android and host.
7. Windows firewall, clean-install, shutdown/restart and network exposure tests.
8. Only then integrate Android WSS client with UI and end-to-end tests.


## Host-side parser milestone

Implemented `emrg/server/companion_protocol.py` as a strict frame parser/validation boundary with tests. It limits frame/prompt sizes, requires the auth envelope before authenticated messages, restricts message types, enforces scope/workspace for session/chat operations, and normalizes chat payloads while dropping client-supplied cwd/tools/sandbox. It does **not** verify credentials, bind sockets, dispatch requests, or provide a live remote server.

## Strict frame schema hardening

Host-side parser tests now include duplicate JSON key rejection and unknown-field rejection for the auth, status, sessions.list and chat.send frame schemas. The Android WSS client auth envelope now includes `protocol: 1.0` and a per-connection random client nonce, matching the host validator's required auth fields. This remains a client primitive/specification boundary; a live WSS host is not yet available.
