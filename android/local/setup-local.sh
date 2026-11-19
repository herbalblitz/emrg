#!/data/data/com.termux/files/usr/bin/bash
# One-time setup: run EMRG (daemon + gateway) on this phone, inside Termux.
# Run once, inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/herbalblitz/emrg/feat/termux-local/android/local/setup-local.sh | bash
#
# What it does:
#   * installs Python, git and Termux's prebuilt python-cryptography
#   * clones the fork (branch feat/termux-local) to ~/emrg
#   * creates a venv that can see the system python-cryptography
#   * installs emrg + the gateway extra from the repo
#
# NEVER `pip install cryptography` on Termux: the PyPI build cannot load
# under Termux's Python (dlopen failure) and shadows the working system
# package. That is why the venv below uses --system-site-packages.
set -euo pipefail

pkg update -y
pkg install -y python git python-cryptography clang libyaml

if [ ! -d "$HOME/emrg" ]; then
  git clone https://github.com/herbalblitz/emrg.git "$HOME/emrg"
fi
cd "$HOME/emrg"
git checkout feat/termux-local
git pull --ff-only || true

if [ ! -d "$HOME/emrg/.venv" ]; then
  python -m venv --system-site-packages "$HOME/emrg/.venv"
fi
# shellcheck disable=SC1091
source "$HOME/emrg/.venv/bin/activate"
pip install --upgrade pip
pip install -e ".[gateway]"

# Make `emrg` and `emrg-gateway` work in every new Termux session without
# activating the venv by hand. The venv's console scripts carry a shebang
# pointing at the venv interpreter, so PATH is enough — but `source
# .venv/bin/activate` inside this script only affects this script's shell,
# not the user's interactive sessions, which is why a bare `emrg` used to
# report "No command emrg found" after setup.
PATH_LINE='export PATH="$HOME/emrg/.venv/bin:$PATH"'
if ! grep -qF "$PATH_LINE" "$HOME/.bashrc" 2>/dev/null; then
  printf '\n# EMRG local mode (added by setup-local.sh)\n%s\n' "$PATH_LINE" >> "$HOME/.bashrc"
fi
export PATH="$HOME/emrg/.venv/bin:$PATH"

python -c "import cryptography, websockets, tomlkit, emrg.gateway; print('gateway imports OK')"
python -c "import emrg; assert emrg.__version__ == '0.3.63', emrg.__version__; print('emrg version OK:', emrg.__version__)"
# Proof the engine is the real upstream EMRG, not a copy: the core must be
# byte-identical to upstream 0.3.63 outside the gateway sidecar.
if git diff --quiet upstream-v0.3.63-a7568dcf -- emrg/ ':!emrg/gateway'; then
  echo "core matches upstream 0.3.63: OK (0 files differ outside emrg/gateway)"
else
  echo "WARNING: core differs from upstream 0.3.63 outside emrg/gateway:"
  git diff --stat upstream-v0.3.63-a7568dcf -- emrg/ ':!emrg/gateway' || true
fi
# Smoke check: the gateway binary runs and reports its state.
emrg-gateway status || true

cat <<'EOF'

Setup done. Next steps (all inside Termux):
  1) Open a NEW Termux session (so the new PATH from ~/.bashrc loads) and
     start the daemon once:  emrg
     The first-run wizard asks for your LLM API key and model, then the
     daemon keeps running in the background. EMRG speaks OpenAI-compatible
     APIs, so a key you already have works — set its base URL, e.g.:
       OpenRouter  https://openrouter.ai/api/v1
       Groq        https://api.groq.com/openai/v1
       Gemini      https://generativelanguage.googleapis.com/v1beta/openai/
  2) In a second Termux session, start the gateway (leave it running):
       emrg-gateway serve
  3) Get a pairing code (third session, or any new one):
       emrg-gateway pair
     It prints an 8-digit code and the certificate fingerprint.
  4) In the EMRG app: Hosts -> "Run EMRG on this phone" -> Check again
     -> Pair local host. Enter the fingerprint and the code.
EOF
