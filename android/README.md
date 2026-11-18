# EMRG Android (companion client scaffold)

This directory is the native Android companion project.

## Current state

**Scaffold only.** The app launches a Compose screen that explains the connection architecture. It does not yet connect to a daemon, pair devices, or expose agent features.

## Architecture constraint

The existing daemon listens on loopback (`127.0.0.1`) and uses desktop-oriented authentication. It is not remotely accessible from Android over a LAN. Do not change the bind address to `0.0.0.0` as a shortcut.

Before implementing networking, design and review a secure pairing/remote transport:
- explicit host enablement and device pairing/revocation
- authenticated, encrypted transport and replay-resistant credentials
- permission/approval boundaries for tool execution
- compatibility/version negotiation and safe reconnect
- no secret tokens in logs or QR payloads beyond short-lived pairing material

The Android app should be a companion client initially; the Python runtime remains on the host.

## Build

A Gradle wrapper will be added with the first reproducible build/CI slice. For now, open this folder in Android Studio and use a compatible Gradle installation to sync/build.
