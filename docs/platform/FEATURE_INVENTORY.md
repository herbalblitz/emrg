# EMRG Feature Inventory

This inventory is a first-pass source-tree audit; confirm exact behavior against code before marking complete.

## Core runtime
- [ ] Agent orchestration / prompts / provider integration
- [ ] Daemon lifecycle and client reconnect
- [ ] Protocol and streaming events
- [ ] Session persistence, resume, rename, rewind/cancel
- [ ] Tool registry and tool execution
- [ ] Tool approvals and permission policy
- [ ] Workspace and file operations
- [ ] Sandbox roots and platform-specific sandboxing
- [ ] Memory storage, retrieval and editing
- [ ] Skills and extensions
- [ ] Subagents / delegation
- [ ] Scheduled tasks, templates and trigger-on-demand
- [ ] Rant feedback and self-evolution cycle
- [ ] Logs, diagnostics and status
- [ ] CLI and TUI

## Existing desktop GUI (Electron)
- [ ] First-run/onboarding and configuration
- [ ] Chat transcript and composer
- [ ] Ask/Auto modes
- [ ] Streaming and stop
- [ ] Session list and switching
- [ ] Results panel
- [ ] Settings and provider configuration
- [ ] Approval dialogs
- [ ] Workspace / file tree / editor
- [ ] Memory dialog
- [ ] Skills dialog
- [ ] Task scheduler dialogs
- [ ] Rant dialog and evolution feedback
- [ ] GitHub/device login flows where applicable
- [ ] Navigation, accessibility, localization, themes

## Windows
- [ ] Windows command wrappers and daemon startup
- [ ] Process management and shutdown/restart
- [ ] Runtime bundling and dependency installation
- [ ] Installer, PATH, shortcuts and uninstall
- [ ] GUI packaging and asset integrity
- [ ] Sandboxing/security behavior
- [ ] Upgrade and rollback
- [ ] Clean-machine smoke test
- [ ] CI artifact and checksum validation

## Android target
- [ ] Secure device pairing/unpair/revoke
- [ ] Connection diagnostics and reconnect
- [ ] Chat + streaming + cancellation
- [ ] Session management
- [ ] Tool activity + approvals
- [ ] Workspace via SAF/capability APIs
- [ ] Memory read/search/edit
- [ ] Skills management
- [ ] Scheduled tasks
- [ ] Rant/evolution feedback and status
- [ ] Notifications (opt-in)
- [ ] Settings, logs and diagnostic export
- [ ] Accessibility, adaptive layout and offline/error states

## Status labels
- **Existing**: evidence in source tree or README.
- **Needs verification**: implementation found, behavior/tests not yet inspected.
- **Planned**: platform implementation not yet started.
