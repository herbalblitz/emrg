# Android + Windows Implementation Roadmap

Target: preserve all existing EMRG features while delivering Android and Windows experiences.

## Principles

1. **Keep the engine:** Python runtime/daemon remains the source of truth.
2. **Windows continuity:** maintain and improve the existing Electron GUI and packaged runtime.
3. **Android companion:** native Kotlin/Compose client first; no unsafe direct local runtime port.
4. **Protocol-first:** write and test a stable client contract before duplicating UI behavior.
5. **Security-first:** authenticated pairing, encrypted transport, least privilege, visible approvals.
6. **Incremental parity:** deliver feature slices and document limitations; never label incomplete parity as 100%.

## Milestones

### M0 — Repository and contract audit
- Map daemon entry points, protocol envelopes, websocket/HTTP routes, auth and lifecycle.
- Inventory GUI screens, actions, commands and state transitions.
- Identify platform-specific Windows packaging/build/test coverage.
- Baseline test and build results in CI (local execution unavailable in current environment).

### M1 — Client protocol contract
- Document message types, request IDs, streaming, errors, snapshots, reconnect, cancellation.
- Add contract tests for malformed payloads, version mismatch and reconnect.
- Design pairing/authentication without weakening existing local security.

### M2 — Android foundation
- Kotlin, Gradle, Compose, navigation, DI, networking, serialization.
- Secure pairing screen; connection state and diagnostics.
- Keystore token storage, TLS certificate handling, revoke/unpair.
- Mock server and protocol tests; CI builds debug APK.

### M3 — Android core workflows
- Chat transcript, streaming, stop/cancel, markdown/code rendering.
- Sessions: create, list, resume, rename.
- Tool status and explicit approval/rejection.
- Provider/connection settings and safe error handling.

### M4 — Android parity slices
- Workspace browser via scoped storage / daemon capabilities.
- Memory and skills.
- Tasks and scheduled-task controls.
- Rant/evolution history and status.
- Diagnostics, export, device/session revocation.
- Push/notification support only with explicit opt-in and background policy compliance.

### M5 — Windows reliability
- Baseline and fix existing Windows-specific tests.
- Installer/update/uninstall and runtime bundling smoke tests.
- Process lifecycle, daemon restart/reconnect, PATH and shell integration.
- Sandbox/security regression tests.
- Reproducible CI artifacts and checksums.

### M6 — Release gates
- Linux unit and protocol tests.
- Electron renderer/main tests, typecheck, packaging.
- Windows clean-install, upgrade, launch, reconnect, uninstall tests.
- Android lint, unit tests, instrumentation smoke tests, APK/AAB build.
- Security review, dependency scan, signing and release notes.

## Definition of done per feature

- UI flow exists and is accessible.
- Protocol behavior is documented.
- Success, loading, empty, offline, error and cancellation states are handled.
- Security implications are explicit; approvals cannot be bypassed.
- Automated tests cover normal and failure cases.
- Windows/Android platform constraints are documented.
- No secrets, personal data or unbounded logs in diagnostics.

## Immediate work queue

- [ ] Protocol inventory from `emrg/protocol.py`, server and daemon client.
- [ ] Identify daemon transport/authentication and safe remote-access boundaries.
- [ ] Android project scaffold (Kotlin + Compose).
- [ ] Add CI Android build and lint.
- [ ] Establish Windows packaging smoke tests and inspect existing gaps.
