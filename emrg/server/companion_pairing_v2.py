"""Host-approved v2 companion pairing bound to a P-256 device key.

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
from dataclasses import dataclass, field
from typing import Callable

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_credentials import CredentialRegistry

PAIRING_V2_TTL_SECONDS = 180
PAIRING_V2_ID_BYTES = 32
P256_PUBLIC_KEY_MAX_BYTES = 256
P256_SIGNATURE_MIN_BYTES = 64
P256_SIGNATURE_MAX_BYTES = 80
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


def _decode_bounded(value: object, max_bytes: int, error: str) -> bytes:
    if not isinstance(value, str) or not value or len(value) > (max_bytes * 8 + 5) // 6:
        raise PairingV2Error(error)
    if not _BASE64URL_RE.fullmatch(value):
        raise PairingV2Error(error)
    try:
        raw = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, TypeError):
        raise PairingV2Error(error) from None
    if not raw or len(raw) > max_bytes or _encode(raw) != value:
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
    requested_scopes: frozenset[str] | None = None
    client_nonce: str | None = None
    # Keep the issued bearer token only in bounded, short-lived transaction memory.
    credential_token: str | None = field(default=None, repr=False)


class PairingV2Transactions:
    """One-time pairing transactions whose host approval is device-key-bound."""

    def __init__(self, registry: CredentialRegistry, *, host_identity: str,
                 clock: Callable[[], float] = time.monotonic):
        self._registry = registry
        self._host_identity = _text(host_identity, 128, "invalid host identity")
        self._clock = clock
        self._lock = threading.Lock()
        self._pending: dict[str, _PendingV2] = {}
        self._seen_client_nonces: dict[str, float] = {}

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

    def request(self, *, pairing_id: str, device_id: str, public_key: str,
                requested_scopes: list[str] | tuple[str, ...], client_nonce: str) -> dict[str, str]:
        _pairing_id(pairing_id)
        _device_id(device_id)
        if (not isinstance(requested_scopes, (list, tuple)) or not 1 <= len(requested_scopes) <= 3
                or any(not isinstance(scope, str) for scope in requested_scopes)
                or len(set(requested_scopes)) != len(requested_scopes)):
            raise PairingV2Error("invalid requested scopes")
        requested = frozenset(requested_scopes)
        if not requested <= {"status", "sessions.list", "chat.send"}:
            raise PairingV2Error("invalid requested scopes")
        _decode_bounded(client_nonce, 64, "invalid client nonce")
        if len(_decode_bounded(client_nonce, 64, "invalid client nonce")) < 16:
            raise PairingV2Error("invalid client nonce")
        raw_key = _decode_bounded(public_key, P256_PUBLIC_KEY_MAX_BYTES, "invalid device key")
        try:
            parsed_key = serialization.load_der_public_key(raw_key)
        except (ValueError, TypeError):
            raise PairingV2Error("invalid device key") from None
        if not isinstance(parsed_key, ec.EllipticCurvePublicKey) or not isinstance(parsed_key.curve, ec.SECP256R1):
            raise PairingV2Error("invalid device key")
        now = self._clock()
        with self._lock:
            self._purge(now)
            item = self._pending.get(pairing_id)
            if item is None or item.approved is not None:
                raise PairingV2Error("pairing unavailable")
            if item.device_id is not None:
                raise PairingV2Error("pairing request already received")
            self._purge_client_nonces(now)
            if client_nonce in self._seen_client_nonces:
                raise PairingV2Error("client nonce replay")
            if not requested <= item.scopes:
                raise PairingV2Error("requested scopes exceed host grant")
            item.device_id = device_id
            item.public_key = raw_key
            item.requested_scopes = requested
            item.client_nonce = client_nonce
            self._seen_client_nonces[client_nonce] = now
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
            "client_nonce": item.client_nonce,
            "device_id": item.device_id,
            "public_key_sha256": hashlib.sha256(item.public_key).hexdigest(),
            "host_identity": self._host_identity,
            "workspace_id": item.workspace_id,
            "scopes": sorted(item.requested_scopes or item.scopes),
            "expires_at": item.expires_at,
        }
        return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

    def pending_requests(self) -> list[dict[str, object]]:
        now = self._clock()
        with self._lock:
            self._purge(now)
            result = []
            for pairing_id, item in self._pending.items():
                if (item.device_id is None or item.public_key is None
                        or item.credential_token is not None):
                    continue
                result.append({
                    "pairing_id": pairing_id,
                    "device_id": item.device_id,
                    "public_key_sha256": hashlib.sha256(item.public_key).hexdigest(),
                    "workspace_id": item.workspace_id,
                    "cwd": item.cwd,
                    "scopes": sorted(item.requested_scopes or item.scopes),
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
        raw_signature = _decode_bounded(signature, P256_SIGNATURE_MAX_BYTES, "invalid proof")
        if len(raw_signature) < P256_SIGNATURE_MIN_BYTES:
            raise PairingV2Error("invalid proof")
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
                public_key = serialization.load_der_public_key(item.public_key)
                if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
                    raise ValueError("unsupported device key")
                public_key.verify(raw_signature, payload, ec.ECDSA(hashes.SHA256()))
            except (InvalidSignature, ValueError):
                raise PairingV2Error("device proof rejected") from None

            # A lost WSS response may cause the device to redeem again. Return
            # the exact same token for this exact signed transaction; never
            # issue a replacement token or rotate the device credential.
            if item.credential_token is not None:
                return item.credential_token

            # Keep the transaction lock across issuance so concurrent retries
            # cannot create multiple credentials. If issuance raises, the
            # approved transaction remains retryable with the same proof.
            token = self._registry.issue(
                device_id=device_id, workspace_id=item.workspace_id,
                cwd=item.cwd, scopes=set(item.requested_scopes or item.scopes))
            item.credential_token = token
            return token

    def cancel(self, pairing_id: str) -> bool:
        try:
            _pairing_id(pairing_id)
        except PairingV2Error:
            return False
        with self._lock:
            item = self._pending.get(pairing_id)
            if item is None or item.credential_token is not None:
                return False
            del self._pending[pairing_id]
            return True

    def cancel_all(self) -> int:
        with self._lock:
            # Count only transactions still awaiting completion. Redeemed
            # entries are short-lived response-retry cache records, not
            # pending approvals, but are cleared on host shutdown as well.
            count = sum(item.credential_token is None for item in self._pending.values())
            self._pending.clear()
            return count

    def _purge_client_nonces(self, now: float) -> None:
        expired = [
            nonce for nonce, created_at in self._seen_client_nonces.items()
            if now - created_at >= PAIRING_V2_TTL_SECONDS
        ]
        for nonce in expired:
            del self._seen_client_nonces[nonce]
        # Never evict an unexpired nonce: doing so would reopen a replay window.
        # Fail closed under pressure until entries expire naturally.
        if len(self._seen_client_nonces) >= 4096:
            raise PairingV2Error("client nonce replay cache is full")

    def _purge(self, now: float) -> None:
        expired = [key for key, item in self._pending.items()
                   if now - item.created_at >= PAIRING_V2_TTL_SECONDS]
        for key in expired:
            del self._pending[key]
