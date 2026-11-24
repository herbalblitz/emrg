# EMRG Feature Inventory

Test counts are measured, never stored: `uv run --no-sync python3 scripts/check-doc-count.py --measure`.

Source-tree audit inventory. Source-inspected does not mean runtime-tested.

## Current Android/Windows pairing snapshot (2026-11-23; supersedes older milestone notes below)
- Fresh pairing requests only read-only `status` by default. All other requestable scopes are explicit opt-ins; the host's configured scope ceiling and local approval remain authoritative.
- Re-pair preselects the existing host grants plus an explicitly requested additional scope. Legacy placeholder scopes are filtered from requests.
- Electron CompanionPanel exposes host scope-ceiling selection for the one-time pairing code (default unchanged: `status`, `sessions.list`, `chat.send`) and per-request narrowing. Pending approvals default to `status` when requested, otherwise one requested scope; the host approves only a non-empty subset. Main-process IPC bounds the shape, while Python validates the canonical allowlist and requested-set containment.
- Android CI run [#45102656004](https://github.com/herbalblitz/emrg/actions/runs/45102656004) passed APK assembly, shared debug-signature verification, JVM unit tests, lint and artifact upload on commit `b76cfcb5f7a470cc5f57533649eb66c07e90f7db`, including the device-request safety tests. The newer request-correlation/cancellation tests on `c99b2b3bb19cc0f8742056762f56bfb12361e0b7` are awaiting CI. This does not establish physical-device Keystore behavior or Android-to-Windows end-to-end interoperability.

## Windows Companion scope controls (2026-11-22; CI #44979031932 passed)
- [x] Host scope ceiling selection is wired from Electron UI through fixed IPC to the existing `CompanionRuntime.start(scopes=...)` contract; Python remains the canonical validator.
- [x] Per-device approval UI sends a non-empty selected subset through `pairing.decide`; backend rejects scopes not requested by that device.
- [x] Regression tests passed for host ceiling forwarding, least-privilege per-device defaults, empty-selection rejection, and bounded named IPC payloads. CI #44979031932: Ubuntu host 427 passed; Windows host 420 passed, 7 skipped; Electron main/preload 189 passed, 9 skipped; renderer 785 passed; TypeScript, production build and Node-count guard passed.

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
- [x] WSS client primitive with system TLS + pinned host certificate fingerprint, auth-envelope send, protocol/workspace-bound auth-success validation, chat-only scoped frame construction and connection generation guards; dedicated opt-in host WSS listener implements device authentication and bounded scope-gated dispatch.
- [x] Credential-safe QR descriptor parsing: WSS-only, strict allowlist, mandatory <=180-second expiry; endpoint query/user-info/fragment and invalid ports rejected.
- [x] Electron CompanionPanel exposes explicit listener start/stop, QR display/copy, pending device approvals and approve/reject through guarded named IPC (renderer tests, TypeScript check and production build passed in CI #44659155014).
- [x] Android Devices route lists registered devices and revokes other devices through `devices.list` / `devices.revoke` under `devices.manage`; self-revocation is hidden in the UI and rejected by the host. Request exceptions now map to a safe recoverable state, while coroutine cancellation is rethrown (JVM regression tests passed in Android CI #45102656004).
- [x] `CompanionConnection.request` reply correlation/cancellation test seam extracted to `CompanionRequest.kt`; JVM coverage added for request-ID filtering, terminal result/error, refusal, timeout, cancellation and send-exception cleanup (commit `c99b2b3bb19cc0f8742056762f56bfb12361e0b7`; CI pending).
- [ ] Windows-side registered-device registry UI, physical-device Keystore validation and Android-to-Windows end-to-end validation remain open.
- [x] Android authenticated WSS client validates the host's `auth.ok` response and exact approved workspace/scopes (regression tests added; earlier Android CI passed; physical host interoperability remains unverified).
- [x] Compose offline session UI: list/select/create/rename/delete, isolated per-session messages/drafts (CI passed).
- [x] Protocol frame parsing + request-stream state reducer for streaming/tool lifecycle/cancel/error/connection-loss (CI passed in run `44239121243`).
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
- [x] Trusted local React CompanionPanel approval/QR UI is wired through named IPC (renderer CI #44659155014: passed); [ ] Windows-side device-registry management UI and Android-to-Windows live pairing E2E remain open.

## Host TLS identity (implementation status)
- [x] P-256 self-signed certificate generation, exact SAN host binding, SHA-256 fingerprint, bounded validity and certificate-purpose validation.
- [x] Stable identity persistence; no silent regeneration on missing/corrupt state; explicit reset/rotation.
- [x] Windows DPAPI-protected private key and POSIX restrictive file modes; atomic writes.
- [x] TLS context loader, TLS 1.2 minimum, no socket creation; process-local provisioning/context lock.
- [x] Python TLS identity tests cover persistence, corruption, SAN mismatch, key mismatch, atomicity, and DPAPI-backed key file selection (cross-platform CI previously passed).
- [ ] Windows DPAPI/ACL hardening review and physical-device Android Keystore validation remain open.

## Authentication replay defense
- [x] Require client auth nonce minimum length 16 bytes.
- [x] Add process-local per-device replay cache with 10-minute expiry, capacity bound and thread-safe atomic consume.
- [x] Duplicate nonce and cache exhaustion reject authentication.
- [ ] Decide restart persistence or challenge-response requirements and implement transport handshake before production listener.

## Companion TLS trust and auth nonce guard
- [x] Android per-connection exact leaf SHA-256 pin trust manager supports pinned self-signed host certificates; validity dates are enforced during TLS handshake and default hostname verification remains active.
- [x] Host fingerprint string is colon-delimited uppercase bytes for Android QR/parser compatibility.
- [x] Host auth nonce is minimum 16 bytes and consumed once per device with bounded 10-minute process-local replay cache; capacity exhaustion fails closed.
- [x] Android pinned-certificate trust and pairing transport build/JVM tests are covered by Android CI; [ ] physical-device Keystore validation and live host pairing E2E remain open.

## Host QR descriptor generation
- [x] Generate Android-compatible, credential-free v1 pairing descriptor with WSS endpoint and SHA-256 pin.
- [x] Reject endpoint host outside certificate SAN and invalid/insecure endpoint forms.
- [x] Enforce QR expiry at no more than 180 seconds.
- [x] Daemon runtime starts an explicit IP-bound listener, opens a status-only Pairing v2 transaction, and returns a short-lived v2 QR descriptor containing the pairing ID; runtime tests are included in Companion Security CI.
- [x] Integrate v2 descriptor display/copy and pending approval/rejection into React CompanionPanel (renderer CI #44659155014: passed).

## Companion listener safety primitives
- [x] Host Companion defaults disabled; explicit enable gate protects pairing and auth entry points, and disable invalidates outstanding pairing transactions.
- [x] Listener configuration validation rejects hostname/DNS inputs, wildcard binds, multicast/reserved addresses, privileged/out-of-range ports, and unbounded resource/timeout settings.
- [x] Dedicated opt-in WSS listener binds only after explicit host enable; TLS context loads before bind, frame/connection/timeouts bounded, authentication required as first frame, stop closes clients, concurrent starts are rejected, and disable/stop during asynchronous bind closes the late server instead of resurrecting it.
- [x] Host-service disable lifecycle observer stops the listener, closes active WSS clients and invalidates pending pairings; transport tests cover auth + scoped status and disable shutdown.
- [x] Read-only `sessions.list` WSS dispatch uses the approved credential's stored workspace path (never a client-supplied cwd), requires the `sessions.list` scope and returns correlated, allowlisted metadata (max 200 records; bounded text and message counts); tests cover workspace binding, data redaction and unauthorized dispatch.
- [x] Local pairing transaction bounds workspace/device/secret inputs, rejects malformed UTF-8 and invalid scope collections, and validates approval/cancellation IDs and strict boolean decisions; regression tests cover malformed inputs and one-time approval.
- [x] Active WSS device sessions revalidate the authenticated device's current registry authorization before each request; revocation and credential rotation invalidate already-open connections, and registry read failures fail closed. Regression tests cover credential rotation on an already-open socket.
- [x] WSS session listing returns only bounded, allowlisted metadata; local cwd, transcripts, and arbitrary meta fields are excluded.

## Trusted local Companion control bridge
- [x] Daemon owns a disabled-by-default `CompanionHostService` and an allow-listed `CompanionLocalControl` facade; credential registry path is daemon-owned.
- [x] Authenticated daemon command routes only status, pending pairing list, pairing decision and cancellation; unknown operations and malformed payloads fail closed.
- [x] Electron Main exposes fixed status/pending/decision IPC handlers and validates primary BrowserWindow, top-level frame, and local `file://` origin.
- [x] Preload exposes only named Companion methods; preload API contract test updated.
- [x] Daemon owns explicit listener start/stop, TLS identity provisioning, and v2 QR descriptor generation; Electron Main/Preload expose fixed guarded lifecycle IPC methods.
- [x] React renderer CompanionPanel implements explicit host start/stop, QR descriptor display/copy, pending approval list and approve/reject interactions (renderer CI #44659155014: passed).
- [x] Android Devices screen lists/revokes registered devices using the `devices.manage` scope and two-step confirmation; host rejects self-revoke and audit-logs decisions.
- [ ] Full Electron↔daemon runtime tests, Windows device-registry UI, physical Android Keystore checks and live Android-to-Windows pairing remain open. This is not yet a completed end-to-end pairing implementation.

## Pairing v2 host transaction core
- [x] Host trusted-local pending v2 approval listing is bounded to display/decision metadata and SHA-256 public-key fingerprint; tests assert raw public key, client nonce, challenge bytes, and issued credential are not exposed, and redeemed transactions leave the pending queue.
- [x] Transport-independent Ed25519 device-key-bound pairing transaction with host-local approval, signed challenge fields, one-time redemption, bounded lifetime and host-service lifecycle hooks.
- [x] Unit tests cover approval gating, device/key proof binding, wrong-device and wrong-signature rejection, duplicate request/approval, expiry, cancellation and credential scope issuance.
- [x] Pairing v2 tests run explicitly in Companion Security CI #112; the run passed all seven tests including concurrent one-time redemption and pending-cap/cancel-all regression.

## Android encrypted credential envelope
- [x] Strict canonical standard-Base64 validation, exact separator count, 12-byte IV and minimum 16-byte AES-GCM ciphertext.
- [x] JVM regression tests cover canonical round-trip, malformed separators/whitespace, invalid lengths and noncanonical Base64.
- [ ] Validate actual Android Keystore encryption/decryption and key deletion on supported physical devices.
