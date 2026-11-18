# Client protocol contract: current desktop baseline

Audit scope: branch `feat/android-windows-platform-audit`, reviewed 2026-11-18.
This document records observed behavior, not a proposal for remote access. Android
must not implement a network client against this baseline until the host-side
pairing/transport design is reviewed and a versioned contract is agreed.

## Important documentation/source mismatch

`emrg/protocol.py` describes length-prefixed JSON over IPC (Unix socket /
Windows named pipe), but the currently inspected daemon and desktop GUI client
connect over WebSocket to `ws://127.0.0.1:56031`. The daemon's fixed loopback
binding and first-frame token authentication are the operative desktop behavior.
Treat this as a documentation/architecture mismatch to resolve; do not expose
the existing endpoint to LAN as a shortcut.

## Authentication and connection

1. Desktop reads the local token from `~/.emrg/emrgd.token`.
2. Client opens WebSocket to loopback port 56031 (proxy disabled for Python).
3. First frame is JSON: `{"type":"auth","token":"…"}`.
4. Server responds with `{"type":"auth_ok"}` after successful auth.
5. Auth deadline is 10 seconds. Client must treat failure/timeout as terminal for
   the current connection attempt; the GUI implementation stops retrying after
   auth failure to avoid retry storms.

This credential is a local secret. Do not place it in Android, QR payloads,
logs, issue reports, or any remote pairing mechanism.

## Observed task/event envelope

These fields are grounded in `emrg/protocol.py` and the desktop GUI client.
The daemon emits other event types and command replies; this is only a partial
inventory.

### Client task request

Required/ordinary fields:
- `type`: `task`
- `id`: request UUID
- `session_id`: session key
- `cwd`: working directory
- `prompt`: user prompt
- `timestamp`: ISO datetime

Optional fields:
- `images`: image descriptors (sent only when non-empty)
- `sandbox`: sandbox mode (sent only when truthy)
- `tools`: tool-name allowlist (sent whenever not null; an empty list means
  explicitly no tools, which differs from omission)

Example (illustrative only):
```json
{"type":"task","id":"<uuid>","session_id":"<session>","cwd":"<path>","prompt":"Hello","timestamp":"<ISO-8601>","tools":[]}
```

Do not assume omission of `tools` and `tools: []` are equivalent.

### Stream / terminal events

- TaskResponse parser consumes `request_id`, `content`, `done`, and `delta`.
  The event type/envelope is determined by the daemon/client handling path;
  verify exact wire type before building a new client.
- `tool_start`: request id, tool name, tool-call id, arguments object, intent,
  optional batch call label (older frames may omit it).
- `tool_end`: request id, tool name, tool-call id, content and error boolean.
- `pong`: identity, uptime and evolution count; the daemon may include fields
  such as model that older typed readers ignore.
- Terminal/cancellation/error ordering, turn snapshots, and full payload schemas
  still require source-level extraction and contract tests.

## GUI request/reply mapping observed

The GUI correlates these command types to response frame types. Mapping is not
an exhaustive server schema; payload fields and authorization semantics must
be checked in daemon handlers before use.

| Command | Reply frame |
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
| github_connect / disconnect / connect_web | respective *_result |
| list_files | files_list |
| read_file | file_content |
| task_create / task_update / task_delete | task_result |
| task_template_list | templates_list |
| task_template_create / update / delete | template_result |

## Compatibility and safety requirements for any future client

- Keep existing desktop local protocol behavior intact unless a migration and
  regression plan is approved.
- Add explicit protocol version/capability negotiation before remote clients.
- Unknown fields should be handled compatibly; unknown command types must not
  silently invoke privileged operations.
- Preserve per-session/cwd isolation and request correlation.
- Cancellation and terminal frames must be idempotent; clients should recover
  state on reconnect instead of assuming no event was missed.
- Tool approval policy is host-owned. Device pairing alone must not grant
  execution approval.
- Pairing credentials must be device-scoped, revocable, expiring/rotatable and
  protected by TLS with a reviewed host identity strategy.
- Diagnostics must redact prompts, tokens, filesystem paths and tool secrets.

## Verification queue

- [ ] Reconcile `protocol.py` transport description with actual WebSocket daemon.
- [ ] Extract authoritative schemas from daemon handlers and event emitters.
- [ ] Add fixture-based tests for auth, task request optional-field semantics,
      stream order, tool lifecycle, cancellation, errors, reconnect/snapshot.
- [ ] Establish protocol version and capability negotiation.
- [ ] Threat model remote pairing before implementation.
