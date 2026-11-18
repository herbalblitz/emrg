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
## Strict frame-schema hardening increment

The companion frame validator now rejects duplicate JSON object keys and unknown fields in every currently defined client message type (auth, status, sessions.list, chat.send). This prevents ambiguous parser interpretations and fails closed on unsupported request extensions instead of passing them to future handlers. Chat frames continue to be normalized to the narrow host-approved fields. Regression tests cover duplicate keys, extra auth/status fields, and privilege-shaped chat extensions. This remains parser-only hardening; no transport or dispatcher is implemented.

## Android companion frame-construction hardening

The Android WSS primitive now emits the v1 auth envelope with protocol version and a per-connect client nonce. Chat submission converts the legacy local TaskCommand into the narrow companion `chat.send` frame (request ID, approved workspace ID, prompt, optional session ID); it does not forward cwd, tools, timestamp or desktop `task` command fields. Client-side UTF-8 size/identifier bounds mirror the host parser. JVM regression tests were added; execution is pending CI.

## Host parser malformed-Unicode handling

The Python frame parser now converts invalid UTF-8 / lone-surrogate encoding failures into the same safe protocol rejection rather than allowing an encoding exception to escape. Tests exercise malformed raw string input and JSON-escaped surrogate data in validated fields. No raw frame or credential is included in the error.

## Registry concurrent-write handling

The credential registry now serializes read-modify-write issue/revoke operations and reads with a per-instance thread lock, preventing lost updates between concurrent host-service threads using the same registry instance. A regression test issues credentials concurrently for multiple devices and verifies all entries remain. This is process-local serialization only; cross-process file locking and Windows ACL hardening remain open requirements before listener integration.

## Credential registry filesystem permission increment

On POSIX, the registry now requests mode `0700` for a newly created containing directory and `0600` for the atomic temporary file before replacement. A test checks the effective directory/file modes. Existing directory permissions are not silently changed, and Windows ACL protection is still not implemented; do not treat this file registry as production-ready across platforms yet.

## TLS host identity provisioning implementation

Added `emrg/server/companion_tls_identity.py`: creates a dedicated P-256 self-signed certificate with exact SAN host coverage, server EKU/basic constraints/key usage, SHA-256 DER fingerprint, and 365-day validity. It persists across restart, validates key/certificate match and signature/usage/expiry/SAN, and never silently replaces a missing/corrupt identity; explicit reset is required for rotation. Windows private-key bytes are user-DPAPI protected; POSIX files are atomic and mode 0600 under a requested mode-0700 identity directory. A context loader uses TLS 1.2+ and opens no socket. Tests are added; execution is pending. Host UI consent, cross-process lock, Windows ACL/temp-file review and live listener remain gates.

## Android exact-pin TLS and nonce replay defense

The Android client now installs a per-connection exact SHA-256 leaf-pin trust manager for self-signed host certs; certificate validity is checked during TLS handshake, and default hostname verification remains enabled. Host provisioner fingerprint format is colon-delimited uppercase hex, matching Android QR parser. Unit tests cover trust pin match/mismatch/missing chain/invalid pin. Host auth now rejects nonce values under 16 bytes and uses a process-local 10-minute bounded, synchronized replay cache; duplicate nonce and cache saturation fail closed. Linux and Windows CI passed the host tests (70 passed on Linux; 67 passed/3 skipped on Windows). Android test CI is pending at this revision.
