# Companion Daemon Lifecycle and IPC Audit

Status: source audit; no daemon integration implemented.
Date: 2026-11-19

## 1. Executive finding

The repository has a functioning authenticated desktop WebSocket IPC channel and a separately implemented Companion WSS listener, but no safe integration point has yet been implemented between them. The desktop daemon channel is a broad application protocol, not a Companion-specific management API. Do not treat its existence as proof that the Companion controller is integrated.

The Companion listener must remain a separate server with its own TLS identity, device credentials, policy, and connection lifecycle. The desktop daemon token must never become a mobile credential or be sent to Android.

## 2. Verified source boundaries

### Desktop daemon transport

- EmrgServer.serve() binds the existing desktop WebSocket server to 127.0.0.1:EMRGD_PORT.
- It creates a fresh secrets.token_urlsafe(32) authentication token and writes the canonical ~/.emrg/emrgd.token file.
- EmrgServer._handle_client() requires the first frame to contain type=auth and the daemon token, then adds the socket to the general authenticated-connection set.
- After authentication, the same connection enters the broad existing message dispatcher. The dispatch surface includes session, task, approval, settings, filesystem-adjacent, agent, scheduler, and account operations; it is not a narrowly scoped Companion channel.
- _process_message() is the second dispatch layer; task is intercepted earlier in _handle_client(). New commands must account for both dispatch paths and must not bypass the established validation/error conventions.
- Electron daemon_client.js reads the local token file, connects to the desktop daemon, authenticates, and sends existing application commands.

### Daemon lifecycle

- EmrgServer.serve() creates the desktop listener and starts scheduler/config/background tasks before serving.
- Every normal stop path funnels through _shutdown_all().
- _shutdown_all() currently cancels background loops, stops/waits for the scheduler, closes the LLM client, and cleans the desktop port file.
- There is no Companion host service, TLS identity manager, or Companion listener owned by the daemon today.
- The Companion listener has independent async start(), stop(), and close() methods and registers a host-state callback at construction. If integrated, its disposal and callback unregistration must be part of daemon teardown.

### Electron boundary

- main.js registers explicit IPC handlers and creates a BrowserWindow with context isolation, node integration disabled, sandbox enabled, and a fixed preload.
- preload.js exposes a fixed method allowlist through contextBridge; it does not expose raw ipcRenderer.
- Main-process sender validation is not a replacement for daemon-side request validation.
- The daemon token file is a same-user local IPC secret. The current desktop protocol does not establish a separate per-renderer authorization identity or a Companion-only capability. A new management command added to the broad desktop dispatcher would inherit the desktop channel trust model; it must not be described as a cryptographically isolated Electron-only API.

## 3. Integration hazards

1. Do not reuse the desktop WebSocket listener for mobile. It has a much broader command surface and different authentication semantics.
2. Do not put Companion credentials or pairing proofs in desktop logs, generic event broadcasts, or renderer state.
3. Do not add an unrestricted companion.* method dispatcher. Every operation needs a fixed command identifier, exact payload schema, bounded response, and explicit enabled-state checks.
4. Do not trust the renderer to supply transaction authority. Approve/reject/cancel must resolve a daemon-owned pending transaction and compare immutable device key, workspace, and scope data.
5. Do not create the WSS listener as an incidental side effect of importing a module or constructing the GUI. It must be explicitly configured, opt-in, and owned by the daemon.
6. Do not create TLS identity or bind a socket before validating the complete startup policy. A failed startup must dispose partially created resources and unregister callbacks.
7. Do not omit listener shutdown from _shutdown_all(). Shutdown must close active clients, stop accepting, wait for server closure, clear pending transactions, and unregister callbacks before the daemon exits.
8. Do not claim Electron-only isolation from main-process sender checks alone. The daemon receives an authenticated local socket request, not Electron webContents identity. If the threat model includes other same-user processes, define and implement an additional capability/OS boundary rather than assuming the renderer check propagates to Python.

## 4. Recommended staged implementation

### Stage A — transport-independent controller

Create a small daemon-owned controller that composes validated Companion configuration, persistent TLS identity manager, CompanionHostService, and CompanionWssListener. The controller should expose explicit typed methods, not a generic command router. It should not import Electron or know about renderer objects. Unit tests should cover enable/disable, idempotent start/stop, identity failures, callback cleanup, pending transaction invalidation, and active-client shutdown.

### Stage B — daemon lifecycle ownership

- Construct the controller in EmrgServer only after configuration and runtime paths have been validated.
- Keep the listener disabled by default unless the user explicitly opts in.
- Start it only after the desktop daemon startup prerequisites succeed.
- Add a single bounded cleanup step in _shutdown_all() before shared runtime state is released.
- Ensure startup failure rolls back listener and callback resources.
- Do not let a config reload silently widen bind address or re-create TLS identity.

### Stage C — fixed desktop IPC operations

Before adding desktop dispatcher commands, decide whether the existing same-user daemon-token trust model is sufficient. If it is, document that scope explicitly and keep operations narrow. If not, implement a separate authenticated capability channel with a clear OS-specific security model.

Any command must accept only exact JSON object fields and types; reject unknown fields and oversized values; never accept caller-supplied filesystem paths for approved workspaces; derive transaction/device/workspace/scope authority from daemon-owned records; return only bounded non-secret metadata; and avoid generic broadcast delivery for credentials, signatures, or pairing frames.

### Stage D — Electron local UI

Implement the separate Electron contract in COMPANION_ELECTRON_LOCAL_CONTROL_DESIGN.md. Main-process sender checks, fixed preload methods, and renderer approval UX are all required, but none should be mistaken for daemon-side authorization.

### Stage E — end-to-end verification

Test on Windows and Linux: opt-in startup and disabled-by-default behavior; persistent TLS identity and fingerprint display; pending request display and exact local approval; one-time Android proof-of-possession redemption; device revocation and active connection closure; disable/re-enable races and full daemon shutdown; absence of mobile credential material in desktop daemon auth, logs, QR payloads, and renderer events.

A green unit test for the standalone listener or host pairing service is not an end-to-end Android/Windows pairing test.

## 5. Current completion status

| Area | Status |
| --- | --- |
| Host pairing v1/v2 primitives | Implemented and unit-tested |
| TLS identity provisioning/validation | Implemented; hardening gates remain |
| Standalone WSS status/session listing | Implemented; explicitly started by caller |
| Metadata display sanitization | Implemented and regression-tested |
| Daemon-owned Companion controller | Not implemented |
| Daemon startup/config/shutdown integration | Not implemented |
| Narrow desktop IPC operations | Not implemented |
| Electron Main/Preload controls | Not implemented |
| Android device-key lifecycle and live v2 pairing | Not implemented |
| Real Android ↔ Windows end-to-end validation | Not implemented |
