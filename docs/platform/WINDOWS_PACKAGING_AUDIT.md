# Windows Packaging Audit

Audit date: 2026-11-18. Branch: `feat/android-windows-platform-audit`.
Source-inspected only unless a CI run is explicitly cited.

## Confirmed source configuration

- Release matrix includes `windows-2025`, x64, artifact glob `EMRG-*-windows-x64.exe`.
- Electron Builder targets Windows `dir` output (`win-unpacked`), x64; NSIS config is present but the release wrapper uses Inno Setup for the final installer.
- `packaging/make-installer.sh` Windows path requires Inno Setup compiler (`iscc`) and the `emrg/gui/dist/win-unpacked` directory. It generates a staging `emrg.iss`, then compiles a per-user installer.
- Release workflow runs the GUI build, bundles git/gh, builds the installer, and creates a Windows ZIP copy of the installer EXE.
- `.github/workflows/test.yml` has a Windows runner job and an Inno Setup compile smoke test that generates a stub payload, renders the installer script, compiles it, and checks the output executable/version resource.

## CI evidence confirmed on the feature branch\n\n- Windows runner `windows-2025` executes Python tests, GUI unit tests and an Inno Setup compile smoke test on PR CI. The installer smoke is a script-compile/output-resource check using a stub payload; it does not install or launch the product.\n- Android CI #122 is green, but this does not provide Windows runtime validation.\n\n## Not yet proven / remaining gates

- Clean Windows machine install, first launch, daemon start/stop/restart and reconnect.
- Upgrade over a previous version; rollback/uninstall behavior and stale process cleanup.
- PATH/shortcuts and shell integration across PowerShell, cmd, Git Bash.
- Runtime bundled Python/dependency import and provider/tool functionality after installation.
- Windows-specific sandbox/permission boundaries and security regression tests.
- Release artifact signing, checksum verification, and end-user installation smoke.

## Constraints

- No release workflow, installer, signing, deployment or master branch action was performed as part of this audit.
- Source configuration and CI smoke compilation do not equal a clean-machine end-to-end install.
