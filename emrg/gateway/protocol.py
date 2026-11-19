"""Gateway frame vocabulary and structured errors.

The gateway speaks two layers on one WebSocket:

* the daemon's frames, forwarded unchanged (``task``, ``message_delta``,
  ``tool_start``, ``done`` ...). The phone's contract for those is the engine's
  own protocol, which is why there is no second set of chat types to drift.
* gateway frames, prefixed ``gw_``, for the operations the daemon never had and
  for the handshake. Every response echoes the request's ``rid`` when one was
  supplied, so a client can correlate without guessing from the type.
"""

from __future__ import annotations

import json
from typing import Any

from emrg.gateway import API_VERSION, MIN_API_VERSION


class GatewayError(Exception):
    """A refusal that can be rendered to the phone without a traceback."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        detail: Any = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.detail = detail

    def frame(self, rid: str | None = None) -> dict:
        out = {
            "type": "gw_error",
            "code": self.code,
            "message": self.message,
            "retryable": self.retryable,
        }
        if rid:
            out["rid"] = rid
        if self.detail is not None:
            out["detail"] = self.detail
        return out


# Stable codes the Android client switches on. A new code is additive; an
# existing one is never repurposed.
ERR_BAD_FRAME = "bad_frame"
ERR_UNKNOWN_TYPE = "unknown_type"
ERR_FORBIDDEN = "forbidden"
ERR_AUTH_REQUIRED = "auth_required"
ERR_AUTH_FAILED = "auth_failed"
ERR_VERSION = "api_version"
ERR_RATE_LIMITED = "rate_limited"
ERR_BUSY = "busy"
ERR_NOT_FOUND = "not_found"
ERR_DAEMON_UNAVAILABLE = "daemon_unavailable"
ERR_SANDBOX_UNAVAILABLE = "sandbox_unavailable"
ERR_POLICY_REFUSED = "policy_refused"
ERR_INVALID_ARGUMENT = "invalid_argument"
ERR_APPROVAL_REQUIRED = "approval_required"
ERR_CONFLICT = "conflict"
ERR_INTERNAL = "internal"


def frame(type_: str, rid: str | None = None, **fields: Any) -> dict:
    out: dict[str, Any] = {"type": type_}
    if rid:
        out["rid"] = rid
    out.update(fields)
    return out


def parse_frame(raw: str | bytes) -> dict:
    """Parse one WebSocket text frame into a JSON object.

    ``json.loads`` can return a list or a string; every handler assumes a
    mapping, so the shape is checked once here rather than at forty sites.
    """
    try:
        value = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise GatewayError(ERR_BAD_FRAME, f"invalid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise GatewayError(ERR_BAD_FRAME, "a frame must be a JSON object")
    return value


def major(version: str) -> int:
    try:
        return int(str(version).split(".", 1)[0])
    except (TypeError, ValueError):
        return -1


def compatible(client_version: str) -> bool:
    """Whether this Android contract can talk to this gateway.

    Only the major is compared: a 1.3 client and a 1.7 gateway both speak the
    v1 frame vocabulary, and the hello payload tells the client which optional
    capabilities exist.
    """
    return major(client_version) == major(API_VERSION) and major(client_version) >= major(MIN_API_VERSION)


def hello(config, *, engine_version: str = "", host: str = "", port: int = 0) -> dict:
    return {
        "type": "gw_hello_ok",
        "api_version": API_VERSION,
        "min_api_version": MIN_API_VERSION,
        "gateway_version": __import__("emrg.gateway", fromlist=["__version__"]).__version__,
        "engine_version": engine_version,
        "host": host,
        "port": port,
        "capabilities": [
            "chat.stream",
            "tools.monitor",
            "sessions",
            "memory",
            "files.read",
            "files.write",
            "terminal",
            "sandbox",
            "rant",
            "evolution",
            "tasks",
            "skills",
            "logs",
            "config",
            "devices",
        ],
        "limits": {
            "frame_bytes": config.frame_limit,
            "command_timeout": config.effective_command_timeout,
            "edit_file_bytes": 2 * 1024 * 1024,
            "max_devices": config.max_devices,
        },
    }
