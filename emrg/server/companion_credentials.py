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

from emrg.tools.base import non_regular_kind

REGISTRY_VERSION = 1
TOKEN_BYTES = 32


class CredentialRegistryError(ValueError):
    """Safe credential registry failure."""


def _token_hash(token: str, salt: str) -> str:
    return hashlib.sha256((salt + token).encode("utf-8")).hexdigest()


def _record_generation(record: dict[str, Any]) -> str | None:
    """Return a rotation-sensitive opaque ID, including for legacy registry entries."""
    generation = record.get("generation")
    if isinstance(generation, str) and generation:
        return generation
    salt, verifier = record.get("salt"), record.get("verifier")
    if not isinstance(salt, str) or not isinstance(verifier, str):
        return None
    return hashlib.sha256((salt + ":" + verifier).encode("utf-8")).hexdigest()


def _bounded_text(value: object, max_bytes: int, error: str) -> str:
    if not isinstance(value, str) or not value:
        raise CredentialRegistryError(error)
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        raise CredentialRegistryError(error) from None
    if size > max_bytes:
        raise CredentialRegistryError(error)
    return value


class CredentialRegistry:
    """File-backed device credential verifier with revoke/rotate support."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self._lock = threading.RLock()

    def _read(self) -> dict[str, Any]:
        # An open is not a read: on a FIFO it blocks until a peer appears,
        # and this read runs on the daemon's event loop, so the block is
        # the whole daemon. Ask the kind first; absence keeps its existing
        # answer (an empty registry).
        try:
            kind = non_regular_kind(self.path)
        except FileNotFoundError:
            return {"version": REGISTRY_VERSION, "devices": {}}
        except OSError:
            raise CredentialRegistryError("credential registry unavailable") from None
        if kind is not None:
            raise CredentialRegistryError(
                f"credential registry is {kind}, not a regular file"
            )
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            raise CredentialRegistryError("credential registry unavailable") from None
        if (not isinstance(data, dict) or data.get("version") != REGISTRY_VERSION
                or not isinstance(data.get("devices"), dict)):
            raise CredentialRegistryError("credential registry invalid")
        return data

    def _write(self, data: dict[str, Any]) -> None:
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
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
        device_id = _bounded_text(device_id, 128, "invalid device")
        workspace_id = _bounded_text(workspace_id, 128, "invalid workspace")
        cwd = _bounded_text(cwd, 4096, "invalid workspace path")
        allowed = {"status", "sessions.list", "chat.send"}
        if not isinstance(scopes, (set, frozenset)) or not scopes or not scopes <= allowed:
            raise CredentialRegistryError("invalid scopes")
        token = secrets.token_urlsafe(TOKEN_BYTES)
        salt = secrets.token_hex(16)
        with self._lock:
            data = self._read()
            data["devices"][device_id] = {
                "salt": salt, "verifier": _token_hash(token, salt),
                "generation": secrets.token_hex(16),
                "workspace_id": workspace_id, "cwd": cwd,
                "scopes": sorted(scopes), "revoked": False,
            }
            self._write(data)
        return token

    def verify(self, *, device_id: str, credential: str) -> dict[str, Any] | None:
        """Verify a presented token. Return scope-bound metadata, not verifier data."""
        try:
            device_id = _bounded_text(device_id, 128, "invalid device")
            credential = _bounded_text(credential, 512, "invalid credential")
        except CredentialRegistryError:
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
        generation = _record_generation(record)
        if generation is None:
            return None
        return {"device_id": device_id, "workspace_id": workspace_id,
                "cwd": cwd, "scopes": frozenset(scopes), "generation": generation}

    def is_current(self, identity: dict[str, Any]) -> bool:
        """Check that an authenticated connection still matches an active registry record.

        Revocation and credential rotation both invalidate existing connections.
        """
        device_id = identity.get("device_id")
        if not isinstance(device_id, str):
            return False
        try:
            with self._lock:
                record = self._read()["devices"].get(device_id)
        except CredentialRegistryError:
            return False
        if not isinstance(record, dict) or record.get("revoked"):
            return False
        scopes = record.get("scopes")
        return (
            record.get("workspace_id") == identity.get("workspace_id")
            and record.get("cwd") == identity.get("cwd")
            and _record_generation(record) == identity.get("generation")
            and isinstance(scopes, list)
            and frozenset(scopes) == frozenset(identity.get("scopes", ()))
        )

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
