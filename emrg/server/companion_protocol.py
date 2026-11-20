"""Strict parsing and authorization-shape checks for the remote companion protocol.

This module deliberately does not authenticate credentials, open sockets, or dispatch
requests. It is a small host-side guard that can be used by a future listener before
any handler runs. Keep secrets out of exceptions and logs.
"""
from __future__ import annotations

import base64
import json
import re
from typing import Any, Callable

from emrg.server.companion_scopes import ALLOWED_SCOPES, MAX_SCOPES

MAX_FRAME_BYTES = 256 * 1024
MAX_PROMPT_BYTES = 64 * 1024
MIN_CLIENT_NONCE_BYTES = 16
PROTOCOL_VERSION = "1.0"
_PAIRING_DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

class CompanionProtocolError(ValueError):
    """Safe, non-sensitive protocol rejection."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CompanionProtocolError("duplicate frame field")
        result[key] = value
    return result


def _require_fields(frame: dict[str, Any], allowed: set[str]) -> None:
    if not set(frame) <= allowed:
        raise CompanionProtocolError("unsupported frame field")


def _object(payload: str | bytes) -> dict[str, Any]:
    if isinstance(payload, bytes):
        raw = payload
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise CompanionProtocolError("invalid frame") from None
    elif isinstance(payload, str):
        text = payload
        try:
            raw = text.encode("utf-8")
        except UnicodeEncodeError:
            raise CompanionProtocolError("invalid frame") from None
    else:
        raise CompanionProtocolError("invalid frame")
    if len(raw) > MAX_FRAME_BYTES:
        raise CompanionProtocolError("frame too large")
    try:
        # The generic JSON byte decoder auto-detects UTF-16/32; the wire contract is UTF-8 only.
        value = json.loads(text, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError, CompanionProtocolError):
        raise CompanionProtocolError("invalid frame") from None
    if not isinstance(value, dict):
        raise CompanionProtocolError("invalid frame")
    return value


def _required_string(frame: dict[str, Any], name: str, *, max_bytes: int = 512) -> str:
    value = frame.get(name)
    if not isinstance(value, str) or not value.strip():
        raise CompanionProtocolError("invalid frame")
    try:
        encoded_length = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        raise CompanionProtocolError("invalid frame") from None
    if encoded_length > max_bytes:
        raise CompanionProtocolError("invalid frame")
    return value


def validate_client_frame(
    payload: str | bytes,
    *,
    authenticated: bool = False,
    scopes: frozenset[str] = frozenset(),
    workspace_id: str | None = None,
) -> dict[str, Any]:
    """Parse one client frame and enforce the protocol's pre-dispatch boundary.

    Authentication verification remains the listener's responsibility. Before auth,
    only the auth envelope is accepted. After auth, the allowed message types are
    constrained by granted scopes and the bound workspace.
    """
    frame = _object(payload)
    kind = frame.get("type")
    if not isinstance(kind, str):
        raise CompanionProtocolError("invalid frame")

    if not authenticated:
        _require_fields(frame, {"type", "protocol", "device_id", "credential", "client_nonce"})
        if kind != "auth":
            raise CompanionProtocolError("authentication required")
        if frame.get("protocol") != PROTOCOL_VERSION:
            raise CompanionProtocolError("unsupported protocol")
        _required_string(frame, "device_id", max_bytes=128)
        _required_string(frame, "credential", max_bytes=512)
        nonce = _required_string(frame, "client_nonce", max_bytes=128)
        if len(nonce.encode("utf-8")) < MIN_CLIENT_NONCE_BYTES:
            raise CompanionProtocolError("invalid frame")
        return frame

    if kind == "status":
        _require_fields(frame, {"type", "request_id"})
        if "status" not in scopes:
            raise CompanionProtocolError("permission denied")
        _required_string(frame, "request_id", max_bytes=128)
        return frame

    if kind == "sessions.list":
        _require_fields(frame, {"type", "request_id", "workspace_id"})
        if "sessions.list" not in scopes:
            raise CompanionProtocolError("permission denied")
        _required_string(frame, "request_id", max_bytes=128)
        requested_workspace = _required_string(frame, "workspace_id", max_bytes=128)
        if not workspace_id or requested_workspace != workspace_id:
            raise CompanionProtocolError("workspace denied")
        return frame

    if kind == "chat.send":
        _require_fields(frame, {"type", "request_id", "workspace_id", "prompt", "session_id"})
        if "chat.send" not in scopes:
            raise CompanionProtocolError("permission denied")
        _required_string(frame, "request_id", max_bytes=128)
        requested_workspace = _required_string(frame, "workspace_id", max_bytes=128)
        if not workspace_id or requested_workspace != workspace_id:
            raise CompanionProtocolError("workspace denied")
        prompt = _required_string(frame, "prompt", max_bytes=MAX_PROMPT_BYTES)
        # The client always carries a session id (its active local id or a
        # route remote id); a turn without one has nowhere to stream to.
        session_id = frame.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            raise CompanionProtocolError("invalid frame")
        try:
            session_id_bytes = session_id.encode("utf-8")
        except UnicodeEncodeError:
            raise CompanionProtocolError("invalid frame") from None
        if len(session_id_bytes) > 128:
            raise CompanionProtocolError("invalid frame")
        # Return normalized data. The host must supply cwd from its pairing record;
        # never trust a remote cwd/tools/sandbox override.
        return {"type": kind, "request_id": frame["request_id"],
                "workspace_id": workspace_id, "session_id": session_id,
                "prompt": prompt}

    spec = _FRAME_SPECS.get(kind)
    if spec is not None:
        return _validate_spec_frame(frame, kind, spec,
                                    scopes=scopes, workspace_id=workspace_id)

    raise CompanionProtocolError("unsupported message")


def _v_text(max_bytes: int, *, allow_empty: bool = False) -> Callable[[Any], str]:
    def check(value: Any) -> str:
        if not isinstance(value, str) or (not allow_empty and not value.strip()):
            raise CompanionProtocolError("invalid frame")
        try:
            size = len(value.encode("utf-8"))
        except UnicodeEncodeError:
            raise CompanionProtocolError("invalid frame") from None
        if size > max_bytes:
            raise CompanionProtocolError("invalid frame")
        return value
    return check


def _v_int(minimum: int | None = None, maximum: int | None = None) -> Callable[[Any], int]:
    def check(value: Any) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise CompanionProtocolError("invalid frame")
        if minimum is not None and value < minimum:
            raise CompanionProtocolError("invalid frame")
        if maximum is not None and value > maximum:
            raise CompanionProtocolError("invalid frame")
        return value
    return check


def _v_number(minimum: float | None = None, maximum: float | None = None) -> Callable[[Any], float]:
    def check(value: Any) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise CompanionProtocolError("invalid frame")
        number = float(value)
        if number != number or number in (float("inf"), float("-inf")):
            raise CompanionProtocolError("invalid frame")
        if minimum is not None and number < minimum:
            raise CompanionProtocolError("invalid frame")
        if maximum is not None and number > maximum:
            raise CompanionProtocolError("invalid frame")
        return number
    return check


def _v_bool(value: Any) -> bool:
    if not isinstance(value, bool):
        raise CompanionProtocolError("invalid frame")
    return value


def _v_dict(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CompanionProtocolError("invalid frame")
    return value


def _v_one_of(choices: frozenset[str]) -> Callable[[Any], str]:
    def check(value: Any) -> str:
        if not isinstance(value, str) or value not in choices:
            raise CompanionProtocolError("invalid frame")
        return value
    return check


def _v_sha256(value: Any) -> str:
    if (not isinstance(value, str) or len(value) != 64
            or any(char not in "0123456789abcdef" for char in value.lower())):
        raise CompanionProtocolError("invalid frame")
    return value.lower()


# (scope, allowed fields, required fields, per-field validators,
#  extra conditional requirement or None)
# Batch A frames are dispatched by the listener. Batch B/C frames are
# validated here against the frozen wire contract so both sides agree on
# shapes now; their dispatch lands in later batches and the listener
# answers ``not_implemented`` until then. B/C *field* shapes are
# provisional — only the frame names and scopes are frozen.
_FRAME_SPECS: dict[str, tuple[str, set[str], set[str], dict[str, Callable[[Any], Any]], Any]] = {
    # Batch A — sessions.manage
    "sessions.resume": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id"}, {"session_id"},
        {"session_id": _v_text(128)}, None),
    "sessions.rename": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id", "title"}, {"session_id"},
        {"session_id": _v_text(128), "title": _v_text(256, allow_empty=True)}, None),
    "sessions.delete": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id"}, {"session_id"},
        {"session_id": _v_text(128)}, None),
    "sessions.clear": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id"}, {"session_id"},
        {"session_id": _v_text(128)}, None),
    "sessions.compact": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id"}, {"session_id"},
        {"session_id": _v_text(128)}, None),
    "sessions.rewind": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id", "record_index"},
        {"session_id", "record_index"},
        {"session_id": _v_text(128), "record_index": _v_int(0)}, None),
    "sessions.history": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id", "include_records",
         "limit", "offset", "before_index"}, {"session_id"},
        {"session_id": _v_text(128), "include_records": _v_bool,
         "limit": _v_int(1, 1000), "offset": _v_int(0), "before_index": _v_int(0)}, None),
    "sessions.resolve_cwd": ("sessions.manage",
        {"type", "request_id", "workspace_id", "session_id"}, {"session_id"},
        {"session_id": _v_text(128)}, None),
    # Batch A — chat.send (cancel / approve ride the chat scope)
    # chat.cancel: session_id is optional — without one the listener cancels
    # every turn this connection is subscribed to.
    "chat.cancel": ("chat.send",
        {"type", "request_id", "workspace_id", "session_id"}, set(),
        {"session_id": _v_text(128)}, None),
    # chat.approve: approval_request_id is the approval_request frame's
    # request_id; session_id is accepted (optional) for client correlation.
    "chat.approve": ("chat.send",
        {"type", "request_id", "workspace_id", "approval_request_id", "approved",
         "session_id"},
        {"approval_request_id", "approved"},
        {"approval_request_id": _v_text(128), "approved": _v_bool,
         "session_id": _v_text(128)}, None),
    # Batch A — files.read / sandbox.manage
    "files.list": ("files.read",
        {"type", "request_id", "workspace_id", "path"}, {"path"},
        {"path": _v_text(4096)}, None),
    "files.read": ("files.read",
        {"type", "request_id", "workspace_id", "path", "start_line", "line_limit"},
        {"path"},
        {"path": _v_text(4096), "start_line": _v_int(1), "line_limit": _v_int(1, 2000)}, None),
    "sandbox.set": ("sandbox.manage",
        {"type", "request_id", "workspace_id", "session_id", "mode"},
        {"session_id", "mode"},
        {"session_id": _v_text(128), "mode": _v_text(64)}, None),
    "sandbox.roots": ("sandbox.manage",
        {"type", "request_id", "workspace_id", "session_id", "op", "path", "kind"},
        {"session_id", "op"},
        {"session_id": _v_text(128), "op": _v_one_of(frozenset({"list", "add", "remove"})),
         "path": _v_text(4096), "kind": _v_text(64)},
        lambda frame: frame.get("op") in ("add", "remove") and "path" not in frame),
    # Batch A — terminal.exec / files.write
    "terminal.exec": ("terminal.exec",
        {"type", "request_id", "workspace_id", "command", "session_id", "mode",
         "timeout", "confirm_danger", "confirm_risky"}, {"command"},
        {"command": _v_text(MAX_PROMPT_BYTES), "session_id": _v_text(128),
         "mode": _v_text(64), "timeout": _v_number(1.0, 600.0),
         "confirm_danger": _v_bool, "confirm_risky": _v_bool}, None),
    "terminal.exec_cancel": ("terminal.exec",
        {"type", "request_id", "workspace_id", "exec_id"}, {"exec_id"},
        {"exec_id": _v_text(64)}, None),
    "files.write": ("files.write",
        {"type", "request_id", "workspace_id", "path", "content", "session_id",
         "expected_sha256", "create_parents", "mode", "confirm_danger"},
        {"path", "content"},
        {"path": _v_text(4096), "content": _v_text(2 * 1024 * 1024),
         "session_id": _v_text(128), "expected_sha256": _v_sha256,
         "create_parents": _v_bool, "mode": _v_text(64), "confirm_danger": _v_bool}, None),
    "files.delete": ("files.write",
        {"type", "request_id", "workspace_id", "path", "session_id", "mode",
         "confirm_danger"}, {"path"},
        {"path": _v_text(4096), "session_id": _v_text(128),
         "mode": _v_text(64), "confirm_danger": _v_bool}, None),
    # Batch B/C — validated now, dispatched later (see note above).
    "hosts.list": ("hosts.list",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "memory.list": ("memory.read",
        {"type", "request_id", "workspace_id", "scope", "session_id"}, set(),
        {"scope": _v_text(64), "session_id": _v_text(128)}, None),
    "memory.read": ("memory.read",
        {"type", "request_id", "workspace_id", "memory_id", "scope", "session_id"},
        {"memory_id"},
        {"memory_id": _v_text(512), "scope": _v_text(64), "session_id": _v_text(128)}, None),
    "models.list": ("models.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "models.set": ("models.manage",
        {"type", "request_id", "workspace_id", "model"}, {"model"},
        {"model": _v_text(512)}, None),
    "rant.list": ("rant.submit",
        {"type", "request_id", "workspace_id", "status"}, set(),
        {"status": _v_text(64)}, None),
    "rant.submit": ("rant.submit",
        {"type", "request_id", "workspace_id", "message", "agent"}, {"message"},
        {"message": _v_text(MAX_PROMPT_BYTES), "agent": _v_text(128)}, None),
    "evolution.summary": ("evolution.read",
        {"type", "request_id", "workspace_id", "limit"}, set(),
        {"limit": _v_int(1, 20)}, None),
    "tasks.list": ("tasks.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "tasks.trigger": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name"}, {"name"},
        {"name": _v_text(512)}, None),
    "tasks.create": ("tasks.manage",
        {"type", "request_id", "workspace_id", "task"}, {"task"},
        {"task": _v_dict}, None),
    "tasks.update": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name", "task"}, {"name"},
        {"name": _v_text(512), "task": _v_dict}, None),
    "tasks.delete": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name"}, {"name"},
        {"name": _v_text(512)}, None),
    "tasks.templates": ("tasks.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "tasks.template_create": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name", "template"}, {"name", "template"},
        {"name": _v_text(512), "template": _v_dict}, None),
    "tasks.template_update": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name", "template"}, {"name", "template"},
        {"name": _v_text(512), "template": _v_dict}, None),
    "tasks.template_delete": ("tasks.manage",
        {"type", "request_id", "workspace_id", "name"}, {"name"},
        {"name": _v_text(512)}, None),
    "skills.list": ("skills.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "skills.available": ("skills.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "skills.install": ("skills.manage",
        {"type", "request_id", "workspace_id", "name", "confirmed"}, {"name"},
        {"name": _v_text(512), "confirmed": _v_bool}, None),
    "skills.update": ("skills.manage",
        {"type", "request_id", "workspace_id", "name", "confirmed"}, {"name"},
        {"name": _v_text(512), "confirmed": _v_bool}, None),
    "logs.tail": ("logs.read",
        {"type", "request_id", "workspace_id", "source", "lines"}, set(),
        {"source": _v_text(64), "lines": _v_int(1, 1000)}, None),
    "audit.tail": ("audit.read",
        {"type", "request_id", "workspace_id", "limit"}, set(),
        {"limit": _v_int(1, 1000)}, None),
    "devices.list": ("devices.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "devices.revoke": ("devices.manage",
        {"type", "request_id", "workspace_id", "device_id"}, {"device_id"},
        {"device_id": _v_text(128)}, None),
    "settings.get": ("settings.manage",
        {"type", "request_id", "workspace_id"}, set(), {}, None),
    "settings.set": ("settings.manage",
        {"type", "request_id", "workspace_id", "patch"}, {"patch"},
        {"patch": _v_dict}, None),
}


def _validate_spec_frame(
    frame: dict[str, Any],
    kind: str,
    spec: tuple[str, set[str], set[str], dict[str, Callable[[Any], Any]], Any],
    *,
    scopes: frozenset[str],
    workspace_id: str | None,
) -> dict[str, Any]:
    scope, allowed, required, validators, require_when = spec
    _require_fields(frame, allowed)
    if scope not in scopes:
        raise CompanionProtocolError("permission denied")
    _required_string(frame, "request_id", max_bytes=128)
    requested_workspace = _required_string(frame, "workspace_id", max_bytes=128)
    if not workspace_id or requested_workspace != workspace_id:
        raise CompanionProtocolError("workspace denied")
    if not required <= set(frame):
        raise CompanionProtocolError("invalid frame")
    if require_when is not None and require_when(frame):
        raise CompanionProtocolError("invalid frame")
    normalized: dict[str, Any] = {
        "type": kind, "request_id": frame["request_id"], "workspace_id": workspace_id,
    }
    for name, validator in validators.items():
        if name in frame:
            normalized[name] = validator(frame[name])
    return normalized


def encode_server_frame(frame: dict[str, Any]) -> bytes:
    """Encode a JSON object within the shared frame size limit."""
    if not isinstance(frame, dict):
        raise CompanionProtocolError("invalid frame")
    try:
        raw = json.dumps(frame, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError):
        raise CompanionProtocolError("invalid frame") from None
    if len(raw) > MAX_FRAME_BYTES:
        raise CompanionProtocolError("frame too large")
    return raw


_PAIRING_V2_FIXED_BASE64URL = {
    "pairing_id": (43, 32),
    "challenge_id": (22, 16),
}


def _decode_canonical_base64url(value: object, *, min_bytes: int, max_bytes: int) -> bool:
    if not isinstance(value, str) or not value or len(value) > (max_bytes * 8 + 5) // 6:
        return False
    if any(char not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" for char in value):
        return False
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except ValueError:
        return False
    canonical = base64.urlsafe_b64encode(decoded).rstrip(b"=").decode("ascii")
    return min_bytes <= len(decoded) <= max_bytes and canonical == value



def validate_pairing_v2_frame(payload: str | bytes) -> dict[str, Any]:
    """Validate one unauthenticated v2 pairing frame without changing v1 auth.

    This parser is deliberately separate from validate_client_frame(): a future
    listener must explicitly route pairing frames to a rate-limited pairing
    handler and must never treat them as authenticated companion operations.
    """
    frame = _object(payload)
    kind = frame.get("type")
    if kind == "pairing.request":
        allowed = {"type", "protocol", "pairing_id", "device_id", "key_algorithm", "public_key", "requested_scopes", "client_nonce"}
        required = ("pairing_id", "device_id", "key_algorithm", "public_key", "client_nonce")
    elif kind == "pairing.redeem":
        allowed = {"type", "protocol", "pairing_id", "device_id",
                   "challenge_id", "signature"}
        required = ("pairing_id", "device_id", "challenge_id", "signature")
    else:
        raise CompanionProtocolError("unsupported pairing message")
    _require_fields(frame, allowed)
    if frame.get("protocol") != "2.0":
        raise CompanionProtocolError("unsupported pairing protocol")
    for name in required:
        _required_string(frame, name, max_bytes=128)
    if not _PAIRING_DEVICE_ID_RE.fullmatch(frame["device_id"]):
        raise CompanionProtocolError("invalid pairing field")
    if kind == "pairing.request" and frame["key_algorithm"] != "P-256":
        raise CompanionProtocolError("unsupported pairing key algorithm")
    names = ("pairing_id", "challenge_id") if kind == "pairing.redeem" else ("pairing_id",)
    for name in names:
        encoded_length, decoded_length = _PAIRING_V2_FIXED_BASE64URL[name]
        value = frame.get(name)
        if not isinstance(value, str) or len(value) != encoded_length:
            raise CompanionProtocolError("invalid pairing field")
        if not _decode_canonical_base64url(value, min_bytes=decoded_length, max_bytes=decoded_length):
            raise CompanionProtocolError("invalid pairing field")
    if kind == "pairing.request":
        if not _decode_canonical_base64url(frame["public_key"], min_bytes=80, max_bytes=256):
            raise CompanionProtocolError("invalid pairing field")
        scopes = frame.get("requested_scopes")
        if (not isinstance(scopes, list) or not 1 <= len(scopes) <= MAX_SCOPES
                or any(not isinstance(scope, str) or scope not in ALLOWED_SCOPES for scope in scopes)
                or len(set(scopes)) != len(scopes)):
            raise CompanionProtocolError("invalid pairing field")
        nonce = frame.get("client_nonce")
        if not _decode_canonical_base64url(nonce, min_bytes=16, max_bytes=64):
            raise CompanionProtocolError("invalid pairing field")
    if kind == "pairing.redeem":
        if not _decode_canonical_base64url(frame["signature"], min_bytes=64, max_bytes=80):
            raise CompanionProtocolError("invalid pairing field")
    return frame
