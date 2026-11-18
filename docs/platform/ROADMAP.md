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
- [x] Versioned QR descriptor parser and paste-validation UI (credential-free, WSS + fingerprint, mandatory expiry capped at 180 seconds, strict field allowlist).
- [x] Camera QR scanning with CameraX + ML Kit, runtime permission flow, one-shot QR callback, lifecycle cleanup/race guard and user-visible startup failure state (APK build, unit tests, lint and artifact upload all passed CI #122).
- [x] Define TLS host identity persistence, QR fingerprint trust, rotation/recovery, and fail-closed requirements in `COMPANION_TLS_HOST_IDENTITY_POLICY.md`; provisioning primitives now exist, but UI/listener integration remains open.\n- [x] Implement transport-free TLS primitives: DER SHA-256 fingerprinting, normalized pin validation, constant-time pin comparison, TLS 1.2+ server-context configuration and fail-closed certificate/key loading; unit tests added (CI pending).\n- [x] Provision dedicated P-256 self-signed TLS identity with SANs, 365-day validity, DER fingerprint, persistence and explicit reset; Windows key protection via DPAPI, POSIX restrictive file modes; context loader does not open sockets (tests added, CI pending).
- [ ] Verify Windows DPAPI/ACL behavior, cross-process provisioning lock, trusted host UI/QR fingerprint presentation, and TLS pinning in a real listener.
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
- [x] Add in-memory pairing transaction primitive: 180-second expiry, local approval/rejection, one-time redemption, capped secret attempts, cancel.
- [x] Audit existing daemon WebSocket boundary and document safe companion-host integration constraints in `COMPANION_HOST_INTEGRATION_BOUNDARY_AUDIT.md` (source review only; no listener opened).
- [ ] Implement trusted local UI for pairing approval and remote listener; must not widen existing loopback daemon listener.\n- [x] Specify host TLS identity lifecycle, persistence, fingerprint verification, rotation and fail-closed recovery (H2 design).\n- [x] Add TLS primitive module and tests; no sockets/listener, certificate generation, or persistence yet.
- [x] Add credential registry primitive: random 256-bit tokens, salted verifiers only, device/workspace/cwd/scopes binding, rotation by re-issue, revocation and safe metadata listing. **No listener or pairing flow consumes it yet.**
- [x] Add transport-independent companion authentication gate: validates protocol auth envelope, verifies device credential and returns only workspace/cwd/scope binding; tests added. **Not wired to a TLS listener.**
- [x] Compose local pairing approval, safe pending-pairing metadata, credential issuance/authentication, device listing and revocation in `CompanionHostService`; unit tests added. **Service is transport-independent and exposes no listener; trusted-local UI boundary remains caller responsibility.**
- [ ] Integrate credential registry with host approval, TLS listener, active-connection revocation and approved-workspace enforcement.
- [ ] Integrate secure transport with Android connection/reconciliation UI and test against a real host implementation.

### Companion hardening increment (2026-11-18)
- [x] Host protocol parser rejects duplicate JSON keys and unknown client fields across auth/status/session/chat schemas; test cases committed.
- [x] Android WSS auth envelope includes protocol version and a fresh client nonce.
- [x] Android companion chat sends the v1 allowlisted `chat.send` envelope with the host-approved workspace; does not forward desktop task/cwd/tools fields.
- [x] Client validates `auth_ok` protocol and exact approved workspace binding for chat-enabled pairing.
- [x] QR descriptor parser requires integral finite expiry and enforces a maximum 180-second lifetime.
- [ ] CI run for this increment; source-only changes are committed, Android workflow still running on the latest code commit.
- [x] Credential registry serializes same-process concurrent issue/revoke/read operations; concurrency regression test added. Cross-process locking and Windows ACL hardening remain unresolved.
- [x] Companion parser converts invalid UTF-8/lone-surrogate encoding errors into safe protocol rejections; malformed-Unicode tests added.
- [x] Android WSS client auth/chat frames now match the companion v1 field schema and enforce workspace binding; QR descriptor lifetime/endpoint validation hardened (Android test rerun pending).
- [x] Host companion frame parser rejects duplicate/unknown fields and malformed Unicode; credential registry adds same-process locking, POSIX private directory/file modes and bounded text validation (Python tests added; no CI run for feature branch yet).
- [x] Host TLS identity provisioning module added: P-256 self-signed certificate, SAN validation, exact host binding, key/cert/signature/usage/expiry checks, stable fingerprint, explicit reset and TLS context loading (no socket).
- [x] Windows key protection uses DPAPI; POSIX identity directory/files request 0700/0600. Tests cover persistence, reset, corrupt/partial state, mismatch, permissions and no-listener context.
- [ ] Run host Python tests/CI, update `uv.lock` and platform packaging dependency locks for cryptography, and review Windows DPAPI/temp ACL/cross-process race behavior before using this identity in a listener.
- CI run #175 (commit `6f49dde8`) passed: Android debug APK assemble, 114 JVM unit tests, lint, and APK artifact upload. This confirms QR expiry/endpoint parser tests and the companion client-frame tests compile and pass. It does not validate a live WSS host or Python host-side tests.
- [x] Added feature-branch CI workflow `.github/workflows/companion-security.yml` to run host companion auth/credential/pairing/protocol/TLS tests, including the new TLS identity module. Its first run is in progress; no Python test result is claimed yet.
- Companion Python CI run `44318969402` passed after the pairing-expiry test correction: **65 passed** (auth, credentials, host service, pairing, strict protocol, TLS fingerprint and TLS identity provisioning). Dependency sync succeeded on the runner. The generated lockfile update was not committed; lock/platform packaging regeneration remains a release gate.
- [x] Android WSS client now supports host self-signed certificates through a per-connection exact SHA-256 leaf pin trust manager; validates cert dates and retains default hostname verification; tests cover match/mismatch/empty-chain/invalid-pin. Android CI verification pending.
- [ ] Cross-platform lockfile updates and CI checks for cryptography dependency.
- [x] Host companion auth nonce replay guard: minimum 16-byte nonce, per-device one-time nonce cache (10-minute TTL, bounded capacity), synchronized duplicate rejection and fail-closed cache saturation; Python tests added (CI pending).
- CI run `44321725013` passed the companion host suite on Ubuntu and Windows: Ubuntu **70 passed**; Windows **67 passed, 3 skipped** (platform-specific permission/symlink cases). Windows DPAPI provisioning and TLS context tests passed. A first Windows run exposed PowerShell multiline invocation; fixed workflow now uses one portable pytest command.
- Android run `44321562671` is currently executing for exact-pin trust-manager tests after correcting test visibility/resource classloader. APK build and unit test/lint results are pending.
- [x] TLS identity provisioning/context loading now has process-local and OS cross-process locking (POSIX flock / Windows msvcrt) and symlink-protected lock file; test assertions added. CI pending for this incremental hardening.
- Latest companion security CI `44322035478` passed after cross-process lock coverage: Ubuntu **71 passed**; Windows **67 passed, 4 skipped** (POSIX permission/symlink cases). This includes TLS identity provisioning, DPAPI and lock-file checks on Windows; Windows ACL details remain unaudited.
- Latest host security CI `44323155710` after cross-process identity test passed: Ubuntu **72 passed**; Windows **68 passed, 4 skipped**. This includes two independent processes provisioning the same identity and confirming the same fingerprint. CI uploaded platform-resolved `uv.lock` artifacts; repository lockfile update remains open.
- [x] Regenerated and committed `uv.lock` with cryptography/cffi/pycparser from the CI-resolved lock; CI checked `uv sync` on Linux and Windows. Commit `46148e92`.
- Android run `44323912033`: debug APK assembly passed and **119 JVM tests passed with zero failures**, including exact-pin trust-manager matching, mismatch, expired certificate, empty chain, malformed pin, and the existing Companion protocol/client suite. Lint and APK artifact upload are still pending.
- Android companion run `44323912033` fully passed: Debug APK build, **119 JVM tests / 0 failures**, lint, and debug APK artifact upload. The test report confirms all 11 `PinnedRemoteWebSocketTest` cases passed, including exact-pin acceptance, wrong pin, expired cert, missing cert chain and malformed pin.
