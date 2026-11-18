# Client protocol contract: current desktop baseline

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
- TaskResponse reader: `request_id`, `content`, `done`, `delta`; exact event envelope/type and lifecycle still need validation from emitter.
- `tool_start`: request_id, tool_name, tool_call_id, arguments, intent; optional call_label (older frames may omit).
- `tool_end`: request_id, tool_name, tool_call_id, content, error.
- `pong`: identity, uptime_seconds, evolution_count; other fields such as model may be included and ignored by typed readers.
- Full error, cancellation, ordering, snapshots and reconnect semantics remain to be extracted and tested.

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
- [ ] Reconcile protocol.py's transport description with daemon's actual WebSocket transport.
- [ ] Extract authoritative message schemas and event order from daemon emitters/handlers.
- [ ] Add fixture tests for auth, optional-field semantics, streaming, tool lifecycle, cancellation, errors and reconnect.
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
- These findings are source inspection, not runtime integration tests. Android transport remains unimplemented.

## Transport doc correction

`emrg/protocol.py` contains typed message models and JSON serialization; its module header's length-prefix / Unix socket / Windows pipe description is inconsistent with the current daemon and desktop GUI WebSocket implementation. Do not derive transport or framing from that header. Existing desktop behavior remains loopback WebSocket with token auth.
