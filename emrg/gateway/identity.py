"""Pairing codes, device records and bearer tokens.

The gateway never stores a device token in the clear. A pairing attempt returns
the token exactly once; the host keeps ``sha256(token)``. A stolen
``devices.json`` therefore contains no usable credential, the same property the
daemon's own token file gets from being owner-only.

Pairing is deliberately *not* a long-lived secret: an 8-digit code is only
strong enough because it is one-shot, expires in five minutes, is rate-limited
per source address by the server, and is destroyed after five wrong guesses.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable

DEFAULT_SCOPES = ("chat", "files", "exec", "admin")
ALL_SCOPES = frozenset(DEFAULT_SCOPES)

PAIRING_TTL_SECONDS = 300.0
PAIRING_MAX_ATTEMPTS = 5
DEVICE_TOKEN_PREFIX = "emrgd_"


class PairingError(Exception):
    """A refused pairing attempt, with a stable machine-readable code."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Device:
    id: str
    name: str
    token_hash: str
    created: float
    last_seen: float
    scopes: list[str] = field(default_factory=lambda: list(DEFAULT_SCOPES))
    app_version: str = ""

    def public(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "created": _iso(self.created),
            "last_seen": _iso(self.last_seen),
            "scopes": list(self.scopes),
            "app_version": self.app_version,
        }


class DeviceStore:
    """The paired-device registry and the one live pairing code."""

    def __init__(
        self,
        devices_path: Path,
        pairing_path: Path,
        *,
        max_devices: int = 8,
        clock=time.time,
    ) -> None:
        self.devices_path = devices_path
        self.pairing_path = pairing_path
        self.max_devices = max_devices
        self._clock = clock
        self._lock = threading.RLock()

    # ── device registry ───────────────────────────────────────────────

    def _read_devices(self) -> list[Device]:
        try:
            raw = json.loads(self.devices_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        if not isinstance(raw, dict) or not isinstance(raw.get("devices"), list):
            return []
        out: list[Device] = []
        for item in raw["devices"]:
            if not isinstance(item, dict):
                continue
            try:
                device = Device(
                    id=str(item["id"]),
                    name=str(item["name"]),
                    token_hash=str(item["token_hash"]),
                    created=float(item["created"]),
                    last_seen=float(item["last_seen"]),
                    scopes=[str(s) for s in item.get("scopes", DEFAULT_SCOPES)],
                    app_version=str(item.get("app_version", "")),
                )
            except (KeyError, TypeError, ValueError):
                continue
            out.append(device)
        return out

    def _write_devices(self, devices: list[Device]) -> None:
        payload = {
            "version": 1,
            "devices": [asdict(d) for d in devices],
        }
        _atomic_json(self.devices_path, payload)

    def devices(self) -> list[Device]:
        with self._lock:
            return self._read_devices()

    def public_devices(self) -> list[dict]:
        return [d.public() for d in self.devices()]

    def authenticate(self, token: str) -> Device | None:
        """Return the device this bearer token belongs to, or ``None``.

        The comparison is over every stored hash with :func:`hmac.compare_digest`
        so a wrong token costs the same whether it resembles a real one or not.
        ``last_seen`` is written at most once a minute: a frame-by-frame write
        would turn a busy phone into a disk writer.
        """
        if not isinstance(token, str) or not token:
            return None
        digest = _token_hash(token)
        now = self._clock()
        with self._lock:
            devices = self._read_devices()
            match: Device | None = None
            for device in devices:
                if hmac.compare_digest(device.token_hash, digest):
                    match = device
            if match is None:
                return None
            if now - match.last_seen >= 60.0:
                match.last_seen = now
                self._write_devices(devices)
            return match

    def touch(self, device_id: str) -> None:
        with self._lock:
            devices = self._read_devices()
            changed = False
            for device in devices:
                if device.id == device_id:
                    device.last_seen = self._clock()
                    changed = True
                    break
            if changed:
                self._write_devices(devices)

    def revoke(self, device_id: str) -> bool:
        with self._lock:
            devices = self._read_devices()
            kept = [d for d in devices if d.id != device_id]
            if len(kept) == len(devices):
                return False
            self._write_devices(kept)
            return True

    # ── pairing ──────────────────────────────────────────────────────

    def issue_pairing_code(self, ttl_seconds: float = PAIRING_TTL_SECONDS) -> dict:
        """Mint a one-shot code, replacing any previous one.

        Returning the code to the caller (the CLI) is the only time it exists
        in the clear; the file holds a salted hash.
        """
        code = f"{secrets.randbelow(100_000_000):08d}"
        salt = secrets.token_hex(16)
        now = self._clock()
        payload = {
            "version": 1,
            "salt": salt,
            "code_hash": _code_hash(code, salt),
            "created": now,
            "expires_at": now + max(30.0, float(ttl_seconds)),
            "attempts": 0,
            "max_attempts": PAIRING_MAX_ATTEMPTS,
        }
        with self._lock:
            _atomic_json(self.pairing_path, payload)
        return {
            "code": code,
            "expires_at": payload["expires_at"],
            "ttl_seconds": int(payload["expires_at"] - now),
        }

    def pairing_code_state(self) -> dict | None:
        with self._lock:
            return self._read_pairing()

    def _read_pairing(self) -> dict | None:
        try:
            raw = json.loads(self.pairing_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return raw if isinstance(raw, dict) else None

    def _write_pairing(self, payload: dict) -> None:
        _atomic_json(self.pairing_path, payload)

    def pair(
        self,
        code: str,
        device_name: str,
        *,
        scopes: Iterable[str] | None = None,
        app_version: str = "",
    ) -> tuple[Device, str]:
        """Consume ``code`` and create a device, returning its one-time token."""
        name = _clean_name(device_name)
        now = self._clock()
        with self._lock:
            payload = self._read_pairing()
            if not payload:
                raise PairingError("no_pairing_code", "No pairing code is active. Run 'emrg-gateway pair' on the host.")
            try:
                expires_at = float(payload.get("expires_at", 0))
            except (TypeError, ValueError):
                expires_at = 0.0
            if now >= expires_at:
                self._clear_pairing()
                raise PairingError("pairing_expired", "The pairing code expired. Generate a new one on the host.")
            attempts = int(payload.get("attempts", 0) or 0)
            if attempts >= int(payload.get("max_attempts", PAIRING_MAX_ATTEMPTS) or PAIRING_MAX_ATTEMPTS):
                self._clear_pairing()
                raise PairingError("pairing_locked", "Too many wrong codes. Generate a new one on the host.")
            salt = str(payload.get("salt", ""))
            expected = str(payload.get("code_hash", ""))
            if not hmac.compare_digest(expected, _code_hash(str(code), salt)):
                payload["attempts"] = attempts + 1
                if payload["attempts"] >= int(payload.get("max_attempts", PAIRING_MAX_ATTEMPTS) or PAIRING_MAX_ATTEMPTS):
                    self._clear_pairing()
                    raise PairingError("pairing_locked", "Too many wrong codes. Generate a new one on the host.")
                self._write_pairing(payload)
                left = int(payload.get("max_attempts", PAIRING_MAX_ATTEMPTS) or PAIRING_MAX_ATTEMPTS) - payload["attempts"]
                raise PairingError("pairing_rejected", f"Wrong pairing code ({left} attempt(s) left).")

            devices = self._read_devices()
            if len(devices) >= self.max_devices:
                raise PairingError(
                    "device_limit",
                    f"This host already has {len(devices)} paired device(s). Revoke one before pairing another.",
                )

            chosen = _clean_scopes(scopes)
            token = DEVICE_TOKEN_PREFIX + secrets.token_urlsafe(32)
            device = Device(
                id=uuid.uuid4().hex[:16],
                name=name,
                token_hash=_token_hash(token),
                created=now,
                last_seen=now,
                scopes=chosen,
                app_version=str(app_version)[:120],
            )
            devices.append(device)
            self._write_devices(devices)
            self._clear_pairing()
            return device, token

    def _clear_pairing(self) -> None:
        try:
            self.pairing_path.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            pass


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _code_hash(code: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{code}".encode("utf-8")).hexdigest()


def _clean_name(value: str) -> str:
    name = " ".join(str(value or "").split())
    if not name:
        name = "Android device"
    if len(name) > 64:
        name = name[:64]
    name = "".join(ch for ch in name if ch.isprintable())
    return name or "Android device"


def _clean_scopes(scopes: Iterable[str] | None) -> list[str]:
    if scopes is None:
        return list(DEFAULT_SCOPES)
    chosen = []
    for scope in scopes:
        value = str(scope).strip().lower()
        if value in ALL_SCOPES and value not in chosen:
            chosen.append(value)
    return chosen or ["chat"]


def _atomic_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{secrets.token_hex(4)}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def _iso(value: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(value))
