# Companion Host Integration Boundary Audit

Branch: feat/android-windows-platform-audit

Status: source audit complete; host-side pairing/auth composition exists, but trusted local UI and remote host transport are not implemented.

## Verified existing daemon boundary

The daemon's current WebSocket listener is created in EmrgServer.serve() using a fixed-port socket bound explicitly to 127.0.0.1. It authenticates the first frame against the daemon token and then routes a broad existing desktop protocol through EmrgServer._handle_client. That protocol includes task execution, session control, approval responses, cancellation, and other daemon commands.

**Do not change this listener to bind LAN interfaces.** Do not reuse its daemon token, auth envelope, or unrestricted handler as the Android companion endpoint. The desktop daemon token is a local IPC credential, not a mobile device credential.

## Integration constraints confirmed from source

1. Keep the existing fixed-port loopback socket and local token lifecycle unchanged.
2. A future companion listener must be a separate opt-in listener with its own configuration, port, TLS identity, credential validation, connection set, and shutdown lifecycle.
3. Companion frames must pass validate_client_frame before any operation; credentials must be checked against the registry and device scopes/workspace binding.
4. Do not forward remote frames to _handle_client or accept legacy desktop task messages. Remote requests need a small adapter with a dedicated allowlist.
5. Remote chat must construct host-side task/session inputs itself. Never accept client cwd, tools, sandbox mode, writable roots, provider credentials, or arbitrary command fields.
6. Status and session listing must be read-only, workspace-scoped, and have bounded output. Chat execution must use the host's existing session and sandbox policy and preserve host-side approval semantics.
7. Pairing begin/approve/reject and pairing-secret display must be callable only from a trusted local UI/console path. Redemption is network-facing but must require an approved transaction and must consume it exactly once.
8. TLS must use an explicit host identity and a reviewed certificate provisioning/rotation strategy. Android's pin must be tied to that identity. A self-signed certificate is not acceptable merely because the client can pin it; the user needs a trustworthy out-of-band verification path.
9. Revocation must remove credentials and close active connections associated with that device. Listener disable/shutdown must invalidate pending pairings and close remote sockets.
10. The integration must have separate tests proving the current listener remains loopback-only and the remote listener defaults disabled. Add cross-platform socket/TLS tests before exposing a LAN option.

## Current code-level progress

- `CompanionHostService` composes the credential registry, pairing transactions and auth gate.
- It provides local operations to begin pairing, inspect safe pending metadata, approve/reject/cancel, list and revoke devices.
- Transport-independent operations redeem pairing and authenticate the first frame.
- Tests cover the approved pairing-to-auth path and reject/revoke behavior.
- The service opens no sockets. Access control for local UI callers is not enforced by this Python class.

## Suggested implementation sequence

- H1: Implement host-side pairing manager composition and a trusted local approval interface, without opening a socket.
- H2: Define TLS identity persistence, rotation, and user-verifiable fingerprint presentation. **Design specification committed in `COMPANION_TLS_HOST_IDENTITY_POLICY.md`; implementation remains open.**
- H3: Implement a disabled-by-default dedicated WSS listener and narrow protocol adapter, initially status-only.
- H4: Add scoped session listing, then chat with host-side task construction and approval/sandbox enforcement.
- H5: Test device revocation, pending-pairing expiry, listener disable, reconnect reconciliation, rate limits, malformed frames, and Android-to-host end-to-end flows on Windows and Linux.

No milestone above is complete until tests and CI evidence exist.

## H2 policy document

`COMPANION_TLS_HOST_IDENTITY_POLICY.md` now defines the host certificate identity, DER fingerprint format, private-key persistence and access protection, QR out-of-band trust, explicit rotation/recovery, listener enablement gate, shutdown/revocation behavior, and acceptance tests. It is a design gate only; no key generation or listener code was added in this increment.


## TLS primitive implementation increment

`emrg/server/companion_tls.py` adds transport-free helpers for SHA-256 fingerprinting over DER bytes, fingerprint normalization/validation, constant-time pin comparison, and a TLS 1.2+ server context that loads a supplied certificate/key pair fail-closed. It does not generate or persist identity material and does not create sockets. Unit coverage is in `tests/test_companion_tls.py`; CI verification is pending.
