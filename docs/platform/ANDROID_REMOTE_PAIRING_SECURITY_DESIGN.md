# Android Remote Pairing — Security Design Proposal

Status: proposal only; no transport or pairing code implemented.
Branch: `feat/android-windows-platform-audit`.

## Scope and security objectives

Android may control an EMRG host over a network, but the Python runtime/providers/tools remain host-side. Pairing must not grant unrestricted execution; each action remains governed by host sandbox policy and host approval.

Protect against:
- LAN/Wi-Fi attackers intercepting, modifying or replaying traffic.
- Unauthorized devices pairing by guessing or reusing a code.
- Lost/stolen phone retaining access.
- Credential theft through logs, backups, QR capture or app export.
- Protocol downgrade / incompatible clients.
- Malicious client requesting dangerous capabilities.
- Network reconnection to a different host masquerading as the paired host.

## Trust boundaries

1. **Host daemon**: authority for identity, authentication, session access, tools, approvals and policy.
2. **Android client**: untrusted remote controller; credentials stored in Android Keystore-backed storage where available.
3. **Network**: hostile by default. Never assume private LAN/Wi-Fi is trusted.
4. **EMRG runtime**: host-owned; client never receives provider API keys, host desktop token, raw shell authority or unrestricted filesystem access.

## Pairing proposal (not implementation)

1. User explicitly enables remote pairing on host. Default off; requires host UI/console confirmation.
2. Host generates an unpredictable one-time pairing secret (minimum 128-bit entropy), short expiry (suggest 2–5 minutes), and a pairing transaction identifier.
3. Host displays QR with host endpoint, host identity/fingerprint, transaction ID and one-time secret. QR is sensitive; never log or persist it.
4. Android scans QR and validates endpoint identity. Host identity must be verified through an explicit fingerprint confirmation or trusted provisioning; no silent acceptance of arbitrary TLS certificates.
5. Client and host perform a modern authenticated key exchange over TLS. Pairing secret is consumed once and bound to the transaction; server validates expiry, attempt limits and proof. Prefer a reviewed standard protocol/library; do not design custom cryptography.
6. Host displays the device name, key fingerprint and requested capabilities; user explicitly approves.
7. Host issues device-scoped credential with least-privilege scopes, expiration and unique device ID. Credential must be revocable and rotatable.
8. Both sides negotiate protocol version and capabilities. Unknown or unsupported mandatory capabilities fail closed.
9. Host records pairing metadata (device ID, public key, scopes, creation/last-use, expiry, revocation) without storing pairing secret.

## Credential/session controls

- Android credential storage: Keystore-backed encryption; exclude credentials from cloud backup/device transfer.
- Host credential store: protected by OS permissions; never print secrets in diagnostics.
- Every request authenticated; use TLS with host identity pinning/verification. No plaintext transport, no desktop token reuse, no embedded shared app secret.
- Include request IDs, session binding and anti-replay protection. Prefer transport-level replay resistance plus short-lived request/session proofs if needed.
- Revocation is immediate at host; host can revoke one device, rotate credentials, disable remote access globally, and expire stale pairings.
- Re-pair after revocation. Never silently re-trust a changed host certificate/fingerprint.

## Authorization and safety

- Pairing scopes are explicit: read status; list/continue sessions; send chat; view tool activity; optional workspace access.
- Tool execution and dangerous operations remain host-owned approval decisions. Pairing is not consent to run tools.
- Sandbox policy is enforced on host; Android cannot raise tier, bypass approval or grant writable roots.
- Host-side remote access must expose only a reviewed API, not the existing unrestricted daemon command surface.
- Limit message size, rate, concurrent requests, session access and pairing attempts. Validate every schema and path/session association.
- On disconnect: cancel or retain work according to explicit host policy; client must reconcile authoritative state on reconnect, not replay non-idempotent commands automatically.

## Pairing and remote access lifecycle

States: Disabled → Pairing window → Pending approval → Paired → Revoked/Expired.
- Pairing window is single-use, short-lived and rate-limited.
- Pending approval cannot issue task commands.
- Paired state has per-device scopes and expiry.
- Revocation disables credential and terminates active device connections.
- Disable remote access closes listeners and invalidates active pairing transactions.
- Host UI/CLI must show active devices and last activity, and support revoke-all.

## Protocol readiness gates

Before implementation:
- Define protocol version/capability negotiation.
- Specify session-scoped streaming, tool lifecycle, errors, cancellation, queue events and reconnect snapshot semantics.
- Build portable fixtures and client/server contract tests.
- Select maintained TLS/identity/pairing implementation; security review required.
- Add host opt-in and revocation UX; verify Windows firewall/network exposure behavior.
- Test no token/secret/prompt/tool arguments leak into logs or QR persistence.

## Explicitly rejected

- Exposing the existing loopback daemon directly to LAN.
- Reusing `~/.emrg/emrgd.token` on Android.
- Embedding a shared secret in APK.
- Trust-on-first-use without explicit host identity confirmation.
- Pairing by static PIN, unlimited QR, or reusable QR secret.
- Granting shell/tool access solely because a device is paired.
- Custom crypto or bypassing host sandbox/approval controls.

## QR descriptor parser implementation note

The Android parser accepts only the versioned credential-free descriptor allowlist, requires an expiry timestamp, rejects expired descriptors, rejects non-integral/non-finite timestamps, and caps the descriptor lifetime at 180 seconds. This is parser/UI groundwork only; it does not prove host identity or pair a device until the real TLS host and approval flow exist.

## Endpoint validation hardening

The WSS pairing endpoint validator now rejects URL user-info, query strings, fragments, non-WSS schemes, missing hosts, and invalid/zero ports. This prevents credential-like query parameters from being smuggled in the QR endpoint and catches malformed socket targets before the pairing state advances. Unit tests cover these rejection cases; the Android workflow is pending.
