# EMRG Android companion

Native Kotlin + Jetpack Compose companion client.

## Current state

- Interactive chat UI with local mock transport (send/receive, scroll to latest).
- Explicit offline/demo indicator and warning; no real host connection or agent execution.
- Secure pairing, session management and protocol networking are **not implemented**.

## Architecture/security boundary

The existing host daemon is loopback-bound and uses desktop credentials. Android
must not connect by copying that token or by exposing the daemon on LAN.

Before real networking, implement and review:
- explicit host-side remote enablement and device pairing/revocation
- authenticated encrypted transport and host identity verification
- expiring, device-scoped, revocable credentials and replay protection
- version/capability negotiation, reconnect/snapshot semantics
- host-owned tool approvals and least-privilege authorization

The Python runtime remains on the host. The current `DemoTransport` is a UI-only
fake that can be replaced behind the `ChatTransport` interface once the contract
and security design are approved.

## Build

CI workflow: `.github/workflows/android.yml` runs debug APK assembly and lint.
Build status must be checked on GitHub Actions; no local build is claimed.
