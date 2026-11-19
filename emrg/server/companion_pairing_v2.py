"""Host-approved v2 companion pairing bound to an Ed25519 device key.

This module is transport-independent. A WSS adapter must expose request/redeem
only over the pinned TLS connection and expose approve/reject only to trusted
local host UI. No device credential is included in a QR descriptor.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import secrets
import threading
import time
import unicodedata
from dataclasses import dataclass
from typing import Callable

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from emrg.server.companion_credentials import CredentialRegistry

PAIRING_V2_TTL_SECONDS = 180
PAIRING_V2_ID_BYTES = 32
ED25519_PUBLIC_KEY_BYTES = 32
ED25519_SIGNATURE_BYTES = 64
_BASE64URL_RE = re.compile(r"^[A-Za-z0-9_-]+$")
_DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class PairingV2Error(ValueError):
    """Safe pairing v2 rejection without secret material."""


def _text(value: object, limit: int, error: str) -> str:
    if not isinstance(value, str) or not value:
        raise PairingV2Error(error)
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        raise PairingV2Error(error) from None
    if size > limit or any(
        unicodedata.category(char) in {"Cc", "Cf"} for char in value
    ):
        # Control and formatting characters can spoof local approval displays
        # (for example, embedded newlines or bidirectional text overrides).
        raise PairingV2Error(error)
    return value


def _decode(value: object, expected: int, error: str) -> bytes:
    encoded_length = (expected * 8 + 5) // 6
    if (not isinstance(value, str) or len(value) != encoded_length
            or not _BASE64URL_RE.fullmatch(value)):
        raise PairingV2Error(error)
    try:
        raw = base64.b64decode(
            value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, TypeError):
        raise PairingV2Error(error) from None
    if len(raw) != expected or _encode(raw) != value:
        raise PairingV2Error(error)
    return raw


def _device_id(value: object) -> str:
    if not isinstance(value, str) or not _DEVICE_ID_RE.fullmatch(value):
        raise PairingV2Error("invalid device")
    return value


def _pairing_id(value: object) -> str:
    _decode(value, PAIRING_V2_ID_BYTES, "pairing unavailable")
    return value


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


@dataclass
class _PendingV2:
    created_at: float
    workspace_id: str
    cwd: str
    scopes: frozenset[str]
    expires_at: int
    device_id: str | None = None
    public_key: bytes | None = None
    challenge: bytes | None = None
    challenge_id: str | None = None
    approved: bool | None = None


class PairingV2Transactions:
    """One-time pairing transactions whose host approval is device-key-bound."""

    def __init__(self, registry: CredentialRegistry, *, host_identity: str,
                 clock: Callable[[], float] = time.monotonic):
        self._registry = registry
        self._host_identity = _text(host_identity, 128, "invalid host identity")
        self._clock = clock
        self._lock = threading.Lock()
        self._pending: dict[str, _PendingV2] = {}

    @property
    def host_identity(self) -> str:
        return self._host_identity

    def begin(self, *, workspace_id: str, cwd: str, scopes: set[str]) -> dict[str, str | int]:
        _text(workspace_id, 128, "invalid pairing request")
        _text(cwd, 4096, "invalid pairing request")
        allowed = {"status", "sessions.list", "chat.send"}
        if not isinstance(scopes, (set, frozenset)) or not scopes or not scopes <= allowed:
            raise PairingV2Error("invalid pairing request")
        now = self._clock()
        pairing_id = _encode(secrets.token_bytes(PAIRING_V2_ID_BYTES))
        with self._lock:
            self._purge(now)
            if len(self._pending) >= 32:
                raise PairingV2Error("pairing capacity reached")
            self._pending[pairing_id] = _PendingV2(
                now, workspace_id, cwd, frozenset(scopes),
                int(time.time()) + PAIRING_V2_TTL_SECONDS)
        return {"pairing_id": pairing_id, "expires_in": PAIRING_V2_TTL_SECONDS}

    def request(self, *, pairing_id: str, device_id: str,
                public_key: str) -> dict[str, str]:
        _pairing_id(pairing_id)
        _device_id(device_id)
        raw_key = _decode(public_key, ED25519_PUBLIC_KEY_BYTES, "invalid device key")
        now = self._clock()
        with self._lock:
            self._purge(now)
            item = self._pending.get(pairing_id)
            if item is None or item.approved is not None:
                raise PairingV2Error("pairing unavailable")
            if item.device_id is not None:
                raise PairingV2Error("pairing request already received")
            item.device_id = device_id
            item.public_key = raw_key
            item.challenge = secrets.token_bytes(32)
            challenge_id = _encode(secrets.token_bytes(16))
            # challenge_id is carried inside the signed canonical payload.
            item.challenge_id = challenge_id
            payload = self._challenge_payload(pairing_id, item)
            return {"challenge_id": challenge_id, "challenge": _encode(payload)}

    def _challenge_payload(self, pairing_id: str, item: _PendingV2) -> bytes:
        assert item.device_id is not None and item.public_key is not None
        assert item.challenge is not None and item.challenge_id is not None
        value = {
            "protocol": "2.0",
            "pairing_id": pairing_id,
            "challenge_id": item.challenge_id,
            "nonce": _encode(item.challenge),
            "device_id": item.device_id,
            "public_key_sha256": hashlib.sha256(item.public_key).hexdigest(),
            "host_identity": self._host_identity,
            "workspace_id": item.workspace_id,
            "scopes": sorted(item.scopes),
            "expires_at": item.expires_at,
        }
        return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

    def pending_requests(self) -> list[dict[str, object]]:
        now = self._clock()
        with self._lock:
            self._purge(now)
            result = []
            for pairing_id, item in self._pending.items():
                if item.device_id is None or item.public_key is None:
                    continue
                result.append({
                    "pairing_id": pairing_id,
                    "device_id": item.device_id,
                    "public_key_sha256": hashlib.sha256(item.public_key).hexdigest(),
                    "workspace_id": item.workspace_id,
                    "cwd": item.cwd,
                    "scopes": sorted(item.scopes),
                    "approved": item.approved,
                    "expires_in": max(0, int(PAIRING_V2_TTL_SECONDS - (now - item.created_at))),
                })
            return result

    def approve(self, pairing_id: str, *, approved: bool) -> bool:
        try:
            _pairing_id(pairing_id)
        except PairingV2Error:
            return False
        if not isinstance(approved, bool):
            return False
        with self._lock:
            self._purge(self._clock())
            item = self._pending.get(pairing_id)
            if item is None or item.device_id is None or item.public_key is None or item.approved is not None:
                return False
            item.approved = approved
            if not approved:
                del self._pending[pairing_id]
            return True

    def redeem(self, *, pairing_id: str, device_id: str,
               challenge_id: str, signature: str) -> str:
        _pairing_id(pairing_id)
        _device_id(device_id)
        _decode(challenge_id, 16, "invalid challenge")
        raw_signature = _decode(signature, ED25519_SIGNATURE_BYTES, "invalid proof")
        with self._lock:
            self._purge(self._clock())
            item = self._pending.get(pairing_id)
            if item is None:
                raise PairingV2Error("pairing unavailable")
            if item.approved is not True:
                raise PairingV2Error("pairing not approved")
            if (item.device_id != device_id or item.challenge_id != challenge_id
                    or item.public_key is None):
                raise PairingV2Error("device proof rejected")
            payload = self._challenge_payload(pairing_id, item)
            try:
                Ed25519PublicKey.from_public_bytes(item.public_key).verify(raw_signature, payload)
            except (InvalidSignature, ValueError):
                raise PairingV2Error("device proof rejected") from None
            del self._pending[pairing_id]
            workspace_id, cwd, scopes = item.workspace_id, item.cwd, set(item.scopes)
        return self._registry.issue(device_id=device_id, workspace_id=workspace_id,
                                    cwd=cwd, scopes=scopes)

    def cancel(self, pairing_id: str) -> bool:
        try:
            _pairing_id(pairing_id)
        except PairingV2Error:
            return False
        with self._lock:
            return self._pending.pop(pairing_id, None) is not None

    def cancel_all(self) -> int:
        with self._lock:
            count = len(self._pending)
            self._pending.clear()
            return count

    def _purge(self, now: float) -> None:
        expired = [key for key, item in self._pending.items()
                   if now - item.created_at >= PAIRING_V2_TTL_SECONDS]
        for key in expired:
            del self._pending[key]
