# EMRG Feature Inventory

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
- [ ] Trusted host approval UI, pairing/revoke UI, Android live transport integration and end-to-end transport remain unimplemented. Host identity provisioning and the dedicated WSS listener now have host-side implementations; cross-platform runtime integration remains open.
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

- [x] Host frame schema strictness: duplicate JSON keys, unknown fields and invalid Unicode fail closed; host parser tests added (CI execution pending).
- [x] Registry same-instance thread serialization for credential issue/revoke/read; concurrent-issuance test added. Cross-process lock and Windows ACL hardening are open.
- [ ] Host TLS identity generation/persistence and trusted local UI are not yet built; TLS primitives remain transport-free.

## Host TLS identity (implementation status)

- [x] P-256 self-signed certificate generation, exact SAN host binding, SHA-256 fingerprint, bounded validity and certificate-purpose validation.
- [x] Stable identity persistence; no silent regeneration on missing/corrupt state; explicit reset/rotation.
- [x] Windows DPAPI-protected private key and POSIX restrictive file modes; atomic writes.
- [x] TLS context loader, TLS 1.2 minimum, no socket creation; process-local provisioning/context lock.
- [ ] Python tests/CI, lockfile regeneration and Windows DPAPI/ACL review.
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
- [ ] Android trust tests/build CI final confirmation; host UI and Android live integration are still not wired.

## Host QR descriptor generation

- [x] Generate Android-compatible, credential-free v1 pairing descriptor with WSS endpoint and SHA-256 pin.
- [x] Reject endpoint host outside certificate SAN and invalid/insecure endpoint forms.
- [x] Enforce QR expiry at no more than 180 seconds.
- [ ] Integrate descriptor into host approval UI and QR rendering; WSS listener currently exposes authentication and status only, with chat/session dispatch not yet connected.


## Companion listener safety primitives

- [x] Host Companion defaults disabled; explicit enable gate protects pairing and auth entry points, and disable invalidates outstanding pairing transactions.
- [x] Listener configuration validation rejects hostname/DNS inputs, wildcard binds, multicast/reserved addresses, privileged/out-of-range ports, and unbounded resource/timeout settings.
- [x] Dedicated opt-in WSS listener binds only after explicit host enable; TLS context loads before bind, frame/connection/timeouts bounded, authentication required as first frame, stop closes clients, concurrent starts are rejected, and disable/stop during asynchronous bind closes the late server instead of resurrecting it.
- [x] Host-service disable lifecycle observer stops the listener, closes active WSS clients and invalidates pending pairings; transport tests cover auth + scoped status and disable shutdown.
- [x] Read-only `sessions.list` WSS dispatch uses the approved credential's stored workspace path (never a client-supplied cwd), requires the `sessions.list` scope and returns correlated, allowlisted metadata (max 200 records; bounded text and message counts); tests cover workspace binding, data redaction and unauthorized dispatch.
- [x] Local pairing transaction bounds workspace/device/secret inputs, rejects malformed UTF-8 and invalid scope collections, and validates approval/cancellation IDs and strict boolean decisions; regression tests cover malformed inputs and one-time approval.
- [ ] Chat dispatch into daemon runtime, UI pairing/QR, Android live transport and full end-to-end remain open.

- [x] Active WSS device sessions revalidate the authenticated device's current registry authorization before each request; revocation and credential rotation invalidate existing connections, and registry read failures fail closed. Regression tests cover credential rotation on an already-open socket.
- [x] WSS session listing returns only bounded, allowlisted metadata; local cwd, transcripts, and arbitrary meta fields are excluded.


## Pairing v2 host transaction core

- [x] Transport-independent Ed25519 device-key-bound pairing transaction with host-local approval, signed challenge fields, one-time redemption, bounded lifetime and host-service lifecycle hooks.
- [x] Unit tests cover approval gating, device/key proof binding, wrong-device and wrong-signature rejection, duplicate request/approval, expiry, cancellation and credential scope issuance.
- [x] Added `tests/test_companion_pairing_v2.py` to the companion-security workflow's explicit pytest invocation; CI run [#111](https://github.com/herbalblitz/emrg/actions/runs/44447348538) passed with the initial five v2 tests, then CI run [#112](https://github.com/herbalblitz/emrg/actions/runs/44447421797) passed with all seven tests, including concurrent one-time redemption and the 32-entry pending-cap/cancel-all regression.
- [ ] WSS request/redeem adapter, trusted local approval UI, Android private-key proof/credential persistence integration and end-to-end pairing remain unimplemented.


## Android encrypted credential envelope

- [x] Replaced permissive envelope parsing with strict canonical standard-Base64 validation; exact separator count, 12-byte IV and minimum 16-byte AES-GCM ciphertext are enforced.
- [x] Added platform-independent JVM regression tests for canonical round-trip, malformed separators/whitespace, invalid lengths and noncanonical Base64.
- [x] Android Companion CI #206 passed: APK assembly, unit tests, lint and artifact upload.
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
- [x] JVM regression tests and Android CI #267 passed on commit `2fbdf7d0d92c` (APK assembly, unit tests, lint, artifact upload).
- [ ] Production integration with `PairingV2Client`, `SecurePairingCoordinator`, host approval/redeem lifecycle, and QR UI remains open; no end-to-end pairing is claimed.
- [ ] Physical-device Android Keystore validation and full Android-to-Windows host integration remain open.

## Pairing v2 Android integration status (2026-11-19)

Implemented in source:
- `PairingV2Flow` composes the signed Pairing v2 client with the pinned WSS exchange and exposes immutable transport identity metadata.
- `PairingV2SessionController` validates that QR/session metadata (WSS endpoint, TLS fingerprint, pairing ID, device ID, and scopes) exactly matches the transport that created the signed request.
- Host-local approval remains the only approval authority. A generic unavailable response leaves Android in `AWAITING_HOST_APPROVAL`; retry reuses the exact signed redeem frame.
- A credential is reported as paired only after a valid host credential response is accepted by the coordinator and secure credential storage succeeds.
- Enabled scopes remain read-only (`status`, `sessions.list`). `chat.send` is intentionally not enabled.

Not yet complete:
- QR scanner/panel is not wired to the controller.
- No physical-device Android Keystore validation or Android ↔ Windows end-to-end pairing has been completed.
- No live authenticated status/session-list transport is claimed; remote chat/tool execution remains unavailable.
