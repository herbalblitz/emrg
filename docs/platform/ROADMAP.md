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
- [x] Extract session command/reply payloads and inspect daemon event lifecycle; Windows packaging baseline documented separately.

### M1 — Protocol
- [x] Document local authentication/task fields and source-verified session command/reply schemas.
- [x] Record protocol/documentation transport mismatch.
- [x] Correct protocol.py module documentation to state that it models JSON payloads, not transport framing.
- [x] Android protocol JSON fixtures + Kotlin frame parser tests for malformed/optional/unknown frames, streaming, tool lifecycle and terminal events (CI passed in run 44239121243).
- [x] Request stream reducer tracks request/session IDs, interleaved deltas, tool events, terminal/cancel/error and connection-loss transitions (CI passed in run 44239121243).
- [x] Validated task/session command JSON builders and tests; tools omitted vs empty allowlist explicitly distinguished.
- [ ] Reconnect policy and protocol negotiation/transport integration.\n- [x] Add Android protocol-event-to-chat bridge with request binding and mismatch isolation (CI pending).
- [ ] Review pairing design without weakening local endpoint.

### M2 — Android foundation
- [x] Kotlin/Compose starter and CI workflow committed.
- [x] Fix Java/Kotlin JVM target mismatch found by first CI build (Java 8 vs Kotlin 17); follow-up CI build and lint passed.
- [ ] Gradle wrapper/reproducible build.
- [x] Initial chat state/UI and mock transport (offline-only; no network).
- [x] Chat UI resets busy state and displays safe fallback when transport throws.
- [x] Extract mock transport into a testable source unit and add deterministic unit tests for demo response and safe transport-failure fallback (CI verified).
- [x] Pure chat state reducer: blank/duplicate submissions, response/failure transitions, draft lock while sending (tests added; latest integration CI passed).
- [x] Activity saved-state restore for chat history and draft; bounded snapshot (60 messages), reset in-flight flag after recreation.
- [x] Private local SharedPreferences JSON snapshot for messages/draft; bounded to 60 messages, corrupt snapshot falls back safely (integration CI passed).
- [x] Offline session state/store and Compose UI: bounded session list, isolated chat snapshots/drafts, select/create/rename/delete, safe last-session replacement (UI CI passed).
- [x] Protocol frame model/parser and request-stream reducer added (unit test CI validation ongoing).
- [ ] Navigation/state expansion.
- [x] Add fail-closed Android pairing state model: WSS-only endpoint, SHA-256 host fingerprint confirmation, explicit host approval, host-bound scoped credential acceptance, revocation reset (CI passed).\n- [x] Versioned QR descriptor parser and paste-validation UI (credential-free, WSS + fingerprint, expiry, strict field allowlist; CI pending).
- [ ] Camera QR scanner, TLS certificate pinning in a real transport, host-side approval/revoke implementation.
- [x] Add Android Keystore AES-256-GCM credential store with alias-bound AAD, private encrypted-value persistence, and delete/revoke path (alias validation unit tests added; CI pending).

### M3 — Core workflows
- [x] Chat markdown/code renderer (headings, bold, inline/fenced code and bullets; CI passed run 44241040540).
- [x] Transport stream callback + Compose partial response rendering and Stop/cancel state (mock-only; CI passed).
- [ ] Session create/list/resume/rename.
- [x] Tool Activity UI component/state lifecycle wired to chat; CI validation pending (no live daemon event source yet).
- [x] Add protocol snapshot-to-chat reducer bridge with request-ID guard and terminal-state reconciliation; unit tests added (CI pending).
- [ ] Live tool events and approval (bridge reducer tests added; daemon transport still mock-only).
- [ ] Provider/settings and error states.

### M4 — Parity
- [ ] Workspace via scoped storage/capabilities; memory; skills; scheduled tasks; evolution; diagnostics; opt-in notifications.

### M5 — Windows reliability
- [x] Windows CI job and Inno Setup script compile smoke test baseline inspected.
- [ ] Clean-machine packaging/install/update/uninstall smoke tests.
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
- [x] Add UI-level streaming callback, partial assistant text, stop/cancel handling and reducer tests; offline transport only (CI passed in run 44239121243).
- [x] Add protocol JSON fixtures, Kotlin frame model/parser and request-stream reducer; desktop command schemas audited and lifecycle documented. Android CI test verification is still in progress.
- [x] Resolve transport mismatch in source documentation (protocol.py header corrected; contract doc explains current WebSocket transport).
- [ ] Test tools allowlist omission vs empty.
- [x] Wire Tool Activity display into chat transient state and Compose; tests cover active-request updates and cleanup (CI pending).
- [x] Bridge protocol request snapshots to chat streaming/tool activity state; mismatched request IDs are ignored (CI pending).
- [x] Confirm Android persistence and session UI integration CI results; builds/lint/tests passed before adding features.
- [x] Integrate offline session manager into Compose UI; session controls are disabled during a request to prevent cross-session response writes.
- [x] Validate offline session UI build/lint/tests; no daemon synchronization until protocol and pairing are validated.
- [ ] Add wrapper and confirm CI result.
- [x] Implement host-scoped Android Keystore credential storage; does not implement pairing or network transport.
- [x] Inspect Windows packaging and test workflow baseline; documented in `WINDOWS_PACKAGING_AUDIT.md`.
- [ ] Run clean Windows install/upgrade/reconnect/uninstall smoke and investigate gaps.

Execution branch: `feat/android-windows-platform-audit`; do not work on `master`.
CI first attempt (commit `34e23d1`) failed at Kotlin compile because Java compile target defaulted to 1.8 while Kotlin targeted 17. Fixed by setting both Java compatibility and Kotlin JVM target to 17 in `android/app/build.gradle.kts` (commit `9d74a53`); later CI runs for the fix and mock-chat UI both completed successfully.
Commit `5bd51cc` adds local mock chat UI; no host connection or tool execution. The mock transport is isolated in `ChatTransport.kt`; unit tests cover its explicit demo response and transport-error fallback. Earlier CI passed the mock transport and reducer baseline. Persistence integration run `44229919552` passed after the Compose context fix (`f6a0b6c`). Offline session store and state unit-test CI passed (`44231236886`, `44231252445`). Compose session UI and per-session chat update refactor committed; CI run `44232157705` succeeded (APK build/lint/tests).
Session schemas and event lifecycle were source-inspected and documented in `CLIENT_PROTOCOL_CONTRACT.md`; desktop tests cover event dispatch and delta flush ordering. This is not an Android runtime protocol integration test. Source review does not imply builds/tests ran.

Protocol parsing/reducer commits: `e95de48`, `c151b46`, `01c0b59`; fixture/tests: `8b66bf4`, `4b5d1c9`, `3e64477`. Android JVM CI was enabled; CI exposed Compose Saver test misuse and an invalid local-session selection expectation. Snapshot encoding/decoding is now tested via pure codec helpers, and the selection test now asserts unknown IDs leave the active session unchanged. Streaming UI/state changes and fixes passed CI in run `44239121243`.
