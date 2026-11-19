"""Strict parsing and authorization-shape checks for the remote companion protocol.

This module deliberately does not authenticate credentials, open sockets, or dispatch
requests. It is a small host-side guard that can be used by a future listener before
any handler runs. Keep secrets out of exceptions and logs.
"""
from __future__ import annotations

import json
from typing import Any

MAX_FRAME_BYTES = 256 * 1024
MAX_PROMPT_BYTES = 64 * 1024
MIN_CLIENT_NONCE_BYTES = 16
PROTOCOL_VERSION = "1.0"

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
    elif isinstance(payload, str):
        try:
            raw = payload.encode("utf-8")
        except UnicodeEncodeError:
            raise CompanionProtocolError("invalid frame") from None
    else:
        raise CompanionProtocolError("invalid frame")
    if len(raw) > MAX_FRAME_BYTES:
        raise CompanionProtocolError("frame too large")
    try:
        value = json.loads(raw, object_pairs_hook=_reject_duplicate_keys)
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
        session_id = frame.get("session_id")
        if session_id is not None:
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

    raise CompanionProtocolError("unsupported message")


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


_PAIRING_V2_BASE64URL = {
    "pairing_id": 43,       # 32 random bytes, unpadded URL-safe base64
    "public_key": 43,       # 32-byte Ed25519 public key
    "challenge_id": 22,     # 16 random bytes, unpadded URL-safe base64
    "signature": 86,        # 64-byte Ed25519 signature
}


def validate_pairing_v2_frame(payload: str | bytes) -> dict[str, Any]:
    """Validate one unauthenticated v2 pairing frame without changing v1 auth.

    This parser is deliberately separate from validate_client_frame(): a future
    listener must explicitly route pairing frames to a rate-limited pairing
    handler and must never treat them as authenticated companion operations.
    """
    frame = _object(payload)
    kind = frame.get("type")
    if kind == "pairing.request":
        allowed = {"type", "protocol", "pairing_id", "device_id", "public_key"}
        required = ("pairing_id", "device_id", "public_key")
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
        _required_string(frame, name, max_bytes=128 if name == "device_id" else 128)
    for name, expected_length in _PAIRING_V2_BASE64URL.items():
        if name in frame:
            value = frame[name]
            if (not isinstance(value, str) or len(value) != expected_length
                    or any(char not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                           for char in value)):
                raise CompanionProtocolError("invalid pairing field")
    return frame
