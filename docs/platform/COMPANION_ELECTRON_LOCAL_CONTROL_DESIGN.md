# Companion Electron Local-Control Design

Status: daemon-owned opt-in WSS lifecycle, protected TLS identity loading, transaction-bound v2 QR creation, fixed Electron Main/Preload IPC, and focused tests are implemented in source; CI for the latest changes, React approval/QR UI, and live Android-to-Windows pairing remain incomplete.
Date: 2026-11-20

## 1. Purpose and boundary

Define the narrow trusted-desktop control path required to manage the standalone Companion host service. The Electron application is the local approval surface; Android is an untrusted remote client until it completes the host-approved pairing flow.

This design does not authorize remote chat, tool execution, filesystem access, arbitrary daemon commands, or network-exposed management. The existing desktop daemon listener must remain loopback-only and must not be repurposed as the Android WSS listener.

## 2. Existing code facts

- `emrg/gui/main.js` registers explicit `ipcMain.handle("emrg:…")` handlers inside `registerIpc()`; the main BrowserWindow uses `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, and the fixed `preload.js`.
- Navigation is restricted to the packaged renderer document; external navigation is denied or opened in the system browser.
- `emrg/gui/preload.js` exposes a fixed `emrg` API through `contextBridge.exposeInMainWorld`. It does not expose raw `ipcRenderer`.
- Electron Main and Preload now have three fixed Companion IPC methods (status, pending list, approve/reject), with primary-window/top-frame/local-file URL checks in Main. The React renderer has not yet consumed these methods.
- `CompanionRuntime` is daemon-owned. It stays inert until the trusted local renderer explicitly starts it with an exact bind IP, port, and workspace directory. It provisions or validates the persistent TLS identity, enables the host, starts the WSS listener, creates a short-lived Pairing v2 transaction restricted to `status`, and returns a credential-free v2 QR descriptor containing that transaction ID. Stop and daemon shutdown disable the host and close the listener. The listener loads the protected identity through `create_identity_server_context`, including Windows DPAPI unprotection; direct PEM paths remain a compatibility/test-only construction option.
- `EmrgServer` owns one `CompanionHostService` at `~/.emrg/companion/credentials.json` and a `CompanionLocalControl` facade. The host remains disabled by default; construction does not enable a listener or open a socket.
- The authenticated desktop-daemon protocol routes the fixed `companion_local_control` message into either the lifecycle controller (`listener.start`, `listener.stop`) or the narrow facade (`status`, `pairing.pending.list`, `pairing.decide`, `pairing.cancel`). Listener startup rejects wildcard/non-unicast binds and requires an existing workspace directory; the daemon derives the workspace ID and fixes the initial grant to `status` rather than accepting renderer-supplied scopes.
- Electron Main exposes fixed handlers for status, pending-list, approve/reject, listener start, and listener stop. Every handler validates that the caller is the primary BrowserWindow's top-level local `file://` renderer. Preload exposes only corresponding named methods; no generic IPC or arbitrary daemon command is exposed.
- The lifecycle path now provisions TLS, starts/stops the WSS listener, enables/disables the host, and creates a transaction-bound v2 QR descriptor. The React renderer does not yet call the new lifecycle methods or display the QR/pending approval UI, so a user-facing pairing flow is not complete.
- The desktop daemon's local IPC authentication material is not a mobile companion credential and must never be returned to the renderer, QR code, Android client, or Companion registry.

## 3. Required command boundary

The daemon-side controller/facade now accepts only the implemented subset below through the existing authenticated local desktop-daemon channel. The remaining operations are future work:

| Operation | Required behavior |
| --- | --- |
| status | Implemented: return host enabled state and bounded pending count. Detailed listener state and active connection count remain future work |
| listener.start | Implemented: exact bind IP/port and existing workspace directory; provisions TLS identity, starts WSS, creates status-only Pairing v2 transaction and credential-free v2 QR descriptor |
| listener.stop | Implemented: disable host policy and close WSS listener/clients |
| identity.ensure | Provision or validate the persistent TLS identity; never silently replace a corrupt/missing identity |
| pairing.begin | Create one short-lived host transaction and return a credential-free v2 QR descriptor |
| pairing.pending.list | Return bounded pending requests with device label, device ID, public-key fingerprint, workspace, scopes, approval state, and expiry. The current host service projection exposes only the SHA-256 public-key fingerprint, never raw public key, client nonce, challenge bytes, signature, or issued credential |
| pairing.decide | Approve/reject one exact pending transaction; approval binds its immutable device key, workspace, and scopes |
| pairing.cancel | Cancel one exact pending transaction |
| devices.list | Return bounded non-secret registered-device metadata |
| devices.revoke | Revoke one exact device ID and invalidate its active identity |

The names above are semantic operation identifiers for the implementation, not existing protocol commands. Do not add a generic `companion.call`, arbitrary method dispatch, arbitrary path argument, raw registry access, or a command that accepts client-supplied filesystem paths.

Every request must have an exact schema, strict type/length checks, duplicate-key rejection where JSON is used, bounded responses, and safe generic errors. The daemon must independently enforce host-enabled state, transaction lifecycle, exact scope subset, and device/key binding. Renderer-side validation is only defense in depth.

## 4. Electron Main and Preload requirements

### Main process

- Register dedicated handlers only from `registerIpc()`; keep all Companion operations in a small adapter module rather than expanding unrelated session handlers.
- Validate the invoking `webContents`: it must be the primary application window, the top-level frame, and the expected local renderer origin. Reject subframes, preview WebContents, destroyed windows, and unexpected origins.
- Validate each payload in the main process before forwarding it to the daemon facade.
- Never accept a caller-provided socket address, filesystem path, credential, TLS private key, or arbitrary command name.
- Do not log QR descriptors, credentials, signatures, private-key material, or full pairing frames.
- When the window closes or daemon exits, do not leave an approval operation in an ambiguous state; daemon transaction expiry remains authoritative.

### Preload

Expose only named methods, for example:
- `companionStatus()`
- `companionEnsureIdentity()`
- `companionBeginPairing(options)`
- `companionListPendingPairings()`
- `companionDecidePairing({ pairingId, decision })`
- `companionCancelPairing({ pairingId })`
- `companionListDevices()`
- `companionRevokeDevice({ deviceId })`

These are proposed method names, not implemented APIs. Each method must invoke one fixed channel with a validated narrow payload. Do not expose `ipcRenderer`, generic `invoke(channel, payload)`, event subscription wildcards, or daemon connection objects.

## 5. Approval UX and security rules

Before enabling approval, the renderer must show the exact pending request:
- device display label and stable device ID;
- public-key algorithm and full SHA-256 public-key fingerprint;
- host identity/workspace;
- exact requested scopes;
- transaction expiry and remaining lifetime.

Approval must require a deliberate user action on that exact transaction. Do not use a global "approve all", implicit approval, approval by device label alone, or a renderer-supplied replacement public key/workspace/scope set. The daemon must compare the approved immutable transaction data, not trust values echoed back from the renderer.

Reject and cancel must be distinct explicit actions. Revocation must identify the exact registered device and explain that active connections will be terminated. Fingerprints should be copyable and visually grouped; do not truncate the only fingerprint shown at decision time.

The UI must clearly distinguish:
- listener stopped vs host disabled;
- TLS identity unavailable vs listener not started;
- pending approval vs approved vs redeemed;
- active device vs revoked device.

## 6. Lifecycle and concurrency

- The daemon owns `CompanionRuntime`, `CompanionHostService`, persistent TLS identity, and `CompanionWssListener` instances.
- Startup is opt-in and driven by explicit validated configuration. Do not bind LAN interfaces unless the separate listener policy permits a specific unicast address and the user explicitly enabled it.
- Disable/revoke must close affected active WSS connections and invalidate pending transactions. Existing listener callbacks must remain race-safe across disable/re-enable and startup/shutdown.
- Daemon shutdown must stop the listener, close clients, clear pending pairing transactions, and unregister callbacks before releasing service state.
- Repeated start/stop/disable/revoke operations must be idempotent or return a safe conflict; no duplicate listeners or orphan callback registrations.
- TLS identity rotation is explicit and must not silently invalidate or replace identity while the listener is active.

## 7. Required tests before integration is considered complete

### Daemon facade
- each allowed operation has schema, authorization, disabled-state, and response-bound tests;
- unknown operations and extra fields are rejected;
- renderer-supplied paths, scopes, keys, credentials, and arbitrary commands cannot override daemon-owned values;
- concurrent approve/reject/cancel/redeem/revoke operations preserve one-time transaction semantics;
- listener start/stop and daemon shutdown close all clients and leave no active listener.

### Electron
- only the primary top-level renderer can invoke Companion handlers;
- preview WebContents, subframes, external origins, destroyed windows, and malformed payloads are rejected;
- preload exposes only named Companion methods and no generic IPC primitive; static sender-origin guards cover the fixed lifecycle and approval handlers; renderer interaction and runtime integration tests remain required;
- approval UI displays and approves the exact immutable device-key fingerprint/workspace/scopes;
- no secret material appears in logs, renderer events, or error messages.

### End-to-end
- Focused Windows and Linux tests cover runtime enable/disable, identity provisioning, transaction-bound QR creation, daemon listener start/stop routing, listener shutdown, and fail-closed startup. Full Electron↔daemon and Android↔Windows E2E remain required;
- Android pairing remains blocked until device-key generation, v2 transport, proof-of-possession, secure credential persistence, and real-device validation are complete;
- no test may treat a standalone WSS listener test as proof of end-to-end Android pairing.

## 8. Explicit non-goals

This local-control design does not implement the Android v2 network pairing route, remote chat, tool execution, remote filesystem access, or a general-purpose remote administration API.
