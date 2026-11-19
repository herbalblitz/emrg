"""Credential authentication for the companion protocol.

This is a transport-independent gate. The future WSS handler must call it after
TLS identity verification and before marking a connection authenticated.
"""
from __future__ import annotations

import threading
import time
from typing import Any, Callable

from emrg.server.companion_credentials import CredentialRegistry, CredentialRegistryError
from emrg.server.companion_protocol import CompanionProtocolError, validate_client_frame


class CompanionAuthenticationError(ValueError):
    """Safe authentication rejection; does not expose token or device data."""


class CompanionAuthenticator:
    """Authenticate credentials and enforce a bounded per-device nonce replay cache."""

    NONCE_TTL_SECONDS = 600
    MAX_NONCE_ENTRIES = 4096

    def __init__(self, registry: CredentialRegistry, *,
                 nonce_ttl_seconds: float = NONCE_TTL_SECONDS,
                 max_nonce_entries: int = MAX_NONCE_ENTRIES,
                 clock: Callable[[], float] = time.monotonic):
        if nonce_ttl_seconds <= 0 or max_nonce_entries <= 0:
            raise ValueError("nonce cache bounds must be positive")
        self._registry = registry
        self._nonce_ttl = float(nonce_ttl_seconds)
        self._max_nonce_entries = int(max_nonce_entries)
        self._clock = clock
        self._nonce_lock = threading.Lock()
        self._used_nonces: dict[tuple[str, str], float] = {}

    def _accept_nonce(self, device_id: str, nonce: str) -> bool:
        now = self._clock()
        key = (device_id, nonce)
        with self._nonce_lock:
            expired = [entry for entry, expiry in self._used_nonces.items() if expiry <= now]
            for entry in expired:
                self._used_nonces.pop(entry, None)
            if key in self._used_nonces:
                return False
            # Fail closed rather than evicting an unexpired nonce and weakening replay checks.
            if len(self._used_nonces) >= self._max_nonce_entries:
                return False
            self._used_nonces[key] = now + self._nonce_ttl
            return True

    def authenticate(self, first_frame: str | bytes) -> dict[str, Any]:
        """Validate the auth envelope, verify credential, and consume its nonce."""
        try:
            frame = validate_client_frame(first_frame, authenticated=False)
            record = self._registry.verify(
                device_id=frame["device_id"], credential=frame["credential"])
        except (CompanionProtocolError, CredentialRegistryError):
            raise CompanionAuthenticationError("authentication rejected") from None
        if record is None:
            raise CompanionAuthenticationError("authentication rejected")
        if not self._accept_nonce(frame["device_id"], frame["client_nonce"]):
            raise CompanionAuthenticationError("authentication rejected")
        # The verifier's returned metadata is the authoritative policy binding.
        # Never return token, salt, or verifier fields from the registry.
        return {
            "device_id": record["device_id"],
            "workspace_id": record["workspace_id"],
            "cwd": record["cwd"],
            "scopes": record["scopes"],
            "generation": record["generation"],
        }
