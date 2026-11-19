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
- [x] Bound host pending pairing transactions (32 max); purge expired entries before capacity checks and expose shutdown/disable invalidation hook. Tests added; CI verification pending.
- [x] Windows packaging and test workflow baseline inspected; audit documented.
- [ ] Windows clean-machine install/upgrade/reconnect/uninstall smoke.

## CI evidence
- Run #95, commit `22b056f1`: disposal during in-progress WSS startup closes the late-bound server, unregisters the host callback, and prevents restart; Ubuntu **154 passed**, Windows **150 passed, 4 skipped**.
- Run #94, commit `9ffb1bdb`: WSS listener disposal/callback-unregistration regression test passed on Ubuntu and Windows; Ubuntu **153 passed**, Windows **149 passed, 4 skipped**. This validates host-side companion security tests only, not Android live transport.
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
- [x] Regenerated all four platform packaging locks (`windows-x64`, `linux-x86_64`, `linux-aarch64`, `macos-arm64`) to include `cryptography`/CFFI via CI and committed as `f5070eda`. Added a test that fails if any platform lock omits cryptography. Cross-platform CI verification of this new commit is in progress.
- Cross-platform lockfile CI run `44325149306` passed: Ubuntu **73 passed**, Windows **69 passed, 4 skipped**. Both runners regenerated/validated all four platform packaging locks; the lockfile guard now confirms `cryptography` is present in every platform lock. Windows DPAPI, process-lock, TLS identity and nonce tests remain green.
- Android companion run `44324129985` also fully passed on the Android workflow concurrency change: assemble Debug APK, 119 unit tests, lint, and artifact upload. Confirms the latest branch state did not regress the green Android checks.
- [x] Host QR descriptor primitive added: Android v1 fields only, WSS endpoint/SAN match, colon SHA-256 pin, no credential, TTL <=180s. Descriptor tests added and included in CI; host UI/QR rendering integration remains open.
- Companion descriptor CI run `44325571922` Linux leg passed **89 tests**, including new host QR descriptor validation (SAN matching, endpoint safety, credential-free schema and TTL limits). Windows leg is still running.
- Companion descriptor CI run `44325571922` fully passed on both platforms: Ubuntu **89 passed**; Windows **85 passed, 4 skipped**. Includes descriptor generator and all four platform packaging locks generated on each runner; Windows-specific DPAPI and file-lock tests passed.
- [x] QR descriptor expiry is capped at host TLS certificate expiration and already-expired identity descriptors are rejected; tests added.
- Pairing descriptor expiry guard CI `44325896168` passed on both platforms: Ubuntu **91 passed**, Windows **87 passed, 4 skipped**. Certificate-expiry bounds and expired-host identity rejection are tested. Endpoint canonicalization changes are pending the next run.
- Canonical endpoint descriptor CI `44326015099` passed: Ubuntu **92 passed**, Windows **88 passed, 4 skipped**. Host descriptor canonicalizes DNS case/trailing dot and port, with unit coverage. Cross-platform companion tests remain green.

### Companion pairing transaction capacity/lifecycle increment
- Pending pairing records are capped at 32. Creation purges expired entries first and rejects safely if capacity is still exhausted.
- Host service companion state is now **disabled by default**; pairing creation/approval/redemption and authentication are gated until explicit local `enable()`.
- `disable()` flips the state off and invalidates all outstanding transactions; pending-list UI data is hidden while disabled. This remains a service primitive, not yet wired to host UI or a listener.
- Tests cover default-off behavior, enable/disable, pending invalidation, redeem rejection, and the prior cap/expiry tests. CI verification pending.


### Companion default-off + WSS listener implementation
- [x] Companion host service is disabled by default; enable is explicit, disable invalidates pending pairings, and disabled state gates pairing/authentication.
- [x] Added a listener configuration guard: IP-literal only, no wildcard/multicast/reserved bind, ports restricted to 1024-65535, and bounded connection/handshake/idle/close settings.
- [x] Wired the policy into a dedicated, opt-in TLS WSS listener; TLS context loads before bind, authentication is the first frame, and connection/frame/time limits are enforced.
- [x] Implemented scoped `status` and read-only `sessions.list`; session paths come only from the approved credential record and returned metadata is allowlisted and bounded.
- [x] Host disable and explicit stop close clients; disable/stop during asynchronous bind closes the late server rather than resurrecting the listener, and concurrent start calls cannot create duplicate servers.
- [x] Active connections revalidate revocation and credential generation on each request; remote `chat.send` remains explicitly unimplemented and is never dispatched.
- [ ] Trusted local pairing/QR UI, Android live WSS integration, chat/task lifecycle, cancellation, tool approvals and end-to-end tests remain open.
- [x] Cross-platform CI verified startup-race and metadata-bound regression tests: Ubuntu **150 passed**; Windows **146 passed, 4 skipped** in run `44438215551`.

- [x] Follow-up WSS lifecycle regression CI run `44438531074` passed on both platforms after adding explicit-stop-during-bind and concurrent-start tests: Ubuntu **152 passed**; Windows **148 passed, 4 skipped**.


### Android Companion authentication gate verification (2026-11-19)
- [x] Android WSS client rejects application frames before host authentication, rejects repeated authentication-control frames after authentication, and rejects missing/null frame types in either state.
- [x] Android Companion CI run #191 passed on commit `fdd5d1bb`: debug APK assembly, JVM unit tests, lint and debug APK artifact upload.
- [x] Recorded the client-side security gate and its limitations in `CLIENT_PROTOCOL_CONTRACT.md` (documentation commit `85732af9`).
- [ ] Still required before usable remote Companion: real host-approved pairing/credential issuance flow, Android pairing coordinator + encrypted credential-store integration, live WSS connection/UI lifecycle, host protocol compatibility/negotiation, daemon chat/task bridge, cancellation and tool-approval lifecycle, and end-to-end tests. The current demo chat remains offline and must not be presented as connected.


### Pairing integration audit (2026-11-19)
- [x] Remove Android self-approval affordance; UI now waits for host-side approval rather than locally transitioning to HOST_APPROVED (commit `a85ea4c3`; Android CI #192 pending at audit time).
- [x] Confirm host-side primitives enforce local approval, one-time redemption, expiry, attempt limits and device credential issuance; current descriptor is credential-free.
- [ ] Design and implement versioned QR transaction binding (pairing ID/one-time secret exchange without embedding the issued device credential), trusted local host approval UI, and pinned-TLS redemption endpoint.
- [ ] Connect Android coordinator to the real host response and persist only the credential issued by that host; add cross-platform end-to-end tests. Do not mark pairing complete until these gates pass.


## Companion pairing v2 design (2026-11-19)

- Added [COMPANION_PAIRING_V2_DESIGN.md](COMPANION_PAIRING_V2_DESIGN.md), defining the proposed QR v2 transaction reference, host-local approval, device-key proof of possession, one-time redemption, strict WSS message boundaries, state machine, and security test matrix.
- The design deliberately keeps reusable credentials and private keys out of QR data. Pairing remains disabled end-to-end until host transaction, local approval UI, WSS exchange, Android Keystore integration, and cross-platform tests are implemented.
- CI Android Companion #192 passed on `a85ea4c3`; it predates this documentation-only commit.


## Pairing transaction hardening (2026-11-19)

- Increased pairing transaction identifier entropy to 256 random bits (32 CSPRNG bytes encoded URL-safe).
- Added regression coverage for identifier length and concurrent redemption: a single approved transaction must issue exactly one credential when multiple redemption attempts race.
- Companion security CI is being used to validate the implementation on Ubuntu and Windows; do not treat the run triggered by the code-only commit as validation of the subsequent test commit.


### Pairing transaction CI result (2026-11-19)

- Companion security CI #97 passed on commit `50fec84a` on both Ubuntu and Windows.
- Ubuntu: 156 tests passed.
- Windows: 152 tests passed, 4 skipped.
- The run includes the 256-bit pairing ID change and concurrent redemption regression test. The test asserts that eight simultaneous redemption attempts against one approved transaction yield exactly one credential.
- The run was triggered by the test commit and therefore validates the pairing implementation and tests at `50fec84a`; the following documentation-only commit does not change executable code.


### Pairing v2 host core prototype (2026-11-19)

- Added transport-independent Ed25519 device-key-bound pairing transactions and host-service lifecycle methods.
- Added tests for local approval, exact device binding, invalid signatures, duplicate device requests, expiry, cancellation, credential issuance, and host disable cleanup.
- WSS listener and Android are not wired to the prototype; QR v2 and TLS identity plumbing remain incomplete.


### Pairing v2 cross-platform host CI (2026-11-19)

- Companion security CI #103 passed on commit `afb7407d` on Ubuntu and Windows.
- Ubuntu: 157 tests passed.
- Windows: 153 tests passed, 4 skipped.
- This validates the standalone Ed25519 pairing v2 core and host-service lifecycle adapter methods, including device-key binding, host approval, invalid signature/device rejection, expiry, cancellation, and disable cleanup.
- This does not validate a live WSS pairing endpoint or Android integration; those remain intentionally unimplemented.


### Pairing v2 signed-expiry verification (2026-11-19)

- The signed Ed25519 challenge now includes a wall-clock `expires_at` value, as well as host identity, device ID, key fingerprint, workspace, and exact scopes. Transaction enforcement still uses a monotonic host clock to avoid wall-clock changes extending the local TTL.
- Companion security CI #104 passed on code commit `8550af1b`: Ubuntu 157 passed; Windows 153 passed, 4 skipped.
- Companion security CI #105 passed on test commit `7d2aad8d`: Ubuntu 157 passed; Windows 153 passed, 4 skipped. The added regression test checks that host identity and expiry are included in the signed challenge.


### Pairing v2 CI coverage correction (2026-11-19)

- The companion-security workflow's path filter already matched `tests/test_companion_pairing_v2.py`, but its explicit pytest command omitted that file. Added the v2 test module to the Ubuntu/Windows invocation.
- Companion security CI run [#111](https://github.com/herbalblitz/emrg/actions/runs/44447348538) passed on both platforms after the workflow correction: Ubuntu **170 passed**; Windows **166 passed, 4 skipped**. Logs show all five `test_companion_pairing_v2.py` tests passed on both runners.
- This verifies the host-side companion test suite and v2 transaction unit tests only; it does not validate a live WSS pairing endpoint or Android v2 integration.
- Pairing v2 remains a transport-independent host primitive. No live WSS pairing endpoint or Android v2 integration is claimed.


### Pairing v2 concurrency and capacity regression coverage (2026-11-19)

- Commit `e2efde59` added tests proving concurrent redemption issues exactly one credential and the pending-transaction cap remains 32 with `cancel_all()` clearing the queue.
- Companion security CI [#112](https://github.com/herbalblitz/emrg/actions/runs/44447421797) passed on that test commit: Ubuntu **172 passed**; Windows **168 passed, 4 skipped**. Both new tests and all seven tests in `test_companion_pairing_v2.py` passed on both platforms.
- This remains host-side unit-test evidence; the v2 pairing flow is not yet wired to a live WSS endpoint or Android client.


### Pairing v2 concurrency and capacity regression CI (2026-11-19)

- Added a concurrent redemption test: eight simultaneous attempts against one approved v2 transaction must produce exactly one credential, which is then verified against the expected device, workspace and scopes.
- Added pending-transaction capacity coverage: the 33rd pending request is rejected at the 32-transaction limit; cancel_all() clears the pending set and capacity becomes available again.
- Companion security CI [#112](https://github.com/herbalblitz/emrg/actions/runs/44447421797) passed on commit `e2efde59653c`: Ubuntu **172 passed**; Windows **168 passed, 4 skipped**.
- These results validate host-side pairing v2 transaction and protocol tests on both platforms. They do not validate live WSS pairing, trusted host UI, Android Keystore integration, or end-to-end pairing; those remain open.


### Android credential-envelope hardening CI (2026-11-19)

- Android credential persistence now uses a strict, platform-independent envelope codec while preserving the existing standard padded Base64 `IV.ciphertext` storage format.
- The decoder requires exactly one separator, canonical Base64, a 12-byte AES-GCM IV and at least a 16-byte authentication-tag-bearing ciphertext; malformed or noncanonical values fail closed.
- Android Companion CI [#206](https://github.com/herbalblitz/emrg/actions/runs/44454840217) passed on commit `16c24854`: debug APK assembly, JVM unit tests, lint and APK artifact upload all succeeded.
- Android CI #204 failed on the intermediate source commit `af227532`, which referenced the codec before its follow-up implementation commit; #206 is the completed run containing the implementation and regression tests.
- The CI result validates JVM tests and APK build/lint, not Android Keystore behavior on physical devices. Pairing v2 device-key integration and live host pairing remain release gates.


### Android credential size hardening (2026-11-19)

- Android pairing coordinator and encrypted credential store now enforce the host registry's 512-byte UTF-8 credential bound before persistence; blank and oversized credentials fail closed.
- AES-256-GCM Android Keystore key generation explicitly requires randomized encryption.
- Regression tests cover blank credentials, exact 512-byte acceptance, oversized ASCII and multibyte UTF-8 rejection, and coordinator non-persistence on oversized issuance.
- Android CI #207 is running for the first storage change; #210 is queued for the complete source and test set at `69ca6a82`. Do not treat either as passed until the latest run completes.
- App minSdk is 26; the platform-independent envelope codec's `java.util.Base64` dependency is therefore compatible with the configured minimum API.
- Physical-device Android Keystore behavior remains unverified and is a separate validation gate.


### Pairing v2 WSS route and cross-platform regression (2026-11-19)

- The dedicated opt-in WSS listener now accepts strict one-frame Pairing v2 `pairing.request` and `pairing.redeem` messages, separate from the authenticated v1 session protocol. The host remains the only approval authority; network peers cannot approve or inspect transaction state.
- The route returns a generic unavailable response for failed redemption and applies a bounded per-peer unauthenticated-attempt limiter.
- Companion security CI #158 passed on commit `85f6af746220c80e97f36b859b41f5b3c01767e6`. This validates host-side WSS security tests on Ubuntu and Windows.
- Remaining gates: daemon lifecycle/configuration integration, trusted Electron local approval UI, Android live pinned-WSS coordinator and retry flow, physical-device Keystore verification, and end-to-end pairing. No Android-to-host pairing completion is claimed.


### Pairing v2 Android transport increment (2026-11-19)

- [x] Add a one-frame pinned-WSS exchange primitive for Pairing v2 request/redeem messages. The TLS leaf SHA-256 pin is checked before application data is sent; responses are bounded to 16 KiB and bound to the pairing transaction, response type, and device ID where applicable.
- [x] Add JVM tests for strict challenge/credential envelopes, transaction mismatch, unexpected response types, and device binding.
- [x] Android Companion CI #267 passed on commit `2fbdf7d0d92c`: debug APK assembly, unit tests, lint, and artifact upload succeeded.
- [ ] Wire the exchange to `PairingV2Client`, `SecurePairingCoordinator`, host-approval wait/redeem flow, and the QR UI. This increment is a transport primitive, not end-to-end pairing.
- [ ] Verify Android Keystore behavior on physical devices and complete cross-platform end-to-end pairing tests.

### Pairing v2 integration status (2026-11-19)

- [x] Compose the Android Pairing v2 client with the pinned WSS exchange through `PairingV2Flow`; bind endpoint, certificate fingerprint, pairing ID, device ID, and requested scopes to the same transport instance.
- [x] Add `PairingV2SessionController` to coordinate challenge delivery, redemption, reducer transitions, and encrypted credential persistence.
- [x] Keep host-local approval authoritative. Android never fabricates approval; a generic unavailable response leaves the coordinator awaiting host approval and permits a bounded user-initiated retry using the cached signed redeem frame.
- [x] Persist credentials only after a valid host-issued credential response and successful coordinator/store validation.
- [ ] Wire the QR scanner/panel and lifecycle-safe retry UX to the controller.
- [ ] Run Android Keystore compatibility tests on physical devices and validate Android ↔ Windows pairing end-to-end.
- [ ] Run authenticated status/session-list operations against a real Windows host. Chat remains disabled because the workspace/cwd authorization contract is not yet complete.

The latest Android debug APK assembly has succeeded. The first controller-integration CI run exposed one test assertion issue in the retry test; the test now checks the successful paired callback rather than expecting a historical failure callback to be erased. A fresh CI run is required before marking this increment verified.


### QR-to-controller UI integration update (2026-11-19)

- [x] Connect the Android QR camera/paste panel to the Pairing v2 descriptor parser and `PairingV2SessionController` (`SecurePairingPanel.kt`). The panel accepts v2 descriptors, displays endpoint and SHA-256 fingerprint, requires explicit fingerprint confirmation, creates/loads the device identity, requests the host challenge, submits the signed proof, and exposes a manual retry path for host approval/redemption.
- [x] Cancel the active pairing exchange when the panel leaves composition; Android still cannot approve its own host request.
- [x] Android CI run `44503538472` for commit `7df4eeb924e7a36e55b653e4ab77ddefa4a7c6de` passed: debug APK assembly, unit tests, lint, and APK artifact upload.
- [ ] Validate non-exportable P-256 key creation/signing/persistence on physical Android devices and supported API levels.
- [ ] Exercise QR creation, trusted-local host approval, pinned WSS request/challenge/redeem and credential persistence against a real Windows host in an end-to-end test.
- [ ] Wire authenticated status/session-list operations to the Android UI and reconcile live host state. `chat.send` remains disabled; no live chat/tool execution is claimed.

The QR panel is now wired to the v2 controller, superseding earlier notes that the scanner/panel was not connected. This does not make pairing production-ready or complete Android/Windows parity.


### Android pairing retry and callback lifecycle hardening (2026-11-19)

- Pairing challenge retries now retransmit the exact original request frame and client nonce; the host returns the original challenge only for an exact match.
- The pairing panel ignores callbacks queued by an older QR transaction after reset or rescan, preventing stale network responses from replacing the current UI state.
- Android workflow run `44508660928` passed APK assembly, unit tests, lint, and artifact upload for the retry-frame change. Run `44509690853` also passed the same checks for the stale-callback isolation change (`0a587371540cb8c49072cd1b063fad160605712e`).
- Remaining validation: physical Android Keystore/device test and live Windows-host pairing end-to-end test.
