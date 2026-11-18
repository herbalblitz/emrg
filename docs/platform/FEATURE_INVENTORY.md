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
- [ ] Pair/revoke; secure transport; diagnostics/reconnect (proposal only documented)
- [x] Compose offline session UI: list/select/create/rename/delete, isolated per-session messages/drafts (CI passed).
- [x] Protocol frame parsing + request-stream state reducer for streaming/tool lifecycle/cancel/error/connection-loss (CI passed in run `44239121243`); no real daemon transport yet.
- [x] Offline UI integration for response streaming, partial text display and Stop/cancel action (no live transport; CI passed in run `44239121243`).
- [x] Tool Activity component wired into transient chat state and Compose rendering; lifecycle reducer tests added (CI pending; protocol events not yet connected to live transport).\n- [ ] Daemon session synchronization; tool approval UI
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
