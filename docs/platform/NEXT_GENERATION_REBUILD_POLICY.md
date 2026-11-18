# EMRG Next Generation — Rebuild / Refactor Policy

## User-approved direction

The user explicitly authorizes rebuilding weak, buggy or outdated parts rather than preserving them blindly. The implementation must remain based on the original EMRG source and preserve its core behavior and feature set.

## Product objective

Deliver a better-maintained EMRG implementation for Windows and Android, with security, reliability, performance, UX and testability improvements. Treat this as a modernization of EMRG—not a disconnected clone.

## Ground rules

1. **Source fidelity:** read and map original implementation before changing it. Keep feature intent and semantics, protocol behavior and user data compatibility unless a documented migration is required.
2. **Replace weak code:** redesign modules when evidence shows correctness, security, maintainability, UX or performance problems. Do not preserve code solely for compatibility.
3. **No blind rewrites:** each replacement has a spec, migration plan, test plan, and parity mapping back to original behavior.
4. **Incremental slices:** maintain runnable, reviewable commits. Avoid a giant rewrite that loses functionality.
5. **Security by design:** threat-model remote access, secrets, approvals, sandbox, workspace, device pairing, transport and update path.
6. **Data preservation:** backwards-compatible reads where practical; migrations are transactional, backed up, idempotent and tested. Never silently delete sessions/config/memory.
7. **Quality gates:** tests, lint, type checks, build, platform smoke tests and security checks are required. Do not claim passing without executing.
8. **Honest status:** distinguish inspected, implemented, committed, built, tested and released.

## Target architecture

### Shared EMRG core
- Preserve Python agent runtime, provider abstraction, tools, memory, skills, subagents, scheduler, session model and sandbox policy.
- Refactor internal boundaries for typed protocol contracts, observability and test seams.
- Keep core independent of UI clients.
- Define versioned client/daemon protocol; add compatibility tests.

### Windows
- Keep Electron UI where it is stable and useful; rebuild or replace weak screens/components if identified.
- Improve daemon lifecycle, packaging, installation/update/uninstall, secure local IPC, crash recovery and performance.
- Preserve existing configuration and session formats.
- CI for clean Windows installation, launch, upgrade, recovery and uninstall.

### Android
- Native Kotlin + Compose client.
- Initially remote companion to host runtime; do not assume unrestricted phone-side Python runtime.
- Secure explicit pairing, encrypted authenticated transport, device revocation, protocol compatibility.
- Chat/stream/cancel, sessions, tool approvals, settings, workspace via SAF, memory, skills, scheduler, feedback/evolution, diagnostics.
- Background service/notifications only where Android policy and user opt-in permit.

## Rebuild criteria (any module)

- Security risk or unsafe trust boundary
- Repeated bugs, unclear state or untestable coupling
- Unsupported/obsolete dependencies
- Poor performance or memory use
- Platform incompatibility
- UI accessibility or interaction defects
- Duplicated implementation with inconsistent behavior

## Workflow per module

1. Inspect original source, history, tests, callers and data formats.
2. Record current behavior and edge cases.
3. Write the new design and parity checklist.
4. Implement on the feature branch, with migration if needed.
5. Add automated tests and regression tests.
6. Run tests/builds in the available environment/CI.
7. Document behavior differences, risk, and status.
8. Commit small, coherent changes.

## First candidate work

- Protocol contract and message schemas: document/version/test before remote client access.
- Daemon connectivity: local-only bind must remain safe; design opt-in remote pairing without exposing unauthenticated daemon.
- Android scaffold: improve to production-ready navigation/theme/state architecture and mockable transport.
- Windows: baseline current build and test coverage; focus only on evidenced weaknesses rather than rewrite wholesale.

## Release definition

"Feature complete" requires parity checklist with every original feature classified as:
- preserved and tested,
- redesigned and tested,
- intentionally changed with explanation,
- or explicitly deferred with user-facing limitation.

No release or PR merge into `master` without the user's approval.
