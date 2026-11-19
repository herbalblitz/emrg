"""Gateway configuration and on-disk layout.

Everything the gateway owns lives under ``~/.emrg/gateway`` so an upgrade or an
uninstall of the desktop app never has to guess which files are the phone's:
pairing, device tokens (hashed), the TLS key pair, the audit log and the
gateway's own options.

The daemon's own state (``~/.emrg/config.toml``, sessions, memory, logs) keeps
its original location and is read/written through the engine's own helpers.
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path

from emrg.config import config_dir

DEFAULT_PORT = 56032
DEFAULT_HOST = "0.0.0.0"

#: Frame ceiling. A task prompt with an image can be a few megabytes; 8 MiB is
#: generous enough for that and small enough that one client cannot exhaust the
#: host's memory with a single frame.
DEFAULT_FRAME_LIMIT = 8 * 1024 * 1024

#: A paired phone that disappears keeps its daemon link (and therefore its
#: running task) for this long. After that an idle link is closed; an active
#: turn is never closed by the reaper.
DEFAULT_IDLE_TIMEOUT = 15 * 60.0

#: Longest single host command the terminal accepts.
DEFAULT_COMMAND_TIMEOUT = 120.0
MAX_COMMAND_TIMEOUT = 600.0

#: How many bytes of a command's stdout/stderr are returned to the phone.
MAX_EXEC_OUTPUT = 200_000
MAX_EXEC_ERROR = 50_000

#: File editor ceiling. The daemon's own read tool has its own bound; this one
#: exists so a phone cannot pull a multi-gigabyte file into a JSON frame.
MAX_EDIT_FILE = 2 * 1024 * 1024


@dataclass
class GatewayConfig:
    """User-visible gateway options (``gateway/config.json``)."""

    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    enabled: bool = True
    max_devices: int = 8
    frame_limit: int = DEFAULT_FRAME_LIMIT
    idle_timeout: float = DEFAULT_IDLE_TIMEOUT
    command_timeout: float = DEFAULT_COMMAND_TIMEOUT
    #: A gateway exec runs at the session's declared tier when it has one.
    #: This is the tier used when the session never declared one. The daemon's
    #: own host-session default is danger-full-access; the phone gets the safer
    #: default unless a person explicitly changes the session tier.
    default_exec_mode: str = "workspace-write"
    #: ``danger-full-access`` is only allowed when the host opts in *and* the
    #: request carries an explicit confirmation. A stolen phone token must not
    #: silently turn into an unconfined shell.
    allow_danger_exec: bool = False
    #: Keep at most this many devices. Revoking one frees a slot.
    #: (Also enforced in the identity store; repeated here so the API can
    #: explain the refusal before pairing starts.)
    audit_max_bytes: int = 10 * 1024 * 1024
    audit_backups: int = 3
    #: Optional bind override for the LAN address printed by ``pair``. Empty
    #: means "discover".
    public_host: str = ""

    @property
    def effective_command_timeout(self) -> float:
        return max(1.0, min(float(self.command_timeout), MAX_COMMAND_TIMEOUT))


@dataclass(frozen=True)
class GatewayPaths:
    """Every path the gateway owns, derived once."""

    root: Path
    cert: Path
    key: Path
    devices: Path
    pairing: Path
    audit: Path
    config: Path
    backups: Path

    @classmethod
    def under(cls, root: Path) -> "GatewayPaths":
        return cls(
            root=root,
            cert=root / "cert.pem",
            key=root / "key.pem",
            devices=root / "devices.json",
            pairing=root / "pairing.json",
            audit=root / "audit.jsonl",
            config=root / "config.json",
            backups=root / "backups",
        )

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        self.backups.mkdir(parents=True, exist_ok=True)
        _chmod(self.root, 0o700)
        _chmod(self.backups, 0o700)


def gateway_paths() -> GatewayPaths:
    """The gateway's directory for this host, created owner-only."""
    paths = GatewayPaths.under(config_dir() / "gateway")
    paths.ensure()
    return paths


def load_config(path: Path | None = None) -> GatewayConfig:
    """Read ``config.json``; a missing or unreadable file means defaults.

    An unreadable file is deliberately not fatal: the gateway is an optional
    path to the engine, and a malformed option must not make the desktop app
    unreachable. The caller logs the parse error and runs on defaults, exactly
    as the engine's own config loader keeps the previous good revision.
    """
    target = path or gateway_paths().config
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return GatewayConfig()
    if not isinstance(raw, dict):
        return GatewayConfig()
    known = {f.name for f in GatewayConfig.__dataclass_fields__.values()}  # type: ignore[attr-defined]
    values = {k: v for k, v in raw.items() if k in known}
    try:
        cfg = GatewayConfig(**values)
    except TypeError:
        return GatewayConfig()
    # Bounds that a hand-edited file could otherwise take out of range.
    cfg.max_devices = max(1, min(int(cfg.max_devices), 32))
    cfg.frame_limit = max(64 * 1024, min(int(cfg.frame_limit), 32 * 1024 * 1024))
    cfg.idle_timeout = max(30.0, float(cfg.idle_timeout))
    cfg.command_timeout = max(1.0, min(float(cfg.command_timeout), MAX_COMMAND_TIMEOUT))
    if cfg.default_exec_mode not in {"read-only", "workspace-write", "danger-full-access"}:
        cfg.default_exec_mode = "workspace-write"
    return cfg


def save_config(cfg: GatewayConfig, path: Path | None = None) -> None:
    """Atomically write ``config.json`` with owner-only permissions."""
    target = path or gateway_paths().config
    target.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(asdict(cfg), indent=2, ensure_ascii=False) + "\n"
    tmp = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, target)
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
    _chmod(target, 0o600)


def _chmod(path: Path, mode: int) -> None:
    try:
        os.chmod(path, mode)
    except OSError:
        # Windows has no POSIX mode bits for the file; the ACL the installer
        # creates is the boundary there. The daemon's own token file follows
        # the same rule.
        pass
