## Immediate WSS credential-change invalidation (2026-11-28)

- `CompanionHostService` now emits device-change notifications after successful v1/v2 credential issuance or reissue and after revocation; callbacks run outside the host lifecycle lock.
- `CompanionWssListener` tracks authenticated sockets by device ID and closes matching sockets with policy code 1008 when that device credential changes, without waiting for another client request. Request-time registry checks remain as defense in depth.
- Added host-service tests for v1 redemption/revocation notifications, v2 redemption notification, and a WSS regression test proving an idle authenticated socket closes immediately after revocation.
- This closes the transport and invalidates Android's connection epoch, but it cannot roll back a host operation that was already dispatched before the credential change. No automatic request replay is introduced. GitHub Actions #46097140748 passed the host suite on Ubuntu (429 passed) and Windows (422 passed, 7 skipped); the new callback and idle-socket tests passed on both. Electron main/preload tests, renderer tests, TypeScript check and production build also passed; the final documented Node suite-count guard was still running at the last check. No local pytest run was available.

# Android + Windows Implementation Roadmap

Test counts are measured, never stored: `uv run --no-sync python3 scripts/check-doc-count.py --measure`.

Target: preserve all original EMRG features while delivering robust Android and Windows experiences.

## Verified implementation status (2026-11-24; supersedes older historical notes below)
- Android has a native Compose companion UI, QR/manual pairing descriptor flow, Android Keystore-backed device credentials, pinned WSS host authentication, and reconnect/refresh/disconnect state handling.
- The Windows daemon has an opt-in dedicated WSS companion listener, local pairing approval UI/IPC, TLS identity provisioning, and QR descriptor display.
- The currently implemented remote WSS operations are scope-gated per paired device: status, session listing/management, live chat (with approval round-trip), tool-activity monitoring, files read/write, terminal exec (capped at workspace-write unless the host opts a device into danger), sandbox management, and device listing/revocation. Pairing approval remains local to Windows.
- Host-service credential issue/reissue and revocation now notify the WSS listener; matching authenticated sockets are closed immediately, including idle sockets. Per-request registry revalidation remains defense in depth. A host operation already dispatched before revocation is not rolled back; the client connection is closed and pending reply waits are invalidated rather than automatically retried.
- Android chat/session and device-management screens use the live companion transport only when paired with the corresponding granted scopes; DemoTransport remains for previews/tests.
- Fresh Android pairing defaults to the read-only `status` scope; additional capabilities require explicit selection, and the host-configured scope ceiling plus host-local approval remain authoritative. Re-pair preselects existing grants and any explicitly requested additional scope.
- Electron CompanionPanel exposes a host-selected scope ceiling for the pairing code created by each listener start (default remains `status`, `sessions.list`, and `chat.send`) and per-device approval narrowing. The Python runtime/local-control validators remain authoritative for canonical scopes and requested-set containment.
- Android request correlation and connection-epoch lifecycle tests passed in [#45928193631](https://github.com/song-nguyet/emrg/actions/runs/45928193631) on `7067c95a28e94ae016df3138ff25f663575c1782`: debug APK assembly, shared debug-signature verification, all 195 JVM tests (0 failures), Android lint and APK/test-report artifact uploads succeeded. The test now checks exception type/message rather than object identity, because coroutine stack-trace recovery may propagate a distinct exception instance. This is GitHub Actions evidence only; no local Gradle build, physical-device Keystore check or Android↔Windows E2E validation was performed.
- Remaining release gates include physical Android↔Windows pairing/reconnect validation, Android Keystore device validation, Windows clean-machine install/upgrade/uninstall and lifecycle smoke tests, Gradle wrapper/reproducible builds, and parity work for chat/tools/providers/settings/memory/skills/scheduling/evolution/diagnostics.
- Older notes later in this file describe earlier milestones and may say that the listener or transport was not yet implemented. Treat those statements as historical snapshots, not the current state.

## Android request lifecycle CI verification (2026-11-27; passed)
- The send-exception test was corrected to assert propagated exception type/message instead of object identity; coroutine stack-trace recovery can produce a distinct exception instance. The test still checks that the reply collector is removed, and the epoch test opts into `ExperimentalCoroutinesApi`.
- Android CI [#45928193631](https://github.com/song-nguyet/emrg/actions/runs/45928193631) passed on code commit `7067c95a28e94ae016df3138ff25f663575c1782`: `assembleDebug`, shared debug-signature verification, 195 JVM tests with 0 failures, `lintDebug`, and both test-report and APK artifact uploads.
- The test correction did not change production request logic, protocol frames, authorization or retry behavior. No local Gradle build or physical Android↔Windows validation was performed.

## Android device-management request reliability (2026-11-23; CI #45102656004 passed)
- Source review found that `DevicesScreen` awaited `CompanionConnection.request` without an exception boundary. An ordinary transport/request exception could escape the Compose coroutine and leave the refresh indicator stuck.
- Added a small request-outcome helper that converts ordinary exceptions into a generic recoverable UI failure, without displaying raw exception details. `CancellationException` is deliberately rethrown so navigation/lifecycle cancellation is not misreported as a host failure.
- Added JVM tests for success, ordinary transport failure and cancellation propagation. GitHub Actions #45102656004 passed APK assembly, shared-signature verification, JVM unit tests, lint and artifact upload on `b76cfcb5f7a470cc5f57533649eb66c07e90f7db`. The wire protocol, device authorization and host-side revocation semantics are unchanged.

## Android request correlation and lifecycle test seam (2026-11-24; CI pending)
- Extracted the shared request/reply wait from `CompanionConnection.request` into `awaitCompanionReply`, retaining subscribe-before-send behavior and the existing request-ID plus terminal-frame matching rule.
- Added JVM tests for unrelated/progress-frame filtering, matching result/error replies, send refusal, timeout, parent cancellation and send-exception cleanup.
- This is a transport-neutral test seam only: it does not change WSS frames, authorization, retry policy or remote operations. CI for commit `c99b2b3bb19cc0f8742056762f56bfb12361e0b7` is pending; no local Gradle run is available.

## Host scope configuration and approval UI (2026-11-22; CI #44979031932 passed)

- The Electron host UI passes a bounded, explicit scope ceiling for the one-time pairing code returned by `CompanionRuntime.start`; omission still preserves the prior three-scope default for older callers.
- Each pending device request can be approved for a non-empty subset only. The UI preselects `status` when present (otherwise one requested scope), and the host service rejects any subset outside the signed request.
- Main-process IPC accepts only the named fields and bounded unique scope arrays; the daemon's canonical 20-scope registry remains the allowlist authority.
- GitHub Actions #44979031932 passed: host security tests 427/427 on Ubuntu and 420 passed with 7 skipped on Windows; Electron main/preload suite 189 passed with 9 skipped; renderer 785/785 passed; TypeScript check, production build and measured Node test-count guard passed.

## Companion remote-chat integration gate (verified 2026-11-20)

**Status update (2026-11-20, same day): the gates below are now satisfied by the 20-screen expansion — `emrg/server/companion_chat.py` reuses the daemon task path via an injected in-process `daemon_accessor` (one-line `daemon.py` wiring, no loopback socket), `CompanionStreamHub` is the bounded per-session event sink with replay, `chat.send` is host-consented through the scope grant, and tests cover scope/workspace/session validation, queueing, cancellation and disconnect. The `not_implemented` response for `chat.send` is removed; the analysis below is kept as the design record of why the boundary is shaped this way.**

Remote chat must reuse the daemon's existing task/session engine; it must not create a second LLM/tool runner inside the WSS listener. The current source has a clear but incomplete boundary:

- `CompanionRuntime` owns the WSS listener and provisions a pairing transaction with only `status` and `sessions.list` scopes. It currently has no daemon task-dispatch callback.
- `CompanionWssListener._session` authenticates the device and validates each frame, but deliberately answers `chat.send` with `not_implemented`. The regression test proves that even a synthetic `chat.send` credential cannot dispatch execution.
- The daemon's existing `_handle_client` task path owns the canonical behavior: safe session-ID validation, `TaskRequest` construction, session creation, input-history recording, sandbox-tier resolution, per-session queueing, cancellation handles, and `_run_tool_loop_locked` lifecycle.
- The existing task loop streams through daemon `_broadcast` to local websocket subscribers. A remote companion needs an explicit, bounded event sink keyed by request/session; simply invoking the tool loop from WSS would not deliver the Android stream and risks bypassing lifecycle bookkeeping.

Required implementation sequence before enabling `SEND_CHAT`:

1. Extract a daemon-owned submission API that reuses the same validation, session lock/queue, sandbox resolution, input-history, cancellation, and terminal-frame behavior as the local task path. Keep local websocket transport concerns outside this API.
2. Inject that API into `CompanionRuntime` / `CompanionWssListener` as a narrow callback. Resolve workspace and cwd only from the approved credential record; never accept a remote path, tool list, sandbox tier, or arbitrary execution options.
3. Define a bounded remote event subscription for one authenticated request: request/session binding, ordered text/tool/status events, explicit terminal state, disconnect cancellation policy, output/frame limits, and timeout/backpressure behavior.
4. Add host-local consent for the `chat.send` scope. Pairing must not grant it implicitly; scope changes/revocation must invalidate existing sockets as they do now.
5. Add tests for unauthorized scope, mismatched workspace/session, unsafe session ID, queueing, duplicate request IDs, concurrent sends, cancellation, disconnect, output bounds, stale socket/revocation, and proof that no arbitrary cwd/tools/sandbox values cross the boundary.
6. Only then wire Android chat UI to the live stream and run Android/Windows CI plus physical-device end-to-end tests.

Until all gates pass, keep remote chat disabled and preserve the explicit `not_implemented` response. Do not report the Android app as live-chat capable merely because WSS authentication and read-only status/session listing work.

### Daemon task-path source audit (2026-11-20)

A closer source pass confirms why a thin `_run_tool_loop_locked` call from WSS is not a safe implementation:

- The canonical client path in `EmrgServer._handle_client` constructs `TaskRequest`, resolves the `Session` from the accepted cwd, appends input history, applies `resolve_client_tier`, and publishes `_session_cancel` / `_session_turn_task` handles before the tool loop starts.
- `_run_tool_loop_locked` owns turn start/end timestamps, terminal-frame fallback, busy-lock release, cancel-handle cleanup, and pending-queue disposition. These are daemon invariants, not transport details.
- Pending work is not an independent FIFO worker: `_session_pending` is injected at round boundaries by the running turn, while terminal cleanup emits `queued_requeue` only after a normal end and otherwise drops queued requests with `queued_cancelled`. A remote submission API must preserve that behavior rather than invent a second queue or silently strand queued turns.
- `_broadcast` currently routes daemon events to session subscribers and filters them by the active task cwd. A remote adapter needs a bounded, request-scoped event sink with explicit subscription lifecycle; exposing the raw local broadcast stream would risk cross-request/session leakage and would not define remote backpressure.
- The host credential supplies the authoritative `workspace_id` and `cwd`. The client frame parser normalizes `chat.send` to request ID, workspace, optional session ID and prompt; it deliberately does not accept remote cwd, tools, or sandbox values. The eventual daemon API must keep those exclusions and derive the session/tool/sandbox policy on the host.

Implementation decision: do not enable or dispatch `chat.send` until the submission API and remote event sink are designed together. The current explicit `not_implemented` response is safer than invoking the loop without correct queue, cancellation, event, and disconnect semantics. This audit is documentation/source inspection only; no executable behavior changed.

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
- [x] Enforce UTF-8-only decoding for byte-oriented v1 and pairing-v2 JSON frames; reject UTF-16/UTF-32 payloads before schema validation (regression tests passed in Companion security CI #44659155014 on Ubuntu and Windows).
- [x] Document local authentication/task fields and source-verified session command/reply schemas.
- [x] Record protocol/documentation transport mismatch.
- [x] Correct protocol.py module documentation to state that it models JSON payloads, not transport framing.
- [x] Android protocol JSON fixtures + Kotlin frame parser tests for malformed/optional/unknown frames, streaming, tool lifecycle and terminal events (CI passed run 44239121243).

## Android request invalidation on connection changes (2026-11-25)

- `CompanionConnection` now maintains a monotonic request epoch. Epoch capture and the final send are serialized against explicit connect/disconnect and socket-state callbacks, so a request captured on an old epoch cannot be emitted through a newly paired socket. The reply helper checks the epoch before sending and while waiting.
- `awaitCompanionReply` subscribes to the correlated reply stream and epoch before sending. It returns `null` when the epoch changes, prefers invalidation over a late reply if both become ready together, and cancels/joins both collectors on success, refusal, timeout, send exception, or parent cancellation.
- JVM tests cover disconnect/reconnect invalidation and a late same-request-ID reply, in addition to request correlation, timeout, refusal, cancellation and send-exception cleanup.
- The send-exception cleanup test initially used `assertSame` for the exception instance. CI #45927838908 showed coroutine stack-trace recovery can produce a distinct instance with the same exception type/message; the assertion was corrected in `7067c95a28e94ae016df3138ff25f663575c1782`. CI #45928193631 passed all 195 JVM tests, lint, APK assembly and signature verification.
