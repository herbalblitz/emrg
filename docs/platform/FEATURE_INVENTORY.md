# EMRG Feature Inventory

Test counts are measured, never stored: `uv run --no-sync python3 scripts/check-doc-count.py --measure`.

Source-tree audit inventory. Source-inspected does not mean runtime-tested.

## Core runtime
- [ ] Agent orchestration/providers
- [x] Daemon WebSocket transport/local auth inspected
- [ ] Lifecycle/reconnect end-to-end tests
- [x] Protocol dataclasses and GUI response-type map partially reviewed
- [x] Android protocol fixtures, frame parser, request-stream reducer, validated task/session command builders added (CI validation pending). Session schemas documented; Android transport not connected.
- [ ] Sessions, tools, approvals, workspace, sandbox
- [ ] Memory, skills, subagents, scheduler, evolution
- [ ] Logs/diagnostics, CLI/TUI

## Desktop GUI (Electron)
- [ ] Onboarding/config; chat/composer; Ask/Auto; streaming/stop
- [ ] Sessions/results/settings/approvals
- [ ] Workspace/file editor; memory/skills/tasks/evolution
- [ ] GitHub/device login; accessibility/localization/themes

## Windows
- [x] Platform-specific shell/daemon branches located in repository
- [ ] Startup/wrappers/process shutdown/restart verified
- [x] Release packaging source and Windows Inno Setup compile smoke test baseline inspected (not clean-install proof)
- [ ] Runtime packaging, installer/PATH/shortcuts/uninstall
- [ ] Sandbox, upgrades/rollback, clean-machine smoke, CI artifact/checksum

## Android
- [x] Native Kotlin/Compose scaffold with interactive mock chat, send/receive and demo/offline state (no real host connection).
- [x] Transport interface includes stream callback; demo mock emits cancellable chunks; Compose renders interim text and Stop cancels request coroutine (CI passed in run `44239121243`). 
- [x] Mock transport extracted with tests for demo response and transport failure fallback (CI passed)
- [x] Chat state reducer tests include partial deltas, completion, cancellation preserving partial text and stale-response guards (CI passed in run `44239121243`).
- [x] Activity saved-state snapshot restores chat messages/draft, caps saved history to 60, clears transient sending state.
- [x] Device-local private SharedPreferences snapshot persists chat history/draft across app restarts; malformed snapshot falls back to fresh preview state (CI passed).
- [x] Safe chat error fallback preserves coroutine cancellation (tests added; CI passed)
- [x] CI workflow committed; debug APK build, lint, and unit tests passed in Actions for streaming integration run `44239121243` (`bc1d3bf2`); markdown renderer CI pending
- [x] WSS client primitive with system TLS + pinned host certificate fingerprint, auth-envelope send, protocol/workspace-bound auth-success validation, chat-only scoped frame construction and connection generation guards; dedicated opt-in host WSS listener implements device authentication, scoped status and bounded read-only session metadata dispatch.
- [x] Credential-safe QR descriptor parsing: WSS-only, strict allowlist, mandatory <=180-second expiry; endpoint query/user-info/fragment and invalid ports rejected.
- [x] Electron CompanionPanel exposes explicit listener start/stop, QR display/copy, pending device approvals and approve/reject through guarded named IPC (renderer tests, TypeScript check and production build passed in CI #44659155014).
- [ ] Registered-device revocation/management UI, Android authenticated status/session-list UI operations, physical-device Keystore validation and cross-platform end-to-end validation remain open.
- [x] Android authenticated WSS client matches the host's `auth.ok` response and requires exact approved workspace/scopes (regression tests added; CI pending).
- [x] Compose offline session UI: list/select/create/rename/delete, isolated per-session messages/drafts (CI passed).
- [x] Protocol frame parsing + request-stream state reducer for streaming/tool lifecycle/cancel/error/connection-loss (CI passed in run `44239121243`); no real daemon transport yet.
- [x] Offline UI integration for response streaming, partial text display and Stop/cancel action (no live transport; CI passed in run `44239121243`).
- [x] Tool Activity component wired into transient chat state and Compose rendering; lifecycle reducer tests added (CI pending; protocol events not yet connected to live transport).
- [ ] Daemon session synchronization; tool approval UI
- [ ] SAF workspace; memory; skills; tasks; evolution
- [ ] Opt-in notifications; settings/export; accessibility/offline states

## Audit docs
- `PROJECT_AUDIT.md`
- `FEATURE_INVENTORY.md`
- `ROADMAP.md`
- `DAEMON_PROTOCOL_AND_ANDROID_SECURITY.md`
- `NEXT_GENERATION_REBUILD_POLICY.md`
- `CLIENT_PROTOCOL_CONTRACT.md`
- `WINDOWS_PACKAGING_AUDIT.md`
- `ANDROID_REMOTE_PAIRING_SECURITY_DESIGN.md`

- [x] Source-verified session command/reply schemas documented; protocol transport mismatch corrected in module header.

Status: **Source-inspected** = checked code; **Implemented** = code exists; **Tested** = test actually executed and passed.

## Companion security increment

- [x] Host frame schema strictness: duplicate JSON keys, unknown fields and invalid Unicode fail closed; host parser tests passed in CI #44659155014 on Ubuntu and Windows.
- [x] Host v1 and pairing-v2 validators enforce UTF-8-only byte payloads (UTF-16/32 rejected); regression tests passed in CI #44659155014 on Ubuntu and Windows.
- [x] WSS listener emits JSON text frames for auth/status/session/pairing responses; mock transport tests reject accidental binary frames (CI #44659155014: passed on Ubuntu and Windows).
- [x] Registry same-instance thread serialization for credential issue/revoke/read; concurrent-issuance test added. Cross-process lock and Windows ACL hardening are open.
- [x] Host TLS identity generation/persistence, protected TLS context loading, daemon-owned opt-in WSS lifecycle, and transaction-bound v2 QR descriptor creation are integrated in source; latest host security CI #44659155014: passed on Ubuntu and Windows.
- [x] Trusted local React CompanionPanel approval/QR UI is wired through named IPC (renderer CI #44659155014: passed); [ ] registered-device management UI and Android-to-Windows live pairing E2E remain open.

## Host TLS identity (implementation status)

- [x] P-256 self-signed certificate generation, exact SAN host binding, SHA-256 fingerprint, bounded validity and certificate-purpose validation.
- [x] Stable identity persistence; no silent regeneration on missing/corrupt state; explicit reset/rotation.
- [x] Windows DPAPI-protected private key and POSIX restrictive file modes; atomic writes.
- [x] TLS context loader, TLS 1.2 minimum, no socket creation; process-local provisioning/context lock.
- [x] Python TLS identity tests cover persistence, corruption, SAN mismatch, key mismatch, atomicity, and DPAPI-backed key file selection (cross-platform CI previously passed).
- [ ] Latest daemon runtime + QR v2 tests/CI and Windows DPAPI/ACL review remain open.
- [ ] Android trust strategy for self-signed pins, local host UI confirmation, QR generation and live listener integration.

## Authentication replay defense

- [x] Require client auth nonce minimum length 16 bytes.
- [x] Add process-local per-device replay cache with 10-minute expiry, capacity bound and thread-safe atomic consume.
- [x] Duplicate nonce and cache exhaustion reject authentication.
- [ ] Decide restart persistence or challenge-response requirements and implement transport handshake before production listener.

## Companion TLS trust and auth nonce guard

- [x] Android per-connection exact leaf SHA-256 pin trust manager supports pinned self-signed host certificates; validity dates are enforced during TLS handshake and default hostname verification remains active.
- [x] Host fingerprint string is colon-delimited uppercase bytes for Android QR/parser compatibility.
- [x] Host auth nonce is minimum 16 bytes and consumed once per device with bounded 10-minute process-local replay cache; capacity exhaustion fails closed.
- [x] Android pinned-certificate trust and pairing transport build/JVM tests are covered by Android CI; [ ] trusted-local host approval UI, physical-device Keystore validation, and real host pairing E2E remain open.

## Host QR descriptor generation

- [x] Generate Android-compatible, credential-free v1 pairing descriptor with WSS endpoint and SHA-256 pin.
- [x] Reject endpoint host outside certificate SAN and invalid/insecure endpoint forms.
- [x] Enforce QR expiry at no more than 180 seconds.
- [x] Daemon runtime now starts an explicit IP-bound listener, opens a status-only Pairing v2 transaction, and returns a short-lived v2 QR descriptor containing the pairing ID; runtime tests are included in Companion Security CI.
- [x] Integrate v2 descriptor display/copy and pending approval/rejection into the React CompanionPanel (renderer CI #44659155014: passed). [ ] Chat/session UI dispatch remains limited to the existing scoped read-only host operations.


## Companion listener safety primitives

- [x] Host Companion defaults disabled; explicit enable gate protects pairing and auth entry points, and disable invalidates outstanding pairing transactions.
- [x] Listener configuration validation rejects hostname/DNS inputs, wildcard binds, multicast/reserved addresses, privileged/out-of-range ports, and unbounded resource/timeout settings.
- [x] Dedicated opt-in WSS listener binds only after explicit host enable; TLS context loads before bind, frame/connection/timeouts bounded, authentication required as first frame, stop closes clients, concurrent starts are rejected, and disable/stop during asynchronous bind closes the late server instead of resurrecting it.
- [x] Host-service disable lifecycle observer stops the listener, closes active WSS clients and invalidates pending pairings; transport tests cover auth + scoped status and disable shutdown.
- [x] Read-only `sessions.list` WSS dispatch uses the approved credential's stored workspace path (never a client-supplied cwd), requires the `sessions.list` scope and returns correlated, allowlisted metadata (max 200 records; bounded text and message counts); tests cover workspace binding, data redaction and unauthorized dispatch.
- [x] Local pairing transaction bounds workspace/device/secret inputs, rejects malformed UTF-8 and invalid scope collections, and validates approval/cancellation IDs and strict boolean decisions; regression tests cover malformed inputs and one-time approval.
- [ ] Chat dispatch into daemon runtime, authenticated Android status/session UI, and full end-to-end remain open.

- [x] Active WSS device sessions revalidate the authenticated device's current registry authorization before each request; revocation and credential rotation invalidate existing connections, and registry read failures fail closed. Regression tests cover credential rotation on an already-open socket.
- [x] WSS session listing returns only bounded, allowlisted metadata; local cwd, transcripts, and arbitrary meta fields are excluded.


## Trusted local Companion control bridge

- [x] Daemon owns a disabled-by-default `CompanionHostService` and an allow-listed `CompanionLocalControl` facade; credential registry path is daemon-owned.
- [x] Authenticated daemon command routes only `status`, `pairing.pending.list`, `pairing.decide`, and `pairing.cancel`; unknown operations and malformed payloads fail closed.
- [x] Electron Main exposes fixed status/pending/decision IPC handlers and validates primary BrowserWindow, top-level frame, and local `file://` origin.
- [x] Preload exposes only named Companion methods; preload API contract test updated.
- [x] Daemon owns explicit listener start/stop, TLS identity provisioning, and v2 QR descriptor generation; Electron Main/Preload expose fixed guarded lifecycle IPC methods.
- [x] React renderer CompanionPanel implements explicit host start/stop, QR descriptor display/copy, pending approval list and approve/reject interactions (renderer CI #44659155014: passed).
- [x] Static Electron IPC tests cover the primary top-level local renderer guard for status, approval, and listener lifecycle handlers; daemon tests cover listener start/stop routing.
- [ ] Full Electron↔daemon runtime tests, registered-device revoke UI, physical Android Keystore checks, and live Android-to-Windows pairing remain open. This is not yet a completed end-to-end pairing implementation.

## Pairing v2 host transaction core

- [x] Host trusted-local pending v2 approval listing is bounded to display/decision metadata and SHA-256 public-key fingerprint; tests assert raw public key, client nonce, challenge bytes, and issued credential are not exposed, and redeemed transactions leave the pending queue. Companion security CI #182: passed on Ubuntu and Windows.

- [x] Transport-independent Ed25519 device-key-bound pairing transaction with host-local approval, signed challenge fields, one-time redemption, bounded lifetime and host-service lifecycle hooks.
- [x] Unit tests cover approval gating, device/key proof binding, wrong-device and wrong-signature rejection, duplicate request/approval, expiry, cancellation and credential scope issuance.
- [x] Added `tests/test_companion_pairing_v2.py` to the companion-security workflow's explicit pytest invocation; CI run [#111](https://github.com/herbalblitz/emrg/actions/runs/44447348538) passed with the initial five v2 tests, then CI run [#112](https://github.com/herbalblitz/emrg/actions/runs/44447421797) passed with all seven tests, including concurrent one-time redemption and the 32-entry pending-cap/cancel-all regression.
- [x] Android WSS request/redeem flow, device-key proof, coordinator validation, and encrypted credential persistence are integrated in the Android pairing path. The host trusted-local approval UI and cross-platform end-to-end validation remain open.


## Android encrypted credential envelope

- [x] Replaced permissive envelope parsing with strict canonical standard-Base64 validation; exact separator count, 12-byte IV and minimum 16-byte AES-GCM ciphertext are enforced.
- [x] Added platform-independent JVM regression tests for canonical round-trip, malformed separators/whitespace, invalid lengths and noncanonical Base64.
- [x] Android Companion CI #206: passed APK assembly, unit tests, lint and artifact upload.
- [ ] Validate actual Android Keystore encryption/decryption and key deletion on supported physical Android devices; JVM CI does not exercise the platform Keystore provider.


### Android credential issuance and persistence bounds

- [x] Enforce the host registry's 512-byte UTF-8 credential maximum at the Android coordinator and encrypted store boundary.
- [x] Explicitly require randomized AES-GCM encryption in the Android Keystore key specification.
- [x] Add JVM boundary tests for blank, exact-limit, oversized ASCII, multibyte UTF-8, and no-persistence-on-rejection cases.
- [ ] Await Android CI for source/test commits `f137081b` through `69ca6a82`; the queued latest run must pass before this slice is CI-verified.
- [ ] Exercise real Android Keystore put/get/delete on supported physical devices.


## Pairing v2 Android WSS transport status (2026-11-19)

- [x] One-shot Android WSS exchange primitive uses the QR-pinned TLS leaf certificate before sending the Pairing v2 request or redeem frame.
- [x] Response envelope is size-bounded and correlated to pairing ID, expected response type, and device ID for credential issuance.
- [x] JVM regression tests and Android CI #267: passed on commit `2fbdf7d0d92c` (APK assembly, unit tests, lint, artifact upload).
- [x] Production Android-side integration with `PairingV2Client`, `SecurePairingCoordinator`, host challenge/redeem lifecycle, and QR UI is present in source. This does not establish a completed end-to-end pairing.
- [ ] Physical-device Android Keystore validation and full Android-to-Windows host integration remain open.

## Pairing v2 Android integration status (2026-11-19)

Implemented in source:
- `PairingV2Flow` composes the signed Pairing v2 client with the pinned WSS exchange and exposes immutable transport identity metadata.
- `PairingV2SessionController` validates that QR/session metadata (WSS endpoint, TLS fingerprint, pairing ID, device ID, and scopes) exactly matches the transport that created the signed request.
- Host-local approval remains the only approval authority. A generic unavailable response leaves Android in `AWAITING_HOST_APPROVAL`; retry reuses the exact signed redeem frame.
- A credential is reported as paired only after a valid host credential response is accepted by the coordinator and secure credential storage succeeds.
- The current QR panel requests only the read-only `status` scope. `chat.send` is intentionally not enabled.

Not yet complete:
- QR scanner/panel is wired to `PairingV2SessionController` with explicit fingerprint confirmation and lifecycle-safe cancellation/retry.
- No physical-device Android Keystore validation or Android ↔ Windows end-to-end pairing has been completed.
- The panel currently requests only read-only `status`; authenticated status/session-list UI operations and remote chat/tool execution remain unavailable.


## Pairing v2 UI integration status (2026-11-19)

`SecurePairingPanel.kt` now connects the QR camera/paste flow to the v2 descriptor parser and `PairingV2SessionController`. It presents the endpoint and host SHA-256 fingerprint for explicit confirmation, then drives the challenge, signed proof, manual retry, and paired callback lifecycle. The active exchange is cancelled when the panel leaves composition. The host's trusted-local approval remains authoritative.

Verification: Android CI run `44503538472` on commit `7df4eeb924e7a36e55b653e4ab77ddefa4a7c6de` passed APK assembly, JVM unit tests, lint, and APK artifact upload.

Still not verified: physical-device Android Keystore behavior, a real Windows-host QR/approval/WSS/credential end-to-end flow, and live status/session-list UI operations. The current panel requests only `status`; chat sending and tool execution are not enabled by this work. A successful CI build is not evidence of device or cross-platform runtime validation.


### Android pairing retry and callback lifecycle hardening (2026-11-19)

- Pairing challenge retries now retransmit the exact original request frame and client nonce; the host returns the original challenge only for an exact match.
- The pairing panel ignores callbacks queued by an older QR transaction after reset or rescan, preventing stale network responses from replacing the current UI state.
- Android workflow run `44508660928` passed APK assembly, unit tests, lint, and artifact upload for the retry-frame change. Run `44509690853` also passed APK assembly, unit tests, lint, and artifact upload for stale-callback isolation (`0a587371540cb8c49072cd1b063fad160605712e`).
- Remaining validation: physical Android Keystore/device test and live Windows-host pairing end-to-end test.


### Pairing state concurrency regression follow-up (2026-11-19)

- [x] Synchronize coordinator state reads with reducer/store writes.
- [x] Read pairing retry eligibility and failure code from one immutable coordinator snapshot.
- [x] Add concurrent duplicate-credential callback regression coverage; CI verification is pending for the test-only follow-up commit.
- [ ] Physical Android Keystore validation and real Windows-host pairing end-to-end validation.
