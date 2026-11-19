"""Narrow in-process API for trusted local Companion approval controls.

This adapter is deliberately transport-independent. Electron/daemon wiring is a
separate integration step; callers must not expose it to remote clients.
"""
from __future__ import annotations

import re
from typing import Any

from emrg.server.companion_host_service import CompanionHostService


_PAIRING_ID = re.compile(r"^[A-Za-z0-9_-]{43}$")
_PENDING_LIMIT = 32
_PENDING_FIELDS = frozenset({
    "pairing_id", "device_id", "public_key_sha256", "workspace_id",
    "cwd", "scopes", "approved", "expires_in",
})


class CompanionLocalControlError(ValueError):
    """Safe rejection from the trusted local Companion control boundary."""


class CompanionLocalControl:
    """Allow-listed, bounded operations over a host-owned pairing service.

    No operation accepts a path, public key, credential, scope override, or
    arbitrary method name. Approval decisions refer only to an immutable
    pairing transaction already held by the host service.
    """

    def __init__(self, host: CompanionHostService):
        if not isinstance(host, CompanionHostService):
            raise TypeError("host must be a CompanionHostService")
        self._host = host

    @staticmethod
    def _object(payload: object, keys: set[str]) -> dict[str, Any]:
        if not isinstance(payload, dict) or set(payload) != keys:
            raise CompanionLocalControlError("invalid local control request")
        return payload

    @staticmethod
    def _pairing_id(value: object) -> str:
        if not isinstance(value, str) or not _PAIRING_ID.fullmatch(value):
            raise CompanionLocalControlError("invalid pairing id")
        return value

    def dispatch(self, operation: str, payload: object | None = None) -> dict[str, Any]:
        """Execute one exact local operation and return only bounded metadata."""
        if not isinstance(operation, str):
            raise CompanionLocalControlError("unknown local control operation")

        if operation == "status":
            self._object({} if payload is None else payload, set())
            pending = self._host.pending_pairings_v2()
            return {"enabled": self._host.enabled, "pending_count": min(len(pending), _PENDING_LIMIT)}

        if operation == "pairing.pending.list":
            self._object({} if payload is None else payload, set())
            if not self._host.enabled:
                return {"items": []}
            rows = self._host.pending_pairings_v2()
            safe_rows: list[dict[str, Any]] = []
            for row in rows[:_PENDING_LIMIT]:
                if not isinstance(row, dict) or set(row) != _PENDING_FIELDS:
                    raise CompanionLocalControlError("invalid pending approval state")
                safe_rows.append({
                    "pairing_id": self._pairing_id(row["pairing_id"]),
                    "device_id": row["device_id"],
                    "public_key_sha256": row["public_key_sha256"],
                    "workspace_id": row["workspace_id"],
                    "cwd": row["cwd"],
                    "scopes": list(row["scopes"]),
                    "approved": row["approved"],
                    "expires_in": row["expires_in"],
                })
            return {"items": safe_rows}

        if operation == "pairing.decide":
            data = self._object(payload, {"pairing_id", "approved"})
            pairing_id = self._pairing_id(data["pairing_id"])
            if not isinstance(data["approved"], bool):
                raise CompanionLocalControlError("invalid approval decision")
            try:
                changed = self._host.decide_pairing_v2(pairing_id, approved=data["approved"])
            except ValueError:
                raise CompanionLocalControlError("pairing unavailable") from None
            return {"decided": bool(changed)}

        if operation == "pairing.cancel":
            data = self._object(payload, {"pairing_id"})
            pairing_id = self._pairing_id(data["pairing_id"])
            return {"cancelled": bool(self._host.cancel_pairing_v2(pairing_id))}

        raise CompanionLocalControlError("unknown local control operation")
