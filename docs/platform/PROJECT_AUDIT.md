# EMRG Platform Audit (Android + Windows)

**Repository:** herbalblitz/emrg  
**Audited branch:** master  
**Audit date:** 2026-11-18  
**Work branch:** feat/android-windows-platform-audit

## Executive summary

EMRG is an existing Python agent runtime with a mature Electron GUI and Windows packaging. This is not a blank project and should not be rewritten or forked into a disconnected implementation. Preserve the Python daemon/runtime and build platform-specific clients around its supported protocol.

The repository's root README describes the Electron GUI as the main entry point, the TUI and daemon as the shared engine, and a self-evolving agent with sessions, tools, scheduled tasks, memory and feedback cycles.

## Repository facts verified from GitHub

- Default branch: `master`; this fork is based on `argszero/emrg`.
- Python package: `emrg`, requires Python 3.11+, currently declares version 0.3.57 in `pyproject.toml`.
- Root tree: 808 entries, recursive tree response not truncated.
- Core implementation: `emrg/`; daemon/server, protocol, agent, sessions, memory, tools, skills and sandbox modules are present.
- Electron GUI: `emrg/gui/`; Electron main/preload/connection-manager and React/Vite renderer.
- Renderer includes component and library tests.
- Existing Windows-related launchers and packaging assets exist (`bin/*.cmd`, `emrg/_win.py`, `packaging/`, Windows release workflow and runtime lock).
- CI workflows exist under `.github/workflows/` for tests and release builds.
- No Android-native Gradle/Kotlin project was found in the repository tree.

## Platform strategy

### Windows: improve and verify the existing implementation

Do not replace the current Electron GUI. Prioritize reproducible builds, Windows lifecycle, installer/uninstaller, bundled runtime, PATH handling, sandbox behavior, and test coverage. Keep parity with the existing EMRG experience.

### Android: native companion client first

Create an Android app (Kotlin + Jetpack Compose) that connects to a running EMRG daemon over a secure, explicit pairing flow. Android is initially a remote client rather than an attempt to port the full Python self-evolving runtime into a phone.

Core safety principles:
- Pair only after explicit user confirmation; use a short-lived pairing code or QR.
- Secure transport (TLS) and authenticated sessions; do not expose daemon unauthenticated to the public internet.
- Store secrets/tokens in Android Keystore-backed storage.
- Require confirmation for high-impact tool actions; expose approval details.
- Use Android scoped storage / SAF for file access; never assume unrestricted filesystem access.
- Respect background execution limits; reconnect and notifications must be opt-in and bounded.

## Initial inventory (feature groups)

| Area | Existing core/GUI | Android companion target |
|---|---|---|
| Chat and streaming | Agent + daemon + GUI transcript | Chat, stream, cancel |
| Sessions | Session management and UI | List, create, resume, rename |
| Settings/providers | Config + settings UI | Connection and provider status; safe config controls |
| Tools/approvals | Tool runtime and approval dialog | Tool activity and explicit approval/rejection |
| Workspace/files | Workspace/file-tree/editor UI | SAF-backed browsing and safe previews |
| Memory | Memory subsystem/dialog | View/search, controlled edits |
| Skills | Skill system/dialog | Browse, enable/disable where protocol supports |
| Tasks/scheduler | Scheduled task subsystem/UI | View, trigger, edit with confirmation |
| Rant/evolution | Feedback and evolution cycle | Submit feedback; status/history |
| Logs/diagnostics | Daemon and GUI logs | Status, diagnostic export |
| Security | Sandbox roots and approval policies | Pairing, device revoke, permissions, approvals |

## Validation status

This is a GitHub-source audit. No repository checkout was available in this execution environment; local tests, desktop builds, Android builds and runtime behavior have **not** been executed. Feature descriptions are based on source-tree and README evidence and require code-level validation before declaring feature parity.

## Next steps

1. Read protocol and daemon connection implementation in detail; document API/events and pairing gap.
2. Establish a clean CI baseline on the feature branch.
3. Add Android app scaffold and protocol contract tests.
4. Harden Windows build/packaging and add smoke checks only where gaps are demonstrated.
5. Implement feature slices end-to-end, with test coverage and reviewable commits.
