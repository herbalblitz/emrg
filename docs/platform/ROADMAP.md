# Android + Windows Implementation Roadmap

Target: preserve all original EMRG features while delivering robust Android and Windows experiences.

## Principles
1. Python runtime/provider/tools/memory/skills/subagents/scheduler/session/sandbox remains source of truth.
2. Preserve Electron desktop; rebuild weak components only after evidence and parity review.
3. Android is native Kotlin/Compose companion initially; no unsafe direct local-runtime port.
4. Protocol-first, security-first, incremental parity; never overclaim completeness.

## Milestones
### M0 — Audit
- [x] Initial repository/component inventory.
- [x] Inspect daemon transport/auth and remote-access boundary.
- [ ] Extract authoritative command/event payloads and Windows packaging baseline.

### M1 — Protocol
- [x] Document local authentication and task fields; partial command/reply mapping.
- [x] Record protocol/documentation transport mismatch.
- [ ] Reconcile protocol.py docs with daemon behavior.
- [ ] Contract fixtures/tests for malformed payload, optional fields, event lifecycle, reconnect.
- [ ] Review pairing design without weakening local endpoint.

### M2 — Android foundation
- [x] Kotlin/Compose starter and CI workflow committed.
- [x] Fix Java/Kotlin JVM target mismatch found by first CI build (Java 8 vs Kotlin 17); follow-up CI build and lint passed.
- [ ] Gradle wrapper/reproducible build.
- [x] Initial chat state/UI and mock transport (offline-only; no network).
- [x] Chat UI resets busy state and displays safe fallback when transport throws.
- [x] Extract mock transport into a testable source unit and add deterministic unit tests for demo response and safe transport-failure fallback (CI verified).
- [x] Pure chat state reducer: blank/duplicate submissions, response/failure transitions, draft lock while sending (tests added; CI pending).
- [ ] Navigation/state expansion, protocol fixtures.
- [ ] Pairing UI, diagnostics, secure credential storage, TLS and revoke.

### M3 — Core workflows
- [ ] Chat, streaming, cancellation, markdown/code.
- [ ] Session create/list/resume/rename.
- [ ] Tool events and approval.
- [ ] Provider/settings and error states.

### M4 — Parity
- [ ] Workspace via scoped storage/capabilities; memory; skills; scheduled tasks; evolution; diagnostics; opt-in notifications.

### M5 — Windows reliability
- [ ] Windows tests baseline; packaging/install/update/uninstall.
- [ ] Lifecycle/restart/reconnect, PATH/shell integration.
- [ ] Sandbox/security regression and clean-machine smoke tests.
- [ ] CI artifacts/checksums.

### M6 — Release gates
- [ ] Core/protocol tests; Electron tests/typecheck/package.
- [ ] Windows clean install/upgrade/reconnect/uninstall.
- [ ] Android lint/unit/instrumentation/APK/AAB.
- [ ] Security/dependency review, signing and release notes.

## Definition of done
Accessible UI; documented protocol; success/loading/empty/offline/error/cancel states; security/approval boundaries; automated tests; platform constraints; no secret or unbounded private logs.

## Current work queue
- [ ] Extract remaining protocol payload schemas and terminal/cancellation order.
- [ ] Resolve transport mismatch in source documentation.
- [ ] Test tools allowlist omission vs empty.
- [ ] Build Android fake transport/state before remote networking. State reducer and transition tests now added; CI verification pending.
- [ ] Add wrapper and confirm CI result.
- [ ] Inspect Windows packaging/test evidence for concrete defects.

Execution branch: `feat/android-windows-platform-audit`; do not work on `master`.
CI first attempt (commit `34e23d1`) failed at Kotlin compile because Java compile target defaulted to 1.8 while Kotlin targeted 17. Fixed by setting both Java compatibility and Kotlin JVM target to 17 in `android/app/build.gradle.kts` (commit `9d74a53`); later CI runs for the fix and mock-chat UI both completed successfully.
Commit `5bd51cc` adds local mock chat UI; no host connection or tool execution. The mock transport is isolated in `ChatTransport.kt`; unit tests cover its explicit demo response and transport-error fallback. Latest CI is green; source changes are reviewed, not locally built.
Source review does not imply builds/tests ran.
