"""The phone's view of the host configuration.

The engine's ``~/.emrg/config.toml`` holds a live API key. It is never sent to a
phone; :func:`snapshot` reports whether a key is set and a four-character tail
so the host can tell one key from another without the key itself travelling.

Edits are a **typed patch**, not a file upload. Every key a phone may change is
listed below with its domain, because a phone's config screen is a set of fields
— an endpoint, a model, a temperature — and a general TOML writer would be a
much larger surface than the feature needs. The file is merged with
``tomlkit`` so comments and unrelated sections survive, validated by re-parsing
before the rename, and backed up first. The daemon notices the new revision
within two seconds on its own poll (``emrg.server.config_reload``); a changed
``model`` is the exception it reports rather than applies, which is why the
response says ``restart_required`` for it, matching ``/model``'s path.
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

from emrg.config import (
    CONFIG_FILE_MODE,
    config_path,
    load_config,
    logs_dir,
)
from emrg.gateway.config import GatewayConfig, gateway_paths, load_config as load_gateway_config, save_config
from emrg.gateway.protocol import ERR_INTERNAL, ERR_INVALID_ARGUMENT, GatewayError

#: Every key a phone may write, with its own domain test. The key is dotted so
#: the patch and the file use the same name.
_SCHEMA: dict[str, tuple[str, Any]] = {
    "llm.base_url": ("str", None),
    "llm.api_key": ("secret", None),
    "llm.model": ("str", None),
    "llm.max_tokens": ("int", (1, 2_000_000)),
    "llm.temperature": ("number", (0.0, 2.0)),
    "llm.context_window": ("int", (1024, 10_000_000)),
    "llm.vision": ("bool", None),
    "llm.max_tool_rounds": ("int", (1, 1000)),
    "update.enabled": ("bool", None),
    "update.delay_minutes": ("int", (0, 100_000)),
    "client.theme": ("enum", ("auto", "dark", "light")),
}

#: Keys whose new value needs more than the two-second reload: the model change
#: goes through ``/model`` (usage anchors, context window), and the gateway's own
#: bind address needs a restart because a listening socket cannot move.
_RESTART_HINT = {
    "llm.model": "the daemon applies a model change through /model; use the model screen "
    "or restart the daemon",
}

_GATEWAY_SCHEMA: dict[str, Any] = {
    "host": ("str", None),
    "port": ("int", (1, 65535)),
    "default_exec_mode": ("enum", ("read-only", "workspace-write", "danger-full-access")),
    "allow_danger_exec": ("bool", None),
    "idle_timeout": ("number", (30.0, 86400.0)),
    "max_devices": ("int", (1, 32)),
    "command_timeout": ("number", (1.0, 600.0)),
    "public_host": ("str", None),
}


def _assert_type(key: str, value: Any, kind: str, domain: Any) -> Any:
    if kind == "str":
        if not isinstance(value, str):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be a string")
        return value.strip()
    if kind == "secret":
        if not isinstance(value, str) or not value:
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be a non-empty string")
        return value.strip()
    if kind == "bool":
        if not isinstance(value, bool):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be true or false")
        return value
    if kind == "int":
        if isinstance(value, bool) or not isinstance(value, int):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be an integer")
        low, high = domain
        if not (low <= value <= high):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be between {low} and {high}")
        return value
    if kind == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be a number")
        low, high = domain
        if not (low <= float(value) <= high):
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be between {low} and {high}")
        return float(value)
    if kind == "enum":
        if value not in domain:
            raise GatewayError(ERR_INVALID_ARGUMENT, f"{key} must be one of {', '.join(domain)}")
        return value
    raise GatewayError(ERR_INTERNAL, f"unknown schema kind {kind!r}")


def _mask(secret: str) -> str:
    if not secret:
        return ""
    return "…" + secret[-4:]


def snapshot(config: GatewayConfig) -> dict:
    """Everything the phone's settings screen may read, with no secret."""
    engine = load_config()
    llm = engine.llm
    raw_path = config_path()
    return {
        "gateway": {
            "host": config.host,
            "port": config.port,
            "enabled": config.enabled,
            "max_devices": config.max_devices,
            "idle_timeout": config.idle_timeout,
            "command_timeout": config.effective_command_timeout,
            "default_exec_mode": config.default_exec_mode,
            "allow_danger_exec": config.allow_danger_exec,
            "public_host": config.public_host,
        },
        "engine": {
            "config_path": str(raw_path),
            "config_present": raw_path.is_file(),
            "base_url": llm.base_url,
            "model": llm.model,
            "max_tokens": llm.max_tokens,
            "temperature": llm.temperature,
            "context_window": llm.context_window,
            "vision": llm.vision,
            "vision_default": llm.vision_default,
            "max_tool_rounds": llm.max_tool_rounds,
            "models": [m for m in llm.models if isinstance(m, dict)],
            "api_key_set": bool(llm.api_key and llm.api_key != "sk-..."),
            "api_key_hint": _mask(llm.api_key),
            "update": {"enabled": engine.update.enabled, "delay_minutes": engine.update.delay_minutes},
            "client": {"theme": engine.client.theme if hasattr(engine, "client") else "auto"},
        },
        "editable": sorted(_SCHEMA),
        "editable_gateway": sorted(_GATEWAY_SCHEMA),
        "logs_dir": str(logs_dir()),
    }


def _backup(raw: bytes) -> Path | None:
    """Keep one copy of the file before a phone edits it, bounded to 20."""
    directory = gateway_paths().backups
    if not raw:
        return None
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    target = directory / f"config-{stamp}-{os.getpid()}.toml"
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except OSError:
        try:
            target.unlink()
        except OSError:
            pass
        return None
    files = sorted(directory.glob("config-*.toml"))
    for old in files[:-20]:
        try:
            old.unlink()
        except OSError:
            pass
    return target


def _tomlkit():
    try:
        import tomlkit
    except ImportError as exc:  # pragma: no cover - installation message
        raise GatewayError(
            ERR_INTERNAL,
            "the gateway needs 'tomlkit' to edit config.toml; install 'emrg[gateway]'",
        ) from exc
    return tomlkit


def apply_patch(patch: dict, *, device_id: str) -> dict:
    """Merge a typed patch into ``config.toml`` atomically.

    Returns the flat map of keys actually changed, a list of keys the daemon
    reloads on its own, and the ones that carry a hint. The API key is reported
    by presence only, never in the response.
    """
    if not isinstance(patch, dict) or not patch:
        raise GatewayError(ERR_INVALID_ARGUMENT, "patch must be a non-empty object")
    changes: dict[str, Any] = {}
    for key, value in patch.items():
        if key not in _SCHEMA:
            raise GatewayError(
                ERR_INVALID_ARGUMENT,
                f"{key} is not editable from a phone; allowed: {', '.join(sorted(_SCHEMA))}",
            )
        kind, domain = _SCHEMA[key]
        clean = _assert_type(key, value, kind, domain)
        changes[key] = clean

    tomlkit = _tomlkit()
    path = config_path()
    try:
        previous = path.read_bytes() if path.exists() else b""
    except OSError as exc:
        raise GatewayError(ERR_INTERNAL, f"cannot read {path}: {exc}") from exc

    if previous:
        try:
            doc = tomlkit.parse(previous.decode("utf-8"))
        except Exception as exc:
            raise GatewayError(
                ERR_INVALID_ARGUMENT,
                f"{path} is not parseable TOML, so a phone edit cannot merge with it: {exc}",
            ) from exc
    else:
        doc = tomlkit.document()

    for dotted, value in changes.items():
        section, key = dotted.split(".", 1)
        table = doc.get(section)
        if table is None:
            table = tomlkit.table()
            doc[section] = table
        table[key] = value

    text = tomlkit.dumps(doc)
    # A second parser is the judge, not the writer: if the merged document no
    # longer parses, nothing is renamed into place.
    import tomllib

    try:
        tomllib.loads(text)
    except tomllib.TOMLDecodeError as exc:
        raise GatewayError(ERR_INTERNAL, f"the merged config is not valid TOML: {exc}") from exc

    backup = _backup(previous)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, CONFIG_FILE_MODE)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
        try:
            os.chmod(path, CONFIG_FILE_MODE)
        except OSError:
            pass
    except OSError as exc:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        raise GatewayError(ERR_INTERNAL, f"cannot write {path}: {exc}") from exc

    reported = {
        key: ("***" if _SCHEMA[key][0] == "secret" else value)
        for key, value in changes.items()
    }
    reloadable = [k for k in changes if k not in _RESTART_HINT]
    hints = {k: v for k, v in _RESTART_HINT.items() if k in changes}
    return {
        "changed": reported,
        "reload_within_seconds": 2,
        "reloadable": reloadable,
        "hints": hints,
        "backup": str(backup) if backup else "",
        "device_id": device_id,
    }


def apply_gateway_patch(patch: dict) -> dict:
    """Persist gateway options and return the ones that need a restart."""
    if not isinstance(patch, dict) or not patch:
        raise GatewayError(ERR_INVALID_ARGUMENT, "patch must be a non-empty object")
    current = load_gateway_config()
    changed: dict[str, Any] = {}
    for key, value in patch.items():
        if key not in _GATEWAY_SCHEMA:
            raise GatewayError(
                ERR_INVALID_ARGUMENT,
                f"{key} is not a gateway option; allowed: {', '.join(sorted(_GATEWAY_SCHEMA))}",
            )
        kind, domain = _GATEWAY_SCHEMA[key]
        clean = _assert_type(f"gateway.{key}", value, kind, domain)
        setattr(current, key, clean)
        changed[key] = clean
    save_config(current)
    restart = [k for k in changed if k in {"host", "port"}]
    return {"changed": changed, "restart_required": restart, "gateway": asdict(current)}
