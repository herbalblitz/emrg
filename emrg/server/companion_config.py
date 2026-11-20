"""Settings get/set for the companion Settings screen.

A port of ``emrg/gateway/config_api.py`` (reference branch
``feat/cross-platform``) for the companion WSS listener, which has no
gateway — with the companion differences stated here, because the settings
patch allowlist is the whole security story of this module:

**Strict patch allowlist.** A phone's settings screen is a set of fields,
not a TOML writer. Every key a remote device may change is listed in
:data:`_SCHEMA` (engine ``config.toml``) and :data:`_COMPANION_SCHEMA`
(companion listener policy) with its own domain test. Any key outside
the allowlist — including every key the gateway allowed that this branch
must not — is rejected with one generic error, and nothing is written:
validation of the whole patch happens before the first byte moves.

Keys deliberately **not** in the allowlist, and why:

* ``llm.api_key`` — never a raw API key. The snapshot reports key
  *presence* only (``api_key_set`` + a four-character tail, the gateway's
  ``_mask``), and no remote set exists. A key is set on the host.
* ``allow_danger_exec`` — the host-local danger opt-in. Remote devices
  are capped at ``workspace-write`` (see ``companion_exec``); a remote set
  of this flag would be privilege escalation to ``danger-full-access``.
* ``max_devices`` — no such knob exists on the companion listener
  (``max_connections`` is listener policy, set at startup); rejected.
* Listener bind (``host`` / ``port`` / ``public_host``) — changing the
  bind remotely would move a listening socket and change network
  exposure; a bind change is a host-local restart decision, not a remote
  patch. Rejected.
* ``companion.default_exec_mode`` is allowlisted but capped at
  ``read-only`` / ``workspace-write``: ``danger-full-access`` is not an
  enum member here, for the same reason ``allow_danger_exec`` is out.

**No ``tomlkit`` in this branch.** The gateway merged with ``tomlkit`` so
comments survived. This branch carries no ``tomlkit`` dependency, so the
merge is a line-oriented surgical edit (:func:`_merge_toml`): only the
lines for keys in the patch move; every comment and unrelated section
survives byte-for-byte. The file is validated with ``tomllib`` before
*and* after the merge, backed up first (bounded to 5), and replaced
atomically. The daemon notices the new revision on its own poll
(``emrg.server.config_reload``); a ``model`` change is reported, not
applied, matching the ``/model`` path.

**No absolute host paths leave the host.** Unlike the gateway snapshot,
this snapshot carries no ``config_path`` and no ``logs_dir`` — both are
absolute paths outside the workspace.

Audit: every set is audit-logged with key *names* only, never values
(a value could be a URL carrying a credential); rejected keys are logged
as denied.
"""
from __future__ import annotations

import dataclasses
import json
import logging
import os
import re
import time
import tomllib
from pathlib import Path
from typing import Any

from emrg.config import (
    CONFIG_FILE_MODE,
    PLACEHOLDER_API_KEY,
    EmrgConfig,
    config_path,
    load_config,
)
from emrg.server.companion_exec import CompanionExecConfig
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.tools.base import non_regular_kind

_LOG = logging.getLogger(__name__)

#: Every engine key a remote device may write, with its domain test.
#: Dotted so the patch and the file use the same name. Ported from gateway
#: ``config_api._SCHEMA`` minus ``llm.api_key`` (never a raw key).
_SCHEMA: dict[str, tuple[str, Any]] = {
    "llm.base_url": ("str", 2048),
    "llm.model": ("str", 512),
    "llm.max_tokens": ("int", (1, 2_000_000)),
    "llm.temperature": ("number", (0.0, 2.0)),
    "llm.context_window": ("int", (1024, 10_000_000)),
    "llm.vision": ("bool", None),
    "llm.max_tool_rounds": ("int", (1, 1000)),
    "update.enabled": ("bool", None),
    "update.delay_minutes": ("int", (0, 100_000)),
    "client.theme": ("enum", ("auto", "dark", "light")),
}

#: Companion listener-policy keys a remote device may write. These are
#: applied to the listener's live config objects by the listener (see
#: :func:`apply_companion_patch`); they are not ``config.toml`` keys.
#: ``default_exec_mode`` excludes ``danger-full-access`` by design.
_COMPANION_SCHEMA: dict[str, tuple[str, Any]] = {
    "companion.default_exec_mode": ("enum", ("read-only", "workspace-write")),
    "companion.command_timeout": ("number", (1.0, 600.0)),
    "companion.idle_timeout": ("number", (5.0, 300.0)),
}

#: Every key a remote device may write, engine + companion.
EDITABLE_KEYS: list[str] = sorted(_SCHEMA) + sorted(_COMPANION_SCHEMA)

#: Keys whose new value needs more than the daemon's two-second reload:
#: the model change goes through ``/model`` (usage anchors, context
#: window), which is why the response carries a hint for it.
_RESTART_HINT = {
    "llm.model": "the daemon applies a model change through /model; use the model screen "
    "or restart the daemon",
}

#: Backups of ``config.toml`` kept beside it after a phone edit.
_MAX_BACKUPS = 5

_SECTION_RE = re.compile(r"^\s*\[([A-Za-z0-9_.\-]+)\]\s*(?:#.*)?$")


class CompanionConfigError(ValueError):
    """Safe settings refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _mask(secret: str) -> str:
    """Last-four hint for a set key, never the key itself."""
    if not secret:
        return ""
    return "…" + secret[-4:]


def _assert_type(key: str, value: Any, kind: str, domain: Any) -> Any:
    """Validate one patch value against its schema entry (gateway port)."""
    if kind == "str":
        if not isinstance(value, str) or not value.strip():
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        cleaned = value.strip()
        if len(cleaned.encode("utf-8")) > domain:
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        return cleaned
    if kind == "bool":
        if not isinstance(value, bool):
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        return value
    if kind == "int":
        if isinstance(value, bool) or not isinstance(value, int):
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        low, high = domain
        if not low <= value <= high:
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        return value
    if kind == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        number = float(value)
        if number != number or number in (float("inf"), float("-inf")):
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        low, high = domain
        if not low <= number <= high:
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        return number
    if kind == "enum":
        if value not in domain:
            raise CompanionConfigError("invalid_patch", f"invalid value for {key}")
        return value
    raise CompanionConfigError("invalid_patch", f"invalid value for {key}")


def snapshot(*, config_file: Path | None = None,
             exec_config: CompanionExecConfig | None = None,
             listener_config: CompanionListenerConfig | None = None) -> dict:
    """Everything the phone's settings screen may read, with no secret.

    The engine key is reported by presence only (``api_key_set`` + a
    four-character tail); the raw key never leaves the host. A missing
    config file answers with defaults and ``config_present: False``; an
    unparseable one is an error, not a guess.
    """
    path = Path(config_file) if config_file is not None else config_path()
    present = path.is_file()
    if present:
        try:
            engine = load_config(path)
        except FileNotFoundError:
            present = False
            engine = EmrgConfig()
        except (tomllib.TOMLDecodeError, OSError, ValueError) as exc:
            raise CompanionConfigError(
                "config_unavailable", "engine config cannot be read") from exc
    else:
        engine = EmrgConfig()
    llm = engine.llm
    exec_cfg = exec_config if exec_config is not None else CompanionExecConfig()
    listener_cfg = listener_config
    key = llm.api_key or ""
    return {
        "config_present": present,
        "engine": {
            "base_url": llm.base_url,
            "model": llm.model,
            "max_tokens": llm.max_tokens,
            "temperature": llm.temperature,
            "context_window": llm.context_window,
            "vision": llm.vision,
            "vision_default": llm.vision_default,
            "max_tool_rounds": llm.max_tool_rounds,
            "models": [{"name": entry["name"]} for entry in llm.models
                       if isinstance(entry, dict) and isinstance(entry.get("name"), str)],
            "api_key_set": bool(key and key != PLACEHOLDER_API_KEY),
            "api_key_hint": _mask(key) if key and key != PLACEHOLDER_API_KEY else "",
            "update": {"enabled": engine.update.enabled,
                       "delay_minutes": engine.update.delay_minutes},
            "client": {"theme": engine.client.theme},
        },
        "companion": {
            "default_exec_mode": exec_cfg.default_exec_mode,
            "command_timeout": exec_cfg.effective_command_timeout,
            "allow_danger_exec": exec_cfg.allow_danger_exec,
            "idle_timeout": (listener_cfg.idle_timeout_seconds
                             if listener_cfg is not None else 60.0),
            "max_connections": (listener_cfg.max_connections
                                if listener_cfg is not None else 8),
        },
        "editable": sorted(_SCHEMA),
        "editable_companion": sorted(_COMPANION_SCHEMA),
    }


def _toml_literal(value: Any) -> str:
    """Render one validated patch value as a TOML literal."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, str):
        # JSON string escaping is a subset of TOML basic-string escaping.
        return json.dumps(value, ensure_ascii=False)
    raise CompanionConfigError("invalid_patch", "unsupported settings value")


def _merge_toml(text: str, changes: dict[str, Any]) -> str:
    """Surgically merge dotted ``section.key`` changes into TOML text.

    Only the lines for keys in ``changes`` move; comments and unrelated
    sections survive byte-for-byte. A missing key is inserted directly
    under its section header; a missing section is appended at the end.
    The merged text is re-parsed by the caller before anything is written.

    Limitation, stated rather than hidden: a ``key = ...`` line inside a
    multi-line string in the same section would be mistaken for the key.
    The engine sections this module writes (``[llm]``, ``[update]``,
    ``[client]``) hold scalars, not multi-line strings, and the merged
    document is re-parsed before it is written, so a bad merge fails
    closed instead of landing.
    """
    lines = text.splitlines()
    for dotted in sorted(changes):
        section, key = dotted.split(".", 1)
        literal = f"{key} = {_toml_literal(changes[dotted])}"
        header_idx: int | None = None
        for idx, line in enumerate(lines):
            match = _SECTION_RE.match(line)
            if match and match.group(1) == section:
                header_idx = idx
                break
        if header_idx is None:
            if lines and lines[-1].strip():
                lines.append("")
            lines.append(f"[{section}]")
            lines.append(literal)
            continue
        key_re = re.compile(rf"^\s*{re.escape(key)}\s*=")
        end = len(lines)
        for idx in range(header_idx + 1, len(lines)):
            if _SECTION_RE.match(lines[idx]) or lines[idx].lstrip().startswith("[["):
                end = idx
                break
        replaced = False
        for idx in range(header_idx + 1, end):
            if key_re.match(lines[idx]):
                lines[idx] = literal
                replaced = True
                break
        if not replaced:
            lines.insert(header_idx + 1, literal)
    merged = "\n".join(lines)
    return merged + "\n" if merged else merged


def _backup(previous: bytes, path: Path) -> str:
    """Keep one timestamped copy of the file before a phone edits it.

    Best-effort: a backup failure never blocks the edit, and the return
    value is ``""`` when no backup was taken. Bounded to
    :data:`_MAX_BACKUPS` files.
    """
    if not previous:
        return ""
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    target = path.with_name(f"{path.name}.companion-bak-{stamp}-{os.getpid()}")
    # The backup name is created with O_EXCL below, so an existing file of
    # any kind refuses the open — but a non-regular file sitting at the
    # name is refused here first, before any open is attempted (the
    # tree-wide opener guard enforces this shape).
    try:
        kind = non_regular_kind(target)
    except FileNotFoundError:
        kind = None
    except OSError:
        return ""
    if kind is not None:
        return ""
    try:
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, CONFIG_FILE_MODE)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(previous)
                handle.flush()
                os.fsync(handle.fileno())
        except OSError:
            try:
                target.unlink()
            except OSError:
                pass
            return ""
    except OSError:
        return ""
    try:
        files = sorted(path.parent.glob(f"{path.name}.companion-bak-*"))
        for old in files[:-_MAX_BACKUPS]:
            try:
                old.unlink()
            except OSError:
                pass
    except OSError:
        pass
    return str(target)


def apply_patch(patch: dict, *, device_id: str,
                config_file: Path | None = None) -> dict:
    """Merge a typed patch into ``config.toml`` atomically.

    Validates the whole patch before touching the file: an unknown key, a
    wrong-typed value, or an unparseable existing file rejects the entire
    patch and writes nothing. Returns the keys actually changed, the
    companion-policy changes the listener must apply itself (see
    :func:`apply_companion_patch`), the keys the daemon reloads on its
    own, and the model-change hint. Values are never audit-logged — only
    key names.
    """
    if not isinstance(patch, dict) or not patch:
        raise CompanionConfigError("invalid_patch", "patch must be a non-empty object")
    engine_changes: dict[str, Any] = {}
    companion_changes: dict[str, Any] = {}
    for key, value in patch.items():
        if key in _SCHEMA:
            engine_changes[key] = _assert_type(key, value, *_SCHEMA[key])
        elif key in _COMPANION_SCHEMA:
            companion_changes[key] = _assert_type(key, value, *_COMPANION_SCHEMA[key])
        else:
            # Generic error by design: the reply names neither the key nor
            # the allowlist. The host-side audit log names the rejected key.
            _LOG.warning(
                "companion settings denied: operation=%r device=%r key=%r",
                "settings.set", device_id, str(key)[:80])
            raise CompanionConfigError("invalid_patch", "settings key not allowed")

    backup = ""
    if engine_changes:
        path = Path(config_file) if config_file is not None else config_path()
        # The kind is asked before the file is read: a FIFO at the config
        # path would block the open (the tree-wide opener guard enforces
        # this shape). A missing file means "no previous revision", not an
        # error — the merge below starts from empty text.
        try:
            kind = non_regular_kind(path)
        except FileNotFoundError:
            previous = b""
        except OSError as exc:
            raise CompanionConfigError(
                "config_unavailable", "engine config cannot be read") from exc
        else:
            if kind is not None:
                raise CompanionConfigError(
                    "config_unavailable", "engine config is not a regular file")
            try:
                previous = path.read_bytes()
            except OSError as exc:
                raise CompanionConfigError(
                    "config_unavailable", "engine config cannot be read") from exc
        if previous:
            try:
                tomllib.loads(previous.decode("utf-8"))
            except (tomllib.TOMLDecodeError, UnicodeDecodeError) as exc:
                raise CompanionConfigError(
                    "invalid_patch",
                    "engine config is not parseable TOML, so a phone edit cannot merge with it",
                ) from exc
            text = previous.decode("utf-8")
        else:
            text = ""
        merged = _merge_toml(text, engine_changes)
        try:
            tomllib.loads(merged)
        except tomllib.TOMLDecodeError as exc:
            raise CompanionConfigError(
                "invalid_patch", "the merged config is not valid TOML") from exc
        backup = _backup(previous, path)
        tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, CONFIG_FILE_MODE)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(merged)
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
            raise CompanionConfigError(
                "config_unavailable", "engine config cannot be written") from exc

    changed = {**engine_changes, **companion_changes}
    _LOG.info(
        "companion settings allowed: operation=%r device=%r keys=%r",
        "settings.set", device_id, ",".join(sorted(changed)))
    reloadable = [key for key in engine_changes if key not in _RESTART_HINT]
    hints = {key: hint for key, hint in _RESTART_HINT.items() if key in engine_changes}
    return {
        "changed": changed,
        "companion_changed": companion_changes,
        "reloadable": reloadable,
        "hints": hints,
        "restart_required": [],
        "backup_created": bool(backup),
    }


def apply_companion_patch(exec_config: CompanionExecConfig,
                          listener_config: CompanionListenerConfig,
                          changes: dict[str, Any]
                          ) -> tuple[CompanionExecConfig, CompanionListenerConfig]:
    """Apply validated companion-policy changes to the live configs.

    Pure: returns the new ``(exec_config, listener_config)`` pair for the
    listener to install. Values are re-validated here so this function is
    safe to call on its own, not only on :func:`apply_patch` output.
    """
    exec_kwargs: dict[str, Any] = {}
    listener_kwargs: dict[str, Any] = {}
    for key, value in changes.items():
        if key == "companion.default_exec_mode":
            exec_kwargs["default_exec_mode"] = _assert_type(
                key, value, *_COMPANION_SCHEMA[key])
        elif key == "companion.command_timeout":
            exec_kwargs["command_timeout"] = _assert_type(
                key, value, *_COMPANION_SCHEMA[key])
        elif key == "companion.idle_timeout":
            listener_kwargs["idle_timeout_seconds"] = _assert_type(
                key, value, *_COMPANION_SCHEMA[key])
        else:
            raise CompanionConfigError("invalid_patch", "settings key not allowed")
    new_exec = dataclasses.replace(exec_config, **exec_kwargs) if exec_kwargs else exec_config
    new_listener = (dataclasses.replace(listener_config, **listener_kwargs)
                    if listener_kwargs else listener_config)
    return new_exec, new_listener
