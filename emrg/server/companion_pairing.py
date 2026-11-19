"""In-memory, local-approval companion pairing transaction.

Pending pairing secrets are one-time, short-lived and never persisted. The caller
must expose begin/approve only through a trusted local UI and redeem only over
the TLS listener after protocol validation. This module is not a network server.
"""
from __future__ import annotations

import hashlib
import math
import hmac
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Callable

from emrg.server.companion_credentials import CredentialRegistry

PAIRING_TTL_SECONDS = 180
PAIRING_MAX_ATTEMPTS = 5
PAIRING_SECRET_BYTES = 24
PAIRING_ID_BYTES = 32
PAIRING_MAX_PENDING = 32


class PairingError(ValueError):
    """Safe pairing transaction failure."""


def _bounded_text(value: object, max_bytes: int, error: str) -> str:
    if not isinstance(value, str) or not value:
        raise PairingError(error)
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        raise PairingError(error) from None
    if size > max_bytes:
        raise PairingError(error)
    return value


@dataclass
class _Pending:
    secret_hash: bytes
    created_at: float
    workspace_id: str
    cwd: str
    scopes: frozenset[str]
    approved: bool | None = None
    attempts: int = 0


class PairingTransactions:
    """Thread-safe single-use pairing transactions backed by a credential registry."""

    def __init__(self, registry: CredentialRegistry, *, clock: Callable[[], float] = time.monotonic):
        self._registry = registry
        self._clock = clock
        self._lock = threading.Lock()
        self._pending: dict[str, _Pending] = {}

    def begin(self, *, workspace_id: str, cwd: str, scopes: set[str]) -> dict[str, str | int]:
        allowed = {"status", "sessions.list", "chat.send"}
        _bounded_text(workspace_id, 128, "invalid pairing request")
        _bounded_text(cwd, 4096, "invalid pairing request")
        if (not isinstance(scopes, (set, frozenset)) or not scopes
                or not all(isinstance(scope, str) for scope in scopes)
                or not scopes <= allowed):
            raise PairingError("invalid pairing request")
        pairing_id = secrets.token_urlsafe(PAIRING_ID_BYTES)
        secret = secrets.token_urlsafe(PAIRING_SECRET_BYTES)
        now = self._clock()
        with self._lock:
            self._purge_expired(now)
            if len(self._pending) >= PAIRING_MAX_PENDING:
                raise PairingError("pairing capacity reached")
            self._pending[pairing_id] = _Pending(
                secret_hash=hashlib.sha256(secret.encode()).digest(),
                created_at=now, workspace_id=workspace_id, cwd=cwd,
                scopes=frozenset(scopes))
        # The secret is returned exactly once to the trusted local caller.
        return {"pairing_id": pairing_id, "pairing_secret": secret,
                "expires_in": PAIRING_TTL_SECONDS}

    def approve(self, pairing_id: str, *, approved: bool) -> bool:
        """Local user decision. False approval decisions permanently reject the request."""
        try:
            _bounded_text(pairing_id, 128, "pairing unavailable")
        except PairingError:
            return False
        if not isinstance(approved, bool):
            return False
        with self._lock:
            self._purge_expired(self._clock())
            pending = self._pending.get(pairing_id)
            if pending is None or pending.approved is not None:
                return False
            pending.approved = bool(approved)
            if not approved:
                del self._pending[pairing_id]
            return True

    def redeem(self, *, pairing_id: str, pairing_secret: str, device_id: str) -> str:
        """Redeem approved pairing once and issue device credential."""
        _bounded_text(pairing_id, 128, "pairing unavailable")
        _bounded_text(pairing_secret, 512, "pairing secret invalid")
        _bounded_text(device_id, 128, "invalid device")
        now = self._clock()
        with self._lock:
            self._purge_expired(now)
            pending = self._pending.get(pairing_id)
            if pending is None:
                raise PairingError("pairing unavailable")
            if pending.approved is not True:
                raise PairingError("pairing not approved")
            pending.attempts += 1
            presented = hashlib.sha256(pairing_secret.encode()).digest()
            if not hmac.compare_digest(pending.secret_hash, presented):
                if pending.attempts >= PAIRING_MAX_ATTEMPTS:
                    del self._pending[pairing_id]
                raise PairingError("pairing secret invalid")
            # Remove before issuing: this transaction cannot be redeemed twice,
            # even if registry persistence fails.
            del self._pending[pairing_id]
            workspace_id, cwd, scopes = pending.workspace_id, pending.cwd, set(pending.scopes)
        return self._registry.issue(device_id=device_id, workspace_id=workspace_id,
                                    cwd=cwd, scopes=scopes)

    def pending_requests(self) -> list[dict[str, object]]:
        """Safe local-UI view; never includes pairing secret or secret hash."""
        now = self._clock()
        with self._lock:
            self._purge_expired(now)
            return [
                {"pairing_id": key, "workspace_id": item.workspace_id,
                 "cwd": item.cwd, "scopes": sorted(item.scopes),
                 "approved": item.approved,
                 "expires_in": max(0, math.ceil(PAIRING_TTL_SECONDS - (now - item.created_at)))}
                for key, item in self._pending.items()
            ]

    def cancel(self, pairing_id: str) -> bool:
        try:
            _bounded_text(pairing_id, 128, "pairing unavailable")
        except PairingError:
            return False
        with self._lock:
            return self._pending.pop(pairing_id, None) is not None

    def cancel_all(self) -> int:
        """Invalidate all pending transactions, e.g. when companion service stops."""
        with self._lock:
            count = len(self._pending)
            self._pending.clear()
            return count

    def _purge_expired(self, now: float) -> None:
        expired = [key for key, item in self._pending.items()
                   if now - item.created_at >= PAIRING_TTL_SECONDS]
        for key in expired:
            del self._pending[key]
