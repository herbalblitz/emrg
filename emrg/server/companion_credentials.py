"""Local companion credential registry.

Credentials are issued once, stored only as salted SHA-256 verifiers, and bound
to a workspace and explicit scopes. This module does not perform pairing approval
or network authentication; callers must enforce both before using it.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import tempfile
import threading
from pathlib import Path
from typing import Any

REGISTRY_VERSION = 1
TOKEN_BYTES = 32


class CredentialRegistryError(ValueError):
    """Safe credential registry failure."""


def _token_hash(token: str, salt: str) -> str:
    return hashlib.sha256((salt + token).encode("utf-8")).hexdigest()


class CredentialRegistry:
    """File-backed device credential verifier with revoke/rotate support."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self._lock = threading.RLock()

    def _read(self) -> dict[str, Any]:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {"version": REGISTRY_VERSION, "devices": {}}
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise CredentialRegistryError("credential registry unavailable") from None
        if (not isinstance(data, dict) or data.get("version") != REGISTRY_VERSION
                or not isinstance(data.get("devices"), dict)):
            raise CredentialRegistryError("credential registry invalid")
        return data

    def _write(self, data: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=str(self.path.parent), prefix=".companion-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                stream.write(json.dumps(data, sort_keys=True, separators=(",", ":")))
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)
        except OSError:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise CredentialRegistryError("credential registry write failed") from None

    def issue(self, *, device_id: str, workspace_id: str, cwd: str,
              scopes: set[str]) -> str:
        """Create/replace a device credential; returns the token exactly once."""
        if not device_id or len(device_id.encode()) > 128:
            raise CredentialRegistryError("invalid device")
        if not workspace_id or len(workspace_id.encode()) > 128:
            raise CredentialRegistryError("invalid workspace")
        if not cwd or len(cwd.encode()) > 4096:
            raise CredentialRegistryError("invalid workspace path")
        allowed = {"status", "sessions.list", "chat.send"}
        if not scopes or not scopes <= allowed:
            raise CredentialRegistryError("invalid scopes")
        token = secrets.token_urlsafe(TOKEN_BYTES)
        salt = secrets.token_hex(16)
        with self._lock:
            data = self._read()
            data["devices"][device_id] = {
                "salt": salt, "verifier": _token_hash(token, salt),
                "workspace_id": workspace_id, "cwd": cwd,
                "scopes": sorted(scopes), "revoked": False,
            }
            self._write(data)
        return token

    def verify(self, *, device_id: str, credential: str) -> dict[str, Any] | None:
        """Verify a presented token. Return scope-bound metadata, not verifier data."""
        if not isinstance(credential, str) or not credential:
            return None
        with self._lock:
            record = self._read()["devices"].get(device_id)
        if not isinstance(record, dict) or record.get("revoked"):
            return None
        salt, verifier = record.get("salt"), record.get("verifier")
        if not isinstance(salt, str) or not isinstance(verifier, str):
            return None
        if not hmac.compare_digest(_token_hash(credential, salt), verifier):
            return None
        scopes, workspace_id, cwd = record.get("scopes"), record.get("workspace_id"), record.get("cwd")
        if (not isinstance(scopes, list) or not all(isinstance(s, str) for s in scopes)
                or not isinstance(workspace_id, str) or not isinstance(cwd, str)):
            return None
        return {"device_id": device_id, "workspace_id": workspace_id,
                "cwd": cwd, "scopes": frozenset(scopes)}

    def revoke(self, device_id: str) -> bool:
        """Revoke a device credential; return False if it was not registered."""
        with self._lock:
            data = self._read()
            record = data["devices"].get(device_id)
            if not isinstance(record, dict):
                return False
            record["revoked"] = True
            self._write(data)
            return True

    def list_devices(self) -> list[dict[str, Any]]:
        """Return safe metadata only; never expose salts, verifiers, or tokens."""
        with self._lock:
            data = self._read()
        return [{"device_id": device_id,
                 "workspace_id": rec.get("workspace_id"),
                 "scopes": rec.get("scopes", []),
                 "revoked": bool(rec.get("revoked", False))}
                for device_id, rec in data["devices"].items()
                if isinstance(rec, dict)]
