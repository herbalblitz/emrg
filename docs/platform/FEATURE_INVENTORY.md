# EMRG Feature Inventory

Source-tree audit inventory. Source-inspected does not mean runtime-tested.

## Core runtime
- [ ] Agent orchestration/providers
- [x] Daemon WebSocket transport/local auth inspected
- [ ] Lifecycle/reconnect end-to-end tests
- [x] Protocol dataclasses and GUI response-type map partially reviewed
- [ ] Full schemas/stream lifecycle contract and tests
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
- [ ] Runtime packaging, installer/PATH/shortcuts/uninstall
- [ ] Sandbox, upgrades/rollback, clean-machine smoke, CI artifact/checksum

## Android
- [x] Native Kotlin/Compose scaffold with interactive mock chat, send/receive and demo/offline state (no real host connection)
- [x] Mock transport extracted with tests for demo response and transport failure fallback (CI passed)
- [x] Chat state reducer with tests for blank/duplicate submits, draft lock, response/failure and stale response.\n- [x] Activity saved-state snapshot restores chat messages/draft, caps saved history to 60, clears transient sending state.\n- [x] Device-local private SharedPreferences snapshot persists chat history/draft across app restarts; malformed snapshot falls back to fresh preview state (CI pending).
- [x] Safe chat error fallback preserves coroutine cancellation (tests added; CI pending)
- [x] CI workflow committed; debug APK build, lint, and unit tests passed in Actions for the latest verified run
- [ ] Pair/revoke; secure transport; diagnostics/reconnect
- [ ] Chat/stream/cancel; sessions; tool activity/approvals
- [ ] SAF workspace; memory; skills; tasks; evolution
- [ ] Opt-in notifications; settings/export; accessibility/offline states

## Audit docs
- `PROJECT_AUDIT.md`
- `FEATURE_INVENTORY.md`
- `ROADMAP.md`
- `DAEMON_PROTOCOL_AND_ANDROID_SECURITY.md`
- `NEXT_GENERATION_REBUILD_POLICY.md`
- `CLIENT_PROTOCOL_CONTRACT.md`

Status: **Source-inspected** = checked code; **Implemented** = code exists; **Tested** = test actually executed and passed.
