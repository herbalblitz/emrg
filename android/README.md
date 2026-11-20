# EMRG Android companion

Native Kotlin + Jetpack Compose companion client for an EMRG host running on Windows.

## Current state

The Android app has a native UI foundation and a partially integrated secure host-connection flow.

Implemented in the repository:

- Local chat UI with an offline/demo transport. This remains a mock and does not execute an EMRG agent.
- Local session/chat state and bounded persistence.
- Versioned pairing descriptor parsing, QR camera scanning, and manual descriptor entry.
- Host identity confirmation using a SHA-256 TLS certificate fingerprint.
- Device-key and host-scoped credential handling using Android Keystore-backed storage.
- Pairing-v2 exchange over WSS with certificate pinning, bounded messages, replay/transaction checks, and host-side approval.
- Authenticated WSS connection after pairing, with read-only host status and session-metadata requests.
- Connection-state handling for reconnect, refresh, disconnect, and stale callback invalidation.

The authenticated remote surface is intentionally read-only at this stage. The host exposes status and an allowlisted session metadata snapshot; it does not expose remote chat execution, tool execution, filesystem access, or remote pairing approval.

## Not yet complete / not yet proven

- End-to-end pairing and reconnect on physical Android and Windows devices.
- Physical-device validation of Android Keystore persistence and credential recovery/revocation.
- Live chat send/stream/cancel against the host daemon.
- Live tool events, tool approval workflows, and other agent operations.
- Full session create/resume/rename/delete over the host transport.
- Provider/settings, memory, skills, scheduled tasks, evolution, diagnostics, and broader original-EMRG feature parity.
- Android instrumentation/device testing and a reproducible Gradle wrapper build.

Do not treat a successful Android CI build or unit-test run as proof of physical-device pairing or full feature parity.

## Architecture and security boundary

The Python runtime, providers, tools, memory, skills, subagents, scheduler, sessions, and sandbox remain on the host as the source of truth. Android is a companion client, not a direct port of the local runtime.

The Android client must not copy desktop credentials or connect by exposing the host's loopback daemon on the LAN. Remote access is opt-in on the host and uses a separately provisioned TLS identity, explicit device pairing/approval, certificate fingerprint pinning, and scoped revocable credentials.

The current remote WSS API is limited to authenticated read-only status and session metadata. Do not enable chat or tool execution until workspace authorization, host-owned approvals, sandbox boundaries, protocol negotiation, and reconnect/reconciliation behavior have been implemented and reviewed.

## Build and CI

The `.github/workflows/android.yml` workflow assembles a debug APK, verifies its signature against the repository's shared debug keystore, runs JVM unit tests, runs lint, and uploads the APK and test reports.

CI does not replace Android instrumentation tests, physical-device testing, or an end-to-end Windows host validation. Check the GitHub Actions run for the exact commit before treating a build as verified.
