# Companion Electron Local-Control Design

Status: local-control facade implemented and unit-tested; daemon/Electron transport and UI wiring remain incomplete.
Date: 2026-11-19

## 1. Purpose and boundary

Define the narrow trusted-desktop control path required to manage the standalone Companion host service. The Electron application is the local approval surface; Android is an untrusted remote client until it completes the host-approved pairing flow.

This design does not authorize remote chat, tool execution, filesystem access, arbitrary daemon commands, or network-exposed management. The existing desktop daemon listener must remain loopback-only and must not be repurposed as the Android WSS listener.

## 2. Existing code facts

- `emrg/gui/main.js` registers explicit `ipcMain.handle("emrg:…")` handlers inside `registerIpc()`; the main BrowserWindow uses `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, and the fixed `preload.js`.
- Navigation is restricted to the packaged renderer document; external navigation is denied or opened in the system browser.
- `emrg/gui/preload.js` exposes a fixed `emrg` API through `contextBridge.exposeInMainWorld`. It does not expose raw `ipcRenderer`.
- Neither Electron file currently has Companion management handlers or preload methods.
- `CompanionHostService` and `CompanionWssListener` are Python-side components. The WSS listener is standalone and must be explicitly started; it is not daemon-managed.
- `CompanionLocalControl` (`emrg/server/companion_local_control.py`) now provides a narrow in-process allow-list for `status`, `pairing.pending.list`, `pairing.decide`, and `pairing.cancel`. It validates exact payload keys, bounds the pending projection, and returns no credential or raw key material. Its unit tests cover the projection and malformed requests. It is not yet instantiated by the daemon or exposed through Electron IPC.
- The desktop daemon's local IPC authentication material is not a mobile companion credential and must never be returned to the renderer, QR code, Android client, or Companion registry.

## 3. Required command boundary

Add a dedicated daemon-side Companion controller/facade. It must accept only the following semantic operations through the existing authenticated local desktop-daemon channel:

| Operation | Required behavior |
| --- | --- |
| status | Return enabled/running state, bind endpoint, active connection count, and non-secret TLS fingerprint |
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

- The daemon owns the `CompanionHostService`, persistent TLS identity manager, and `CompanionWssListener` instances.
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
- preload exposes only the named methods and no generic IPC primitive;
- approval UI displays and approves the exact immutable device-key fingerprint/workspace/scopes;
- no secret material appears in logs, renderer events, or error messages.

### End-to-end
- Windows and Linux daemon/Electron tests cover enable, identity provisioning, QR creation, pending request display, host approval/rejection, device revocation, and shutdown;
- Android pairing remains blocked until device-key generation, v2 transport, proof-of-possession, secure credential persistence, and real-device validation are complete;
- no test may treat a standalone WSS listener test as proof of end-to-end Android pairing.

## 8. Explicit non-goals

This local-control design does not implement the Android v2 network pairing route, remote chat, tool execution, remote filesystem access, or a general-purpose remote administration API.
