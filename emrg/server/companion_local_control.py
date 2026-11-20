"""Narrow in-process API for trusted local Companion approval controls.

This adapter is deliberately transport-independent. Electron/daemon wiring is a
separate integration step; callers must not expose it to remote clients.
"""
from __future__ import annotations

import base64
import io
import json
import re
import time
from typing import Any
from urllib.parse import urlsplit

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_scopes import ALLOWED_SCOPES, MAX_SCOPES


_PAIRING_ID = re.compile(r"^[A-Za-z0-9_-]{43}$")
_PENDING_LIMIT = 32
_PENDING_FIELDS = frozenset({
    "pairing_id", "device_id", "public_key_sha256", "workspace_id",
    "cwd", "scopes", "approved", "approved_scopes", "expires_in",
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

        if operation == "pairing.qr":
            data = self._object(payload, {"descriptor"})
            descriptor = data["descriptor"]
            if not isinstance(descriptor, str) or not 1 <= len(descriptor.encode("utf-8")) <= 2048:
                raise CompanionLocalControlError("invalid pairing descriptor")
            try:
                parsed = json.loads(descriptor)
                if (not isinstance(parsed, dict) or
                        set(parsed) != {"version", "endpoint", "fingerprint_sha256", "expires_at", "pairing_id"} or
                        parsed.get("version") != 2 or
                        isinstance(parsed.get("expires_at"), bool) or
                        not isinstance(parsed.get("expires_at"), int) or
                        parsed["expires_at"] <= int(time.time()) or
                        not isinstance(parsed.get("pairing_id"), str) or
                        not _PAIRING_ID.fullmatch(parsed["pairing_id"]) or
                        not isinstance(parsed.get("fingerprint_sha256"), str) or
                        not re.fullmatch(r"(?:[A-Fa-f0-9]{2}:){31}[A-Fa-f0-9]{2}", parsed["fingerprint_sha256"])):
                    raise ValueError("invalid descriptor fields")
                endpoint = urlsplit(parsed.get("endpoint", ""))
                if (endpoint.scheme != "wss" or not endpoint.hostname or
                        endpoint.username is not None or endpoint.password is not None or
                        endpoint.query or endpoint.fragment or endpoint.path not in ("", "/")):
                    raise ValueError("invalid descriptor endpoint")
                if endpoint.port is not None and not 1 <= endpoint.port <= 65535:
                    raise ValueError("invalid descriptor port")
                import qrcode
                from qrcode.image.svg import SvgPathImage
                qr = qrcode.QRCode(
                    version=None, error_correction=qrcode.constants.ERROR_CORRECT_M,
                    box_size=8, border=4,
                )
                qr.add_data(descriptor)
                qr.make(fit=True)
                image = qr.make_image(image_factory=SvgPathImage)
                buffer = io.BytesIO()
                image.save(buffer)
                svg = buffer.getvalue()
                if not svg or len(svg) > 512 * 1024:
                    raise ValueError("QR image exceeds size limit")
                return {"data_uri": "data:image/svg+xml;base64," + base64.b64encode(svg).decode("ascii")}
            except CompanionLocalControlError:
                raise
            except Exception:
                raise CompanionLocalControlError("could not create pairing QR") from None

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
                approved_scopes = row["approved_scopes"]
                if approved_scopes is not None and (
                        not isinstance(approved_scopes, list)
                        or any(not isinstance(scope, str) for scope in approved_scopes)):
                    raise CompanionLocalControlError("invalid pending approval state")
                safe_rows.append({
                    "pairing_id": self._pairing_id(row["pairing_id"]),
                    "device_id": row["device_id"],
                    "public_key_sha256": row["public_key_sha256"],
                    "workspace_id": row["workspace_id"],
                    "cwd": row["cwd"],
                    "scopes": list(row["scopes"]),
                    "approved": row["approved"],
                    "approved_scopes": (
                        list(approved_scopes) if approved_scopes is not None else None
                    ),
                    "expires_in": row["expires_in"],
                })
            return {"items": safe_rows}

        if operation == "pairing.decide":
            # Exact shape: {"pairing_id", "approved"} for approve-all-or-
            # reject, or the same plus "scopes" to approve a subset of the
            # requested scopes. "scopes" with approved=false is rejected:
            # a rejection grants nothing, so there is no subset to name.
            if not isinstance(payload, dict) or set(payload) not in (
                    {"pairing_id", "approved"}, {"pairing_id", "approved", "scopes"}):
                raise CompanionLocalControlError("invalid local control request")
            pairing_id = self._pairing_id(payload["pairing_id"])
            if not isinstance(payload["approved"], bool):
                raise CompanionLocalControlError("invalid approval decision")
            scopes = payload.get("scopes")
            if scopes is not None:
                if (not isinstance(scopes, list)
                        or not 1 <= len(scopes) <= MAX_SCOPES
                        or any(not isinstance(scope, str) or scope not in ALLOWED_SCOPES
                               for scope in scopes)
                        or len(set(scopes)) != len(scopes)):
                    raise CompanionLocalControlError("invalid approval scopes")
                if not payload["approved"]:
                    raise CompanionLocalControlError("invalid approval scopes")
            try:
                changed = self._host.decide_pairing_v2(
                    pairing_id, approved=payload["approved"], scopes=scopes)
            except ValueError:
                raise CompanionLocalControlError("pairing unavailable") from None
            return {"decided": bool(changed)}

        if operation == "pairing.cancel":
            data = self._object(payload, {"pairing_id"})
            pairing_id = self._pairing_id(data["pairing_id"])
            return {"cancelled": bool(self._host.cancel_pairing_v2(pairing_id))}

        raise CompanionLocalControlError("unknown local control operation")
