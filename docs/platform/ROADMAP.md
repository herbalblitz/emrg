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
- [x] Android protocol JSON fixtures + Kotlin frame parser tests for malformed/optional/unknown frames, streaming, tool lifecycle and terminal events (CI passed run 44239121243).
- [x] Request stream reducer tracks request/session IDs, interleaved deltas, tool events, terminal/cancel/error and connection-loss transitions (CI passed run 44239121243).
- [x] Validated task/session command JSON builders and tests; tools omitted vs empty allowlist explicitly distinguished.
- [x] Document reconnect/reconciliation safety requirements.
- [x] Implement pure Android reconnect state model: epoch guards, auth/reconcile/ready/unknown/failure states, bounded retry schedule, foreground/manual-disconnect controls; JVM tests added.
- [x] Guard protocol stream events by connection epoch and reject stale frames.
- [x] Validate connection-epoch guard and bridge tests in CI #127 (run `44272379675` passed).
- [ ] Integrate model with secure transport, host snapshot API, and protocol negotiation (not implemented; no live daemon connection).
- [x] Add Android host-connection status panel with explicit offline/no-live-transport label and status text for all reducer phases; presentation tests passed CI #129 (run `44273927462`).
- [x] Require non-empty protocol version in host snapshot before READY; missing version fails closed to STATE_UNKNOWN (CI verification pending).
- [x] Document safe reconnect/reconciliation constraints: never replay non-idempotent commands; mark active streams interrupted and require authoritative host snapshot before resuming UI state (design-only).
- [x] Add Android protocol-event-to-chat bridge with request binding and mismatch isolation (CI passed run 44260106387).
- [ ] Review pairing design without weakening local endpoint.

### M2 — Android foundation
- [x] Kotlin/Compose starter and CI workflow committed.
- [x] Fix Java/Kotlin JVM target mismatch (Java 8 vs Kotlin 17); CI passed.
- [ ] Gradle wrapper/reproducible build.
- [x] Initial chat state/UI and mock transport (offline-only; no network).
- [x] Chat UI resets busy state and displays safe fallback when transport throws.
- [x] Extract mock transport and add deterministic unit tests for demo response and safe failure fallback.
- [x] Pure chat state reducer: blank/duplicate submissions, response/failure transitions, draft lock while sending.
- [x] Activity saved-state restore for chat history and draft; bounded snapshot (60 messages), reset in-flight flag after recreation.
- [x] Private local SharedPreferences JSON snapshot for messages/draft; bounded to 60 messages, corrupt snapshot falls back safely.
- [x] Offline session state/store and Compose UI: bounded session list, isolated chat snapshots/drafts, select/create/rename/delete, safe last-session replacement.
- [x] Protocol frame model/parser and request-stream reducer (JVM tests passed in CI #121).
- [ ] Navigation/state expansion.
- [x] Fail-closed Android pairing state model: WSS-only endpoint, SHA-256 host fingerprint confirmation, explicit host approval, host-bound scoped credential acceptance, revocation reset.
- [x] Versioned QR descriptor parser and paste-validation UI (credential-free, WSS + fingerprint, expiry, strict field allowlist).
- [x] Camera QR scanning with CameraX + ML Kit, runtime permission flow, one-shot QR callback, lifecycle cleanup/race guard and user-visible startup failure state (APK build, unit tests, lint and artifact upload all passed CI #122).
- [ ] TLS certificate pinning in real transport; host-side pairing approval/revoke.
- [x] Android Keystore AES-256-GCM credential store with alias-bound AAD, private encrypted-value persistence and delete/revoke path (unit tests passed).

### M3 — Core workflows
- [x] Chat markdown/code renderer (headings, bold, inline/fenced code and bullets; CI passed run 44241040540).
- [x] Transport stream callback + Compose partial response rendering and Stop/cancel state (mock-only; CI passed).
- [ ] Session create/list/resume/rename over host transport.
- [x] Tool Activity UI component/state lifecycle wired to chat (no live daemon event source).
- [x] Protocol snapshot-to-chat reducer bridge with request-ID guard and terminal-state reconciliation; tests passed.
- [ ] Live tool events and approval (bridge reducer tests; daemon transport still mock-only).
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
- [x] UI streaming callback, partial assistant text, stop/cancel handling and reducer tests; offline transport only.
- [x] Protocol JSON fixtures, Kotlin frame model/parser and request-stream reducer; desktop command schemas audited and lifecycle documented. JVM tests passed CI #121.
- [x] Resolve transport mismatch in source documentation (protocol.py header corrected; contract doc explains current WebSocket transport).
- [ ] Add explicit regression test for tools allowlist omission vs empty.
- [x] Tool Activity UI and reducer tests for active-request updates and cleanup.
- [x] Protocol snapshot-to-chat bridge; mismatched request IDs ignored.
- [x] Offline session manager integrated; session controls disabled during active request.
- [x] Android persistence/session UI build, lint and tests validated.
- [ ] Add Gradle wrapper and verify reproducibility.
- [x] Host-scoped Android Keystore credential storage; no remote network transport yet.
- [x] Windows packaging and test workflow baseline inspected; audit documented.
- [ ] Windows clean-machine install/upgrade/reconnect/uninstall smoke.

## CI evidence
- Run #129, commit `d7c6f71b9d996ff09b4795de13109893cbb73361`: connection status panel integration, APK assembly, JVM tests, lint and artifact upload passed.
- Run #122, commit `9c9922f43ce325f3292ee82674be91a18f6e18e9`: Android debug APK assembly, JVM unit tests, lint and APK artifact upload all succeeded. It validates scanner startup error UI plus parser/reducer tests; it is not an Android instrumentation or live daemon pairing test.
- Run #121, commit `02b3145927568a41ec58c6c28fdfb2fd5746dcf0`: same pipeline passed for scanner call-site opt-in fix.
- Earlier CI runs: mock/session/streaming integration runs documented in the historical notes below.

Execution branch: `feat/android-windows-platform-audit`; do not work on `master`.
CI first attempt (commit `34e23d1`) failed at Kotlin compile because Java compile target defaulted to 1.8 while Kotlin targeted 17. Fixed by setting Java compatibility and Kotlin JVM target to 17 (commit `9d74a53`); subsequent CI passed.
Commit `5bd51cc` adds local mock chat UI; it does not connect to a host or execute tools. Mock transport is isolated in `ChatTransport.kt`. Persistence integration run `44229919552` passed after the Compose context fix (`f6a0b6c`). Offline session store/state tests passed (`44231236886`, `44231252445`). Compose session UI run `44232157705` passed APK build/lint/tests.
Session schemas and event lifecycle were source-inspected and documented in `CLIENT_PROTOCOL_CONTRACT.md`; desktop tests cover event dispatch and delta flush ordering. This is not Android runtime protocol integration.
Protocol parsing/reducer commits: `e95de48`, `c151b46`, `01c0b59`; fixture/tests: `8b66bf4`, `4b5d1c9`, `3e64477`. Android JVM CI exposed and fixed Compose Saver misuse and a local-session selection expectation. Snapshot encoding/decoding now uses pure codec tests; streaming UI/state changes passed CI run `44239121243`.

- [x] Android WSS transport primitive with system TLS + pinned leaf-certificate fingerprint check, device-scoped credential lookup, auth gate, chat-only send surface, stale-callback generation guard and certificate fingerprint test. **Client primitive only; daemon remote companion authentication/listener is not implemented.**
- [x] Bind SEND_CHAT pairing credentials to explicit approved workspace ID/cwd; reject chat sends outside the exact approved cwd.
- [x] Draft host companion v1 wire contract and security gates in `HOST_COMPANION_PROTOCOL_V1.md` (specification only; no listener implementation).
- [x] Add strict companion-frame parser/validator (pre-auth gate, message allowlist, scope/workspace checks, size limits, strips client cwd/tools/sandbox fields); unit tests added. This is a validation module only.
- [ ] Implement host-side remote companion listener and pairing approval; must not widen existing loopback daemon listener.
- [x] Add credential registry primitive: random 256-bit tokens, salted verifiers only, device/workspace/cwd/scopes binding, rotation by re-issue, revocation and safe metadata listing. **No listener or pairing flow consumes it yet.**
- [ ] Integrate credential registry with host approval, TLS listener, active-connection revocation and approved-workspace enforcement.
- [ ] Integrate secure transport with Android connection/reconciliation UI and test against a real host implementation.
