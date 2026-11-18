# Daemon Protocol and Android Connectivity Findings

Audit date: 2026-11-18. Source reviewed on `master`: `emrg/protocol.py`, `emrg/server/daemon.py`, `emrg/connect.py`, `emrg/gui/daemon_client.js`.

## Existing transport

- Daemon uses WebSocket messages with JSON objects.
- Desktop GUI connects to `ws://127.0.0.1:<EMRGD_PORT>` (port constant 56031).
- First frame must be `{"type":"auth","token":"..."}`; token comparison uses `secrets.compare_digest`.
- Server sends `auth_ok` after successful authentication.
- Authentication is required within a 10-second first-frame timeout.
- Daemon listener is explicitly bound to `127.0.0.1`, not a LAN interface.
- GUI token is stored in the user's `~/.emrg/emrgd.token` file.
- GUI client handles reconnect/restart, per-session subscriptions and session resume.

## Protocol families observed

The Python protocol types include task requests and responses, deltas, tool-start/tool-end events, pong/status, instance identity and evolution records. The daemon message dispatcher also supports command/event families for sessions, model/provider configuration, history, tasks/templates, memories, skills, files/workspace, GitHub login/connect, rewind/compact, sandbox state/grants, and evolution summaries. Exact payload schemas and permissions need to be extracted and tested command-by-command before Android implements them.

## Android connectivity blocker (security-critical)

The Android phone cannot connect to a daemon bound to the host's loopback address. The desktop token file is a local secret and must not be copied into a QR code, shared over an unauthenticated LAN, or exposed in app logs.

**Do not solve this by changing the listener to 0.0.0.0 or using raw port forwarding.**

Recommended design exploration:
1. Host explicitly enables remote pairing from desktop settings.
2. Pairing uses a short-lived one-time code/QR, with user confirmation on host and phone.
3. Establish a TLS-protected transport with certificate identity/pinning strategy and an authenticated, revocable device credential.
4. Use device-scoped permissions and retain existing tool approval policy; pairing must not imply unrestricted tool execution.
5. Add protocol-version negotiation, rate limits, expiry, replay prevention, revoke-all and diagnostic redaction.
6. Keep the current local-only endpoint and desktop token authentication unchanged for existing clients.

This is a design recommendation, not an implemented remote protocol. It requires threat modeling, tests and review before exposing a network endpoint.

## Suggested implementation sequence

- Extract current protocol messages/events and schemas into a versioned client contract.
- Add a mock daemon and tests for auth, stream ordering, errors, reconnect and cancellation.
- Design pairing and remote transport on host side before wiring Android networking.
- Android UI can be built against a fake transport in parallel; no real credentials or remote socket yet.
