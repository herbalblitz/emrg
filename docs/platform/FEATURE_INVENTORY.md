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
- [x] WSS client primitive with system TLS + pinned host certificate fingerprint, auth-envelope send, protocol/workspace-bound auth-success validation, chat-only scoped frame construction and connection generation guards; host listener is still absent.
- [x] Credential-safe QR descriptor parsing: WSS-only, strict allowlist, mandatory <=180-second expiry; endpoint query/user-info/fragment and invalid ports rejected.
- [ ] Host identity provisioning/persistence, trusted host approval UI, host WSS listener, live pairing/revoke and end-to-end transport remain unimplemented.
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
