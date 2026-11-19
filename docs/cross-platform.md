# EMRG 0.3.59 — cross-platform ecosystem

Native Android APK/AAB plus Windows x64 EXE, preserving 100% of the original
engine and functionality. The desktop (Electron GUI + Python backend + TUI +
`emrgd`) is unchanged; Android reaches the host through a new host-side
gateway.

## Version

`EMRG-0.3.59` everywhere: `pyproject.toml`, the gateway contract `API_VERSION =
"1.0"`, and `android/app/build.gradle.kts` (`versionName = "0.3.59"`,
`versionCode = 359`).

## Architecture decision (confirmed)

* **Base code:** upstream `argszero/emrg` commit `e891078` (version 0.3.59).
  The `herbalblitz/emrg` fork is an untouched v0.3.37 snapshot, 96 commits
  behind; results are pushed to the fork when access is provided.
* **Android:** remote-first via a new host gateway. No embedded Python, no
  on-device parity claim. The phone is a thin remote terminal over TLS.
* **Gateway:** an additive sidecar under `emrg/gateway/`, a client of the
  unmodified daemon. Core `emrg/` stays 0-diff outside `emrg/gateway/`.
* **Builds:** GitHub Actions — `ubuntu-latest` for APK/AAB, `windows-2025`
  for EXE. A PAT and target repo are still needed (see "Release" below).

## Repository layout

```
emrg/                  unchanged engine (0-diff outside emrg/gateway/)
emrg/gateway/          host-side TLS bridge for the Android client
android/               native Kotlin/Jetpack Compose app (one app module)
.github/workflows/ci.yml  gateway tests, Android APK/AAB, Windows EXE
```

## The gateway (`emrg/gateway/`)

A pinned-TLS WebSocket server on `wss://host:56032` with two endpoints:

* `wss://host:56032/v1/pair` — one-shot pairing. The first frame carries the
  8-digit code printed by `emrg-gateway pair`; the reply carries the device
  token once.
* `wss://host:56032/v1/ws` — the session endpoint. The first frame carries the
  device token; after `gw_hello_ok` the socket is a multiplexed pipe: daemon
  frames flow downstream unchanged, phone requests flow upstream either to the
  daemon (allow-listed proxy frames) or to a gateway handler.

CLI: `emrg-gateway serve | pair | devices | revoke <id> | fingerprint |
rotate-cert | status`.

### Security properties

* TLS 1.3 with a per-host self-signed ECDSA certificate. Android pins the
  **SHA-256 of the DER certificate** (the same string `emrg-gateway pair`
  prints). There is no CA and no hostname dependency, so a pin survives a
  DHCP address change.
* Pairing is two-step TOFU: probe the certificate fingerprint, the user
  compares it with the host display, then sends the one-shot code over the
  pinned connection.
* Device tokens are `sha256`-hashed in `devices.json` (never plaintext) and
  one-shot codes expire after five minutes and are destroyed after five wrong
  guesses.
* Host-lifecycle frames (`shutdown`, `init_auto_evolve`) are never forwarded.
* Every proxy frame is allow-listed against the daemon's own dispatch table
  (pinned by `tests/gateway/test_policy.py`).
* Host commands run through the engine's own `bash_tool_v2.run_command` seam
  with session-tier narrowing; `danger-full-access` needs both a host opt-in
  and an explicit phone confirmation. Irreversible command text (`rm -rf /`,
  `mkfs`, `dd of=/dev/`, fork bombs, shutdown, pipe-to-shell installs, forced
  pushes, recursive `chmod 777 /`) additionally needs `confirm_risky`.
* File writes are atomic and pass through `emrg.sandbox.fence.file_refusal`;
  conflict detection is content-based (SHA-256).
* The phone's config surface never carries the API key — presence and a
  four-character tail only. Writes are a typed TOML merge via `tomlkit`, with
  backups and redaction.
* Every mutating action is audit-logged with device id and decision.

## Android app

One `app` module, manual `AppContainer`/`ViewModel` instead of Hilt, custom
back-stack instead of Navigation-Compose, OkHttp WebSocket +
kotlinx.serialization, Android Keystore AES-GCM token storage.

Screens (~20): Hosts, Pair, Sessions, Chat, Tool Monitor, Files, Editor,
Terminal, Sandbox, Memory, Models, Rant, Evolution, Tasks, Skills, Logs,
Audit, Devices, Settings, About.

All slash commands preserved: `/resume`, `/sessions`, `/clear`, `/compact`,
`/memory`, `/rename`, `/model`, `/sandbox`, `/rant`, `/help`, `/image`,
`/delete`, `/rewind`, `/trigger`, `/task-session`, `/skills`, `/version`.

### Pairing (two-step TOFU)

1. Host runs `emrg-gateway pair`, which prints an 8-digit code and the
   certificate fingerprint.
2. Phone "Pair host" screen shows the probed fingerprint; the user compares it
   with the host.
3. User enters the code; the phone sends it over a connection pinned to that
   fingerprint and receives the bearer token, which is stored in Android
   Keystore (AES-256-GCM, non-exportable) and never written to disk in the
   clear.

## Tests

* `tests/gateway/` — 35 gateway tests (identity, policy, TLS, config_api,
  exec, server end-to-end against a fake link), all passing.
* `tests/gateway/test_android_contract.py` — pins Kotlin `API_VERSION` and the
  slash-command list to the Python gateway; fails on drift.
* Android unit tests under `android/app/src/test/java/` (protocol, models).
* The full baseline suite passes except environmental failures (missing `gh`
  CLI, daemon port contention, root/TTY, GUI libs) — documented in
  `emrg-baseline-pytest.log`.

## Release artifacts

Named `EMRG-0.3.59-windows-x64.exe`,
`EMRG-0.3.59-android-debug.apk`,
`EMRG-0.3.59-android-release.apk`, `.aab`, and `SHA256SUMS.txt`.

Builds require GitHub Actions with:
* `EMRG_KEYSTORE_FILE` / `EMRG_KEYSTORE_PASSWORD` / `EMRG_KEY_ALIAS` /
  `EMRG_KEY_PASSWORD` for release signing (debug key used otherwise — not for
  Play upload).
* A Windows code-signing certificate for the EXE (unsigned otherwise).

A GitHub PAT with `repo`/`workflow` scope and a target repo are still needed
before the branch can be pushed and Actions can run. Provide them via the
private secret card when ready.

## Blockers (documented, not fabricated)

* No GitHub PAT / target repo yet — branch cannot be pushed, Actions cannot
  run.
* Local host is aarch64 Ubuntu on an Android kernel: no JDK, Android SDK, KVM,
  Windows, Wine, Docker, `gh`, x86_64 tools, or `bwrap`. APK/AAB/EXE builds
  are therefore unverified locally and must run in CI.
* No Android keystore or Windows code-signing certificate — artifacts will be
  debug-signed/unsigned unless supplied.
* `workspace-write` gateway exec fails closed locally without `bwrap`; the
  corresponding test skips via `ERR_SANDBOX_UNAVAILABLE`.
* Android code cannot be compiled/tested locally.

No builds or test results are fabricated; every green result above was
produced locally, and CI will produce the artifacts.
