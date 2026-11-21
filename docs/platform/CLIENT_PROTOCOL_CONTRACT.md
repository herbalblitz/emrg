# Client protocol contract: current desktop baseline

Test counts are measured, never stored: `uv run --no-sync python3 scripts/check-doc-count.py --measure`.

Audit scope: reviewed 2026-11-18 on `feat/android-windows-platform-audit`.
Observed behavior only; this is not a remote-access implementation.

## Source/documentation mismatch
`emrg/protocol.py` describes length-prefixed JSON over Unix socket/Windows named pipe, while the inspected daemon and desktop GUI use WebSocket to `ws://127.0.0.1:56031`. Current daemon loopback binding and first-frame authentication are operative desktop behavior. Resolve this discrepancy before Android networking. Do not expose the daemon on LAN as a shortcut.

## Authentication
1. Desktop reads local token from `~/.emrg/emrgd.token`.
2. WebSocket connects to loopback port 56031.
3. First frame: `{"type":"auth","token":"…"}`.
4. Successful authentication receives `{"type":"auth_ok"}`.
5. Authentication timeout: 10 seconds. Auth rejection is terminal for the attempt; GUI stops retrying to prevent retry storms.
The desktop token is a local secret. Never place it in Android, QR payloads, logs, or remote pairing.

## Task request (observed in emrg/protocol.py)
Fields: `type=task`, `id` UUID, `session_id`, `cwd`, `prompt`, `timestamp` ISO datetime. Optional: `images` (only emitted if non-empty), `sandbox` (only if truthy), `tools` (emitted whenever non-null). **Omitted tools and an empty tools array differ**: empty array explicitly requests no tools.
Illustrative:
```json
{"type":"task","id":"<uuid>","session_id":"<session>","cwd":"<path>","prompt":"Hello","timestamp":"<ISO-8601>","tools":[]}
```

## Event fields observed
- Streaming deltas: frames carry `request_id`, `content`, `done=false`, `delta=true`; desktop maps to `message_delta`. Client must preserve request correlation and ordering.
- Terminal completion: `done=true`, final `content` and `request_id`; daemon may include `cancelled` boolean. Desktop maps to `done` and clears request stream grouping.
- Failure: error frames include `error`; when associated with a task the daemon may also send terminal `done=true` with an error field. Client should avoid duplicate terminal rendering.
- `tool_start`: request_id, tool_name, tool_call_id, arguments, intent; optional call_label may be absent in older frames.
- `tool_end`: request_id, tool_name, tool_call_id, content, error.
- `pong`: identity, uptime_seconds, evolution_count; optional fields such as model may be present and must not break parsing.
- Broadcast events source-inspected and desktop-tested: `turn_start`, `turn_end`, `task_queued`, `steer_committed`, `queued_requeue`, `queued_cancelled`, `sandbox_roots`, and `rewind_result`. Session-scoped events include `session_id`; queue frames may include `request_id`, `position`, or `request_ids`.
- Desktop tests assert delta batching flushes before `done`, `cancelled`, `error`, and close. These are desktop regression tests, not Android integration tests.

## GUI command-to-reply map (observed, partial)
| Command | Reply |
|---|---|
| list_sessions | sessions_list |
| list_models | models_list |
| list_agents | agents_list |
| list_history | history_list |
| list_input_history | input_history |
| list_tasks | tasks_list |
| list_rants | rants_list |
| list_memories | memories_list |
| list_skills | skills_list |
| resume_session | resume_result |
| resolve_session_cwd | session_cwd_result |
| delete_session | session_deleted |
| set_model | model_set |
| clear_session | clear_result |
| rename_session | rename_result |
| trigger_task | trigger_result |
| compact | compact_result |
| rewind_session | rewind_result |
| read_memory | memory_content |
| evolution_summary | evolution_summary |
| github_connect / disconnect / connect_web | corresponding *_result |
| list_files | files_list |
| read_file | file_content |
| task_create / task_update / task_delete | task_result |
| task_template_list | templates_list |
| task_template_create / update / delete | template_result |

This table only names response types; command payload schemas, permissions and data shapes must be inspected before implementation.

## Requirements for new clients
- Preserve desktop wire behavior; negotiate explicit protocol version/capabilities before remote support.
- Maintain request correlation, session/cwd isolation, and robust reconnect state reconciliation.
- Cancellation and terminal handling should be idempotent.
- Host owns tool approvals; pairing must not grant execution consent.
- Remote credentials are device-scoped, revocable/rotatable, expiry-bound, TLS-protected and replay-resistant.
- Diagnostics redact tokens, prompts, private paths and tool secrets.

## Next verification
- [x] Reconcile protocol.py transport description with daemon's actual WebSocket transport.
- [x] Extract session command schemas and inspect task/event lifecycle and desktop test coverage.
- [x] Add portable JSON fixtures and Android JVM parser tests for authentication/status, optional fields, streaming, tool lifecycle, cancellation, task requests, unknown future frames and malformed JSON. Fixtures live at `android/app/src/test/resources/protocol-fixtures/frames.json` and parser at `android/app/src/main/java/ai/emrg/companion/ProtocolFrame.kt`.
- [ ] Version/capability negotiation and remote-pairing threat model.


## Session command wire schemas (source-verified)

All commands below are JSON WebSocket frames sent after authentication. Command names and response frame names are confirmed in `emrg/server/daemon.py`; required fields and success/error behavior are source-inspected.

| Command frame | Required fields | Response frame | Response fields |
|---|---|---|---|
| `list_sessions` | `cwd` | `sessions_list` | `sessions`; error when cwd omitted |
| `resume_session` | `session_id`, `cwd` | `resume_result` | `session_id`, `meta` on success; `error` otherwise |
| `rename_session` | `session_id`, `cwd`; `title` optional | `rename_result` | `session_id`, `title`; title may be generated when omitted/empty |
| `clear_session` | `session_id`, `cwd` | `clear_result` | `session_id`, `ok: true` on success; `error` on invalid request |
| `delete_session` | `session_id`, `cwd` | `session_deleted` | `session_id`, `ok: true` or `error` |
| `rewind_session` | `session_id`, `cwd`, `record_index` | `rewind_result` | success: `record_index`, `kept_count`, `removed_count`, `removed[]`; errors include `error` |

### Session safety / client behavior

- `resume_session` validates that the session exists at the supplied cwd and checks session metadata; it does not return transcript records. Desktop reads history from disk. Android must not assume this local-disk behavior is available remotely.
- `list_sessions` is cwd-scoped. A client must retain the cwd associated with each session and must not silently substitute a different project path.
- `rename_session` with an empty title invokes host-side title generation; clients should send a non-empty title for deterministic rename.
- `clear_session` and `delete_session` may cancel queued messages and broadcast `queued_cancelled` to session subscribers.
- `rewind_session` broadcasts its successful result to subscribers, including descriptors of removed records. Treat the event as a session-wide state update, not just a reply to the caller.
- These findings are source inspection, not runtime integration tests. The Android live Companion transport remains unimplemented; the separate opt-in host-side WSS subset is documented below.

## Transport doc correction

`emrg/protocol.py` contains typed message models and JSON serialization; its module header's length-prefix / Unix socket / Windows pipe description is inconsistent with the current daemon and desktop GUI WebSocket implementation. Do not derive transport or framing from that header. Existing desktop behavior remains loopback WebSocket with token auth.


## Android protocol parsing milestone (2026-11-18)

- Added a transport-neutral Kotlin `ProtocolFrame` model that preserves the raw JSON frame and tolerates unknown event types and optional fields.
- The parser explicitly exposes task correlation/session IDs, streaming and terminal flags, cancellation/error state, and tool lifecycle metadata.
- Shared test fixtures cover auth/status, delta, tool start/end (including older frames without `call_label`), done/cancelled, error, empty tool allowlist and unknown future event.
- JVM tests reject malformed JSON and non-object roots, and verify nullable/optional values and correlation fields.
- Android CI now runs `testDebugUnitTest` in addition to assemble/lint.
- **Not implemented:** Android WebSocket transport, pairing/authentication UI, protocol negotiation, daemon task bridge, or remote execution. The frame model and host-side WSS subset do not yet form a usable Android client.


## Android request-stream reducer milestone (2026-11-18)

- Added a pure Kotlin reducer keyed by `request_id`; independent requests can receive interleaved chunks without mixing content.
- Tracks session correlation, ordered deltas, tool-call start/end, completion, cancellation, error and connection-loss terminal state.
- Late frames and duplicate terminal events are ignored; connection loss fails only active requests.
- Unit tests cover concurrent/interleaved IDs, stream ordering, idempotent terminal handling, cancellation/error, connection loss and tool lifecycle.
- This reducer is state logic only; no WebSocket/daemon transport or live host is connected.
- CI failure on the previous test-enabled run was traced to existing Compose Saver tests calling `Saver.save` without a `SaverScope`. The test harness is being corrected and revalidated.


## Android command payload builders (2026-11-18)

- Added validated, transport-neutral `TaskCommand` and `SessionCommand` JSON builders for task, list/resume/rename/clear/delete/rewind operations.
- Tests cover required fields, optional session/timestamp/title fields, tools omitted versus explicit empty allowlist, and invalid inputs.
- Builders are payload-only: no authentication, WebSocket send, command authorization, or remote file access is implemented.


## Android offline streaming UI milestone (2026-11-18)

- Chat transport abstraction now accepts a stream callback; the current DemoTransport emits one mock delta and does not contact the daemon.
- Compose chat displays interim response text, finalizes it into history, and exposes Stop to cancel the coroutine/request state.
- Reducer preserves partial text when cancelled and clears transient stream state on terminal completion/failure. Unit tests cover delta accumulation and cancellation.
- **Not implemented:** incremental daemon stream, transport correlation integration, network cancellation semantics, markdown rendering, tool UI or remote connection. CI verification pending.


## Android chat-state protocol projection (2026-11-18)

- Chat UI state can bind a single active request ID and consume a protocol reducer snapshot only when the snapshot request ID matches; stale/unrelated request snapshots are ignored.
- Active snapshots project streamed text and tool lifecycle into transient Compose state. Completed snapshots append the final assistant text; cancelled and failed snapshots use existing UI terminal handling and clear request binding.
- Added reducer tests for request binding, stale correlation, active projection, completion, failure and cancellation cleanup.
- This remains a state projection layer, not a connected event bridge: the offline demo transport does not emit protocol frames and no daemon is connected.

## Reconnect and state reconciliation policy (design-only)

Until a remote transport and host snapshot API exist, Android must treat a lost connection as an interruption—not as proof that the host stopped executing a task.

1. **Fail active local stream presentation:** on transport loss, transition only active request streams to an interrupted/failed state, preserve already-rendered partial text, and do not rewrite terminal requests.
2. **No automatic command replay:** never resend task creation, tool/action commands, cancellation, rewind, clear, delete, rename, or other potentially non-idempotent operations after reconnect. A user may explicitly retry after checking authoritative host state.
3. **Reconnect backoff:** future transport should use bounded exponential backoff with jitter, reset only after a stable authenticated connection; stop retrying on explicit auth rejection, revoked credential, host identity/fingerprint change, or protocol incompatibility. Respect app lifecycle and user disconnect.
4. **Authenticate and revalidate identity first:** reconnect only to the paired endpoint with the stored host identity and valid device credential. TLS/pin or identity failures are terminal until the user re-verifies/re-pairs; do not silently trust a changed host.
5. **Reconcile before resuming controls:** obtain a host-authoritative snapshot containing protocol version/capabilities, device authorization status, sessions scoped to the original approved workspace/cwd, active request IDs/status, queue state, and last-event cursor if supported. If the host cannot provide this snapshot, show connection restored but state unknown; do not enable controls that could duplicate work.
6. **Request correlation:** discard late frames for requests already locally terminal or not bound to the current connection generation. The transport must tag frames with a connection epoch so stale socket callbacks cannot mutate new connection state.
7. **Session scope:** never silently switch cwd/project or infer remote transcript history from local session storage. Preserve explicit session IDs and require the host to validate session/cwd association.
8. **User-visible states:** distinguish disconnected, reconnecting, authenticated-but-reconciling, connected/ready, identity/auth failure, and state-unknown. Diagnostics must redact tokens, prompts, paths and tool arguments.

These are requirements for a future implementation, not evidence of an existing reconnect client, host snapshot endpoint, retry mechanism, or remote pairing.


## Connection epoch and offline status UI milestone (2026-11-18)

- The protocol stream reducer now carries a connection epoch and rejects frames tagged with a stale epoch.
- The chat bridge explicitly ignores epoch-change actions for chat projection; request frames are projected only when correlated and accepted by the reducer.
- Tests verify stale/current epoch frames and bridge isolation. CI run #127: passed APK assembly, JVM tests, lint and artifact upload.
- Android UI now displays the reconnection model's phase and fail-closed explanation, including disconnected, connecting, authenticating, reconciling, ready, unknown state, retry, auth failure and host identity mismatch.
- The status panel is model-backed but remains offline. It explicitly states that live transport is not implemented and does not expose a connect action or falsely claim connectivity.
- Presentation tests added; CI verification for this newest panel commit is pending.

## Reconnect protocol-version gate (2026-11-18)

- The Android reconnect reducer accepts protocol major version `1` (either `1` or `1.x`) and treats minor versions as backward-compatible for this client contract.
- Blank, malformed, prerelease-like, or unknown-major versions fail closed to `STATE_UNKNOWN`; the snapshot is discarded and controls must not be considered reconciled.
- This is a client-side compatibility policy only. The daemon does not yet negotiate capabilities or expose the remote snapshot endpoint.
- Tests cover accepted/rejected version strings and ensure a `2.0` snapshot is not retained. CI verification is pending.

## Capability negotiation model milestone (2026-11-18)

- Added a transport-neutral offer/result model for version and capability negotiation.
- Known client-side capabilities describe parser/state handling only; they do **not** imply a daemon transport is available.
- Unknown optional host capabilities are ignored. Unknown required capabilities, required capabilities omitted from the host offer, and unsupported protocol versions fail closed.
- JVM tests cover compatible offers, optional future capabilities, unsupported major versions, unknown mandatory features and malformed offers.
- **Not implemented:** handshake wire message, daemon capability advertisement, transport integration or remote snapshot validation.

## Snapshot validation model milestone (2026-11-18)

- Added transport-neutral validation for snapshot authorization, protocol version, nonblank session/request identifiers, optional event cursor, and optional workspace/cwd scope.
- When the caller supplies an approved workspace ID or cwd, the snapshot must match exactly; mismatches fail validation.
- Tests cover valid matching scope and invalid authorization/version, blank IDs/cursor/scope, and workspace/cwd mismatch.
- **Not integrated into a remote reducer or transport:** callers must invoke this validator before treating a snapshot as reconciled. No host snapshot API exists yet.

- Follow-up: the reconnection reducer now accepts the approved workspace ID and cwd as reconciliation context and validates them before transitioning to `READY`; tests exercise the reducer boundary for both matching and foreign snapshots.

- Request-stream reducer now refuses to reuse a request ID already present in state, including terminal/interrupted requests; retry must use a fresh ID and explicit user intent. This is local reducer protection, not server-side idempotency.

- Credential revocation handling: if encrypted credential deletion fails, pairing enters `FAILED` but retains the credential alias/host reference for retry; it must not falsely report `REVOKED` or discard the cleanup handle. A successful retry clears pairing state.

## Android secure WebSocket transport primitive (2026-11-18)

- Added an OkHttp WSS client primitive with system TLS validation and an additional exact SHA-256 **leaf certificate** fingerprint check against the pairing descriptor. The device credential is not sent until the identity check passes.
- The client uses a proposed `auth` frame carrying device ID and the device-scoped credential, and waits for `auth_ok` before enabling chat-task sends. It only exposes `TaskCommand` sends and checks the `SEND_CHAT` scope; arbitrary daemon command sending is not exposed.
- This proposed companion authentication frame is **not supported by the existing daemon**. The daemon currently authenticates its desktop loopback token. No Windows host listener, device credential issuance/verification, remote pairing server, or live end-to-end connection has been implemented. This Android transport primitive must not be presented as usable against current EMRG.
- No automatic reconnect/replay is implemented. Transport callbacks currently report safe status strings and parsed frames; integration with UI/reconciliation remains pending.

- Chat pairing credentials now require an explicit approved workspace ID and cwd binding. The transport rejects chat tasks whose cwd is not an exact match for the approved cwd; host-side authorization and canonical path enforcement remain mandatory.


## Host companion protocol proposal (2026-11-18)

The extended remote host API, chat/task lifecycle, stream events, replay policy and implementation gates are drafted in `HOST_COMPANION_PROTOCOL_V1.md`. The opt-in WSS transport, credential verifier and TLS listener now exist as a limited backend subset documented below; the broader design remains a specification, not an implemented capability. Pairing approval UI/QR rendering, Android live transport and daemon task bridge are still absent. The existing desktop loopback endpoint remains unchanged and must not be exposed.


## Companion WSS implemented subset (2026-11-19)

The opt-in Companion WSS listener currently supports:
- First-frame device credential authentication with the paired device's granted scopes.
- `status` requests with the `status` scope.
- `sessions.list` requests with the `sessions.list` scope and exact paired `workspace_id`.

For `sessions.list`, the host resolves the filesystem path exclusively from the authenticated device credential registry record. The client cannot supply or override `cwd`; a request for a different workspace is rejected before dispatch. The response is correlated as `sessions.result` and includes the approved workspace ID and session metadata. This is read-only metadata access; it does not resume sessions, read transcripts, or execute tasks.

Chat dispatch, cancellation, tool approvals, filesystem operations, pairing UI/QR rendering, Android live transport and end-to-end tests remain unimplemented. Do not treat the current WSS subset as a general remote daemon API.


## Active Companion authorization revalidation (2026-11-19)

- Before dispatching each authenticated WSS request, the host checks that the device remains present, non-revoked, and bound to the same workspace, cwd and scopes as at authentication.
- Revoking a device or rotating/replacing its credential invalidates already-open WSS sessions on their next request. Each new credential issuance stores a fresh opaque generation marker; the authenticated identity carries that marker internally, and active-session checks compare it even when workspace, cwd and scopes remain unchanged. Older registry entries without this marker remain compatible through a rotation-sensitive value derived from their existing salt and verifier. Registry read/validation errors fail closed. This is request-boundary enforcement; it does not interrupt an idle socket until it next sends a request (or the listener is otherwise stopped).


### Remote session metadata boundary

The WSS `sessions.list` response does not forward raw session metadata. It exposes only a bounded allowlist (`session_id`, timestamps, title, and message counts in the range 0–2^31−1), truncates text fields, and caps the result at 200 entries. Local paths, transcript content, and arbitrary metadata keys are excluded.

### Pairing input boundary

The local pairing transaction rejects empty, malformed UTF-8, or oversized identity inputs before retaining a pending request: workspace IDs are limited to 128 UTF-8 bytes and workspace paths to 4096 bytes. Scope collections must be non-empty sets containing only the explicitly supported scope names. Redemption bounds pairing IDs to 128 bytes, pairing secrets to 512 bytes, and device IDs to 128 bytes. These checks bound the in-memory pairing surface; they do not replace local approval, TLS, or device authorization.
Approval and cancellation lookups also reject malformed or oversized pairing IDs without raising unexpected type errors. Approval decisions must be actual booleans; a pending request cannot be re-decided after approval or rejection.
The currently unimplemented `chat.send` frame validator also rejects empty, non-string, oversized, or invalid-UTF-8 `session_id` values before returning normalized request data. The listener does not yet dispatch these chat frames.



### WSS dispatch allowlist and negative-path tests

The WSS listener dispatches only authenticated `status` and read-only `sessions.list` requests. A correctly scoped `chat.send` frame is currently answered with `not_implemented`; it is not forwarded to a chat executor. Requests without the required scope are rejected at protocol validation before the corresponding host operation runs. Tests cover both properties so adding protocol validation for a future operation cannot silently enable its execution.


### Listener startup/shutdown race boundary

The WSS listener does not publish a newly-bound server if local Companion policy is disabled or `stop()` is called while the asynchronous socket bind is still in progress. The returned server is closed and awaited before startup fails. A regression test exercises disable-during-startup to prevent a late bind from resurrecting the listener after local shutdown.

The listener also rejects a second concurrent `start()` while the first bind is pending. This prevents two independent WSS servers from being bound by racing lifecycle calls; the in-progress state is cleared on both successful and failed startup.


## Android authenticated WSS contract (2026-11-20)

- The host's `CompanionWssListener` sends `auth.ok` (dot separator), not the desktop daemon's `auth_ok` spelling. The success envelope contains `protocol: "1.0"`, a non-empty `workspace_id`, and the exact granted `scopes` array.
- Android now accepts only `auth.ok` while authentication is pending, rejects application frames before it, and rejects auth-control frames after it. A host auth failure currently closes the socket with policy code 1008 rather than sending a separate auth-error frame.
- The Android client validates the returned workspace when one was pre-approved and requires the returned scope set to match the paired host's approved scopes exactly; missing, duplicate, extra, non-string or unsupported scopes fail closed.
- This aligns the authentication envelope only. Authenticated status/session UI requests, Android-to-host live UI wiring, reconnect reconciliation and full end-to-end testing remain open.

## Pairing integration boundary (2026-11-20)

- Host pairing v2 is implemented in `CompanionHostService` / `PairingV2Transactions`: a host-local transaction binds device public key, client nonce, host TLS identity, workspace and requested scopes; host-local approval is required before signed one-time redemption.
- The QR descriptor's version 2 form carries WSS endpoint, TLS fingerprint, expiry and the opaque pairing transaction ID. It remains credential-free; the device credential is issued only after the host-approved redeem proof.
- `CompanionWssListener` routes unauthenticated `pairing.request` and `pairing.redeem` frames to a bounded, rate-limited one-frame exchange. Those frames never establish an authenticated session. After pairing, the listener accepts device-scoped auth and dispatches only `status` and read-only `sessions.list`; `chat.send` remains `not_implemented`.
- The Electron `CompanionPanel` now starts/stops the opt-in host, displays/copies the v2 QR descriptor, lists pending devices and routes approve/reject through named local IPC. Registered-device revocation UI and full Electron-to-daemon runtime validation remain open.
- Android Pairing v2 request/redeem transport and pinned WSS client primitives exist. The authenticated socket now matches the host's `auth.ok` response and validates exact workspace/scope binding. Authenticated status/session UI operations, physical-device Keystore validation and live Android-to-Windows end-to-end tests remain open.


## Companion WSS WebSocket frame encoding

The host serializes bounded JSON with `encode_server_frame`, which returns UTF-8 bytes. Before calling the Python `websockets` API, the listener decodes those bytes to a UTF-8 string so the library emits a WebSocket **text** frame. Passing bytes to `websocket.send` would emit a binary frame; the Android Companion client rejects binary frames. Regression tests assert text-frame delivery for authentication, status, session-list and Pairing v2 responses. Companion security CI run #44659155014: passed on Ubuntu and Windows; the same run's renderer job passed unit tests, TypeScript checking and production build.

## JSON byte encoding regression

The host protocol accepts JSON text encoded as UTF-8 only. When validators receive byte payloads, they decode strict UTF-8 before JSON parsing; UTF-16/UTF-32 payloads are rejected even though Python's generic JSON byte decoder can auto-detect those encodings. The existing frame-size limit is still measured against the original UTF-8 byte length. This applies to both the v1 authenticated client-frame validator and the separate v2 pairing-frame validator. Regression tests passed in Companion security CI run #44659155014 on Ubuntu and Windows.


## Pairing scope-selection policy (2026-11-21)

- A fresh Android pairing request defaults to the single read-only `status` scope. Other capabilities are explicit user opt-ins; terminal execution, file writes, settings, task/skill management and device administration are never preselected.
- The Android client filters legacy placeholder scopes and never sends an empty scope set.
- The host rejects any requested scope outside the scope ceiling configured when its pairing transaction was created. Trusted local host approval remains authoritative and may narrow the request further; the issued credential contains only the approved scopes.
- Re-pairing preselects the current grants for the same host and may add only the scope the user explicitly chose to request. Android validates the exact granted scope set in the subsequent `auth.ok` response.
- The host's current default ceiling remains `status`, `sessions.list`, and `chat.send`. The current Electron CompanionPanel does not yet expose a scope-ceiling picker or per-request narrowing controls, so other scopes are not available through that UI. The Android client-side picker never widens host policy.

- Android CI run [#44857531977](https://github.com/herbalblitz/emrg/actions/runs/44857531977) passed on commit `a150e6a53314ac3993ca1ac131b66fd4dc74fb47`: debug APK assembly, signature verification, JVM unit tests (including the new least-privilege default tests), lint and artifact upload.
