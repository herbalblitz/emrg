# Companion TLS Host Identity Lifecycle (H2)

Branch: `feat/android-windows-platform-audit`
Status: policy implemented in a transport-free identity-provisioning module; listener/UI integration and cross-platform CI verification remain pending.

## Goals and threat boundary

TLS protects the companion connection from passive interception and active network tampering. Device credentials provide application authentication and authorization; TLS fingerprint pinning authenticates the host identity. These are independent checks.

The existing daemon WebSocket remains bound to `127.0.0.1`. A future companion WSS service must be a separate, opt-in listener and must not start merely because a certificate exists.

## Identity format

- Generate a dedicated host key and leaf certificate for companion use; never reuse daemon, GUI, SSH, code-signing, or provider keys.
- The certificate fingerprint is SHA-256 over the certificate's DER encoding, formatted as 32 byte pairs for UI display. Android descriptor field `fingerprint_sha256` represents this exact value.
- Certificate SANs must cover every advertised endpoint host (IP SAN for literal IP addresses, DNS SAN for hostnames). Do not rely on CN matching.
- Certificate/key algorithm and lifetime must be selected by implementation review against supported Android/Windows/Linux TLS stacks; prefer modern supported algorithms and short, bounded certificate lifetime.
- Keep key and certificate separate from device credential registry. The registry stores only credential verifiers, never host private keys.

## Provisioning and persistence

1. Provision only after user explicitly enables Companion access in host UI. The current API does not implement UI approval; the caller must gate provisioning.
2. Persist identity across normal restarts to avoid silently changing the pinned fingerprint.
3. Current implementation protects the Windows private key with user-scoped DPAPI and stores the PEM key on POSIX with mode 0600 inside a directory mode 0700. Other OSes and Windows ACL/temp-file review remain required before release. If protection is unavailable, fail closed; never silently write a private key as world-readable.
4. Write atomically; restrict permissions; reject symlinks or unexpected file ownership/permissions where platform APIs permit; avoid backups/sync folders.
5. Never log, export, or include the private key in QR payloads, diagnostic bundles, or pairing metadata.
6. Startup validates key/certificate match, expiry, SANs, and configured fingerprint; corruption is a visible error and must not trigger automatic trust-reset.

## Trust and QR pairing

The host's local trusted UI presents a short-lived QR descriptor containing only protocol version, WSS endpoint, DER SHA-256 fingerprint and expiry. It must not contain device credential, pairing secret, host private key, or daemon token.

The phone parses the descriptor, shows endpoint and full fingerprint, and requires explicit fingerprint confirmation before sending a pairing request. The user compares the fingerprint displayed by the trusted host UI with the phone's value. QR is an out-of-band bootstrap, not an authorization substitute. Host approval remains a separate explicit step.

Descriptor generation must include a bounded expiry and avoid advertising public/untrusted interfaces by default. Endpoint selection and bind address are controlled by the host, not supplied by the phone.

## Rotation and recovery

- Normal renewal with the same key is allowed only if the certificate identity/fingerprint policy and pin update are explicit; default policy should make fingerprint changes visible.
- Key rotation changes the fingerprint and requires a fresh local QR scan and user confirmation on each device; do not accept an unpinned replacement automatically.
- Revoke a device credential independently of certificate rotation. Certificate replacement does not restore revoked devices.
- Identity reset requires explicit local confirmation, warns that all phones must pair again, and atomically replaces the identity.
- If identity is missing or invalid, do not silently regenerate on a host with previously paired devices.

## Listener gate / shutdown

Before a listener may be implemented or enabled, require:
- explicit opt-in setting, default off;
- dedicated port and lifecycle, independent from existing daemon listener;
- selected private interface(s), never automatic `0.0.0.0` exposure;
- authenticated pairing/credential checks, strict frame validation, rate limits and bounded connections;
- active device revocation closes its connections;
- disabling listener cancels pending pairings and closes all remote sockets;
- startup diagnostics disclose only safe endpoint/fingerprint metadata;
- firewall/network exposure is clearly explained.

## Acceptance tests for H2/H3

- Identity persists across restart and fingerprint remains stable.
- Private key is not exposed by APIs, QR, logs or registry metadata.
- Certificate/key mismatch, expiry, invalid SAN and corrupt files fail closed.
- Fingerprint is computed from DER bytes and matches Android pin format.
- Identity reset/rotation changes pin and requires fresh confirmation.
- Listener default-off and loopback listener behavior are regression-tested.
- WSS handshake rejects wrong fingerprint, wrong device credential and unapproved pairing.
- Cross-platform Windows/Linux provisioning and permission behavior is tested.

This document is a security specification, not evidence that these mechanisms are implemented.

## Current implementation

`emrg/server/companion_tls_identity.py` provisions a P-256 self-signed server certificate with SAN entries for explicitly supplied DNS/IP hosts, a bounded 365-day validity, SHA-256 fingerprint, and persistence across restart. Existing identities are validated (key/cert match, validity, SAN coverage) and are not silently regenerated; rotation requires explicit `reset=True`. Windows key bytes are DPAPI-protected; POSIX files are written atomically with restrictive modes. `create_identity_server_context` loads the identity into a TLS 1.2+ context and creates no socket. Unit tests are added; execution is pending CI.

Still not implemented: host UI consent, host identity display/QR generation, Windows ACL verification for directory/temp files, cross-process provisioning lock, rotation UX, or listener integration.

Provisioning and context loading are now serialized with a process-local lock to prevent concurrent threads from generating competing pins. Cross-process locking remains unimplemented.
