# EMRG on the phone: local mode (Termux)

The app has two clearly separate features:

1. **Remote control (unchanged).** Pair the phone with a PC host running
   `emrg-gateway` — Hosts screen → `+` → Pair host. The phone is a remote
   terminal; the agent runs on the PC.
2. **Local mode (this page).** The EMRG daemon and gateway run on the phone
   itself, inside Termux, and the app pairs with them at `127.0.0.1:56032`
   exactly like a remote host. No PC needed.

## How it works

Android cannot run the Python agent inside the app, so local mode runs it
next to the app: Termux provides a Linux environment on the phone, the
daemon (`emrgd`, on `127.0.0.1:56031`) and the gateway (`emrg-gateway`,
on `127.0.0.1:56032`) run there, and the app talks to the gateway over the
loopback interface. Pairing is the same two-step flow as remote: the
certificate fingerprint is compared, then the one-shot code is sent over
the pinned connection.

## One-time setup (on the phone, inside Termux)

1. Install **Termux from F-Droid** — https://f-droid.org/en/packages/com.termux/
   (not the Play Store build; it is outdated and breaks package installs).
2. In Termux, run:
   ```sh
   curl -fsSL https://raw.githubusercontent.com/herbalblitz/emrg/feat/termux-local/android/local/setup-local.sh | bash
   ```
   The script installs Python, git and Termux's prebuilt
   `python-cryptography`, clones the fork to `~/emrg`, and installs
   `emrg` + the gateway extra into a venv.
3. Start the daemon once: `emrg`
   The first-run wizard asks for your LLM API key and model; the daemon
   then keeps running in the background. EMRG speaks OpenAI-compatible
   APIs, so a key you already have works — set its base URL, e.g.
   OpenRouter `https://openrouter.ai/api/v1`, Groq
   `https://api.groq.com/openai/v1`, or Gemini
   `https://generativelanguage.googleapis.com/v1beta/openai/`.
   **Groq free keys need a small `max_tokens`**: Groq's free tier counts
   the `max_tokens` you declare against its 8,000 tokens/minute budget, so
   the 8192 default is rejected with `400 — Please reduce the length of the
   messages or completion`. Set `max_tokens = 1024` in
   `~/.emrg/config.toml` and restart the daemon (`emrg server restart`).
   EMRG's own request is already ~8,000 tokens (tool definitions plus
   system prompt), so if 1024 still fails, switch to Gemini instead — its
   free tier has no such cap (same file, model `gemini-2.0-flash`).
4. In a second Termux session, start the gateway (leave it running):
   `emrg-gateway serve`
5. Get a pairing code: `emrg-gateway pair` — it prints an 8-digit code
   (expires after five minutes) and the certificate fingerprint, plus a
   QR code of the same payload. In the app, **Pair local host → Quét mã
   QR** scans it instead of typing the fingerprint by hand (the QR needs
   the `qrcode` package, part of the gateway extra — if it prints
   "QR code unavailable", run `~/emrg/.venv/bin/pip install qrcode`).

## Daily use

1. Termux session 1: `emrg` (daemon; leave it or exit — the daemon stays).
2. Termux session 2: `emrg-gateway serve` (leave it running while you use
   the app).
3. In the app: Hosts → **Run EMRG on this phone** → Check again →
   **Pair local host**, enter the fingerprint and the code from step 5.
   After the first pairing the host stays saved; later just open the app.

Seamless connect: opening the app auto-connects to the last-used host
— no tap, no re-pairing (the token survives app restarts). If the
gateway is down, the Hosts screen shows an offline banner with a
**Khởi động EMRG** button: one tap asks Termux (RUN_COMMAND broadcast)
to start the daemon (`emrg server restart`) and the gateway
(`emrg-gateway serve`) in background sessions, and the app reconnects
on its own once the gateway answers. Two one-time Termux-side gates:
(1) the button asks once for the dangerous `RUN_COMMAND` permission
("Run commands in Termux environment"); (2) Termux refuses external
commands unless `allow-external-apps=true` is set — run once in
Termux: `mkdir -p ~/.termux && echo "allow-external-apps=true" >>
~/.termux/termux.properties && termux-reload-settings`. Without (2)
the broadcast is silently dropped and the host stays Offline.

The app's **Local setup** screen shows the same steps, probes whether the
local gateway is up, copies the setup command, and opens Termux.

## Limits (stated plainly)

- The sandbox (`bwrap`) does not exist on Android, so sandboxed workspace
  commands fail closed; the agent core (chat, sessions, memory, tools,
  evolution) runs.
- The daemon and gateway run only while their Termux sessions are alive.
  Android may kill background Termux sessions; reopening them restarts it.
  To keep them alive, run `termux-wake-lock` once in Termux after
  starting the daemon and gateway (and allow Termux to run unrestricted
  in the system battery settings — on MIUI: Settings → Apps → Termux →
  Battery saver → No restrictions). Without it the gateway can die while
  the phone is locked and the app then reports `Failed to connect`.
- Model calls need internet and your own key/quota — the software is free,
  the model is not.
- This path **has been tested on a real phone** (2026-11-17: install,
  daemon + gateway, pairing, app connect, chat framing). CI only verifies
  the app compiles. If a step fails on the device, report the exact
  command and its last lines.

## Troubleshooting

- `cryptography` import error: never `pip install cryptography` on Termux
  — the PyPI build cannot load under Termux's Python. Use
  `pkg install python-cryptography` and recreate the venv with
  `python -m venv --system-site-packages` (the setup script already does).
- `daemon: not running` from `emrg-gateway status`: start the daemon
  first (`emrg`), then the gateway.
- Daemon refuses to start with `config not found ... create it with [llm]
  section`: run `emrg` once first — the first-run wizard creates the
  config — then start the gateway. (`emrg-gateway serve` also creates a
  default config if none exists.)
- The app says the local gateway is not running: check that
  `emrg-gateway serve` is still running in its Termux session.
- Chat turns fail with `LLM error: LLM stream request failed: 400 —
  Please reduce the length of the messages or completion` on a Groq free
  key: lower `max_tokens` in `~/.emrg/config.toml` (1024 works; the 8192
  default alone exceeds Groq's 8,000 tokens/minute free budget) and run
  `emrg server restart`. If it still fails, switch the same file to
  Gemini (`base_url https://generativelanguage.googleapis.com/v1beta/openai/`,
  model `gemini-2.0-flash`).
