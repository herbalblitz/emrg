"""Composition layer for local companion pairing and device management.

This service intentionally opens no network listener. Pairing management is
available only through a trusted local host UI; companion access is disabled
by default and can be enabled explicitly by the local user.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from emrg.server.companion_auth import CompanionAuthenticator, CompanionAuthenticationError
from emrg.server.companion_credentials import CredentialRegistry
from emrg.server.companion_pairing import PairingTransactions, PairingError
from emrg.server.companion_pairing_v2 import PairingV2Transactions, PairingV2Error


class CompanionHostService:
    """Compose pairing, credentials and auth primitives behind one host API."""

    def __init__(self, registry_path: Path):
        self.registry = CredentialRegistry(registry_path)
        self.pairings = PairingTransactions(self.registry)
        self._pairings_v2: PairingV2Transactions | None = None
        self.authenticator = CompanionAuthenticator(self.registry)
        self._enabled = False
        self._state_callbacks: set[Any] = set()

    @property
    def enabled(self) -> bool:
        """Whether local policy currently permits companion operations."""
        return self._enabled

    def add_state_callback(self, callback) -> None:
        """Register a lifecycle observer (transport/UI); callbacks are synchronous."""
        if not callable(callback):
            raise TypeError("callback must be callable")
        self._state_callbacks.add(callback)

    def remove_state_callback(self, callback) -> None:
        self._state_callbacks.discard(callback)

    def _notify_state(self) -> None:
        for callback in tuple(self._state_callbacks):
            try:
                callback(self._enabled)
            except Exception:
                # Observers cannot prevent local enable/disable policy changes.
                continue

    def enable(self) -> None:
        """Explicit local opt-in; does not create a listener."""
        self._enabled = True
        self._notify_state()

    def disable(self) -> int:
        """Disable immediately and invalidate all pending pairing QR transactions."""
        self._enabled = False
        self._notify_state()
        count = self.pairings.cancel_all()
        if self._pairings_v2 is not None:
            count += self._pairings_v2.cancel_all()
        return count

    def _require_enabled(self) -> None:
        if not self._enabled:
            raise PairingError("companion disabled")

    # Trusted local host UI operations
    def begin_pairing(self, *, workspace_id: str, cwd: str,
                      scopes: set[str]) -> dict[str, str | int]:
        self._require_enabled()
        return self.pairings.begin(workspace_id=workspace_id, cwd=cwd, scopes=scopes)

    def pending_pairings(self) -> list[dict[str, object]]:
        return self.pairings.pending_requests() if self._enabled else []

    def decide_pairing(self, pairing_id: str, *, approved: bool) -> bool:
        self._require_enabled()
        return self.pairings.approve(pairing_id, approved=approved)

    def cancel_pairing(self, pairing_id: str) -> bool:
        return self.pairings.cancel(pairing_id)

    def invalidate_pending_pairings(self) -> int:
        """Invalidate all QR/pairing transactions during companion shutdown or disable."""
        return self.pairings.cancel_all()

    def registered_devices(self) -> list[dict[str, Any]]:
        return self.registry.list_devices()

    def revoke_device(self, device_id: str) -> bool:
        return self.registry.revoke(device_id)


    # Pairing v2 host-local approval and transport-adapter operations.
    def begin_pairing_v2(self, *, host_identity: str, workspace_id: str,
                         cwd: str, scopes: set[str]) -> dict[str, str | int]:
        self._require_enabled()
        if self._pairings_v2 is None:
            self._pairings_v2 = PairingV2Transactions(
                self.registry, host_identity=host_identity)
        elif self._pairings_v2.host_identity != host_identity:
            raise PairingV2Error("host identity changed; restart pairing service")
        return self._pairings_v2.begin(
            workspace_id=workspace_id, cwd=cwd, scopes=scopes)

    def request_pairing_v2(self, *, pairing_id: str, device_id: str,
                           public_key: str) -> dict[str, str]:
        self._require_enabled()
        if self._pairings_v2 is None:
            raise PairingV2Error("pairing unavailable")
        return self._pairings_v2.request(
            pairing_id=pairing_id, device_id=device_id, public_key=public_key)

    def pending_pairings_v2(self) -> list[dict[str, object]]:
        if not self._enabled or self._pairings_v2 is None:
            return []
        return self._pairings_v2.pending_requests()

    def decide_pairing_v2(self, pairing_id: str, *, approved: bool) -> bool:
        self._require_enabled()
        if self._pairings_v2 is None:
            return False
        return self._pairings_v2.approve(pairing_id, approved=approved)

    def redeem_pairing_v2(self, *, pairing_id: str, device_id: str,
                          challenge_id: str, signature: str) -> str:
        self._require_enabled()
        if self._pairings_v2 is None:
            raise PairingV2Error("pairing unavailable")
        return self._pairings_v2.redeem(
            pairing_id=pairing_id, device_id=device_id,
            challenge_id=challenge_id, signature=signature)

    # Future WSS adapter operations; still transport-independent.
    def redeem_pairing(self, *, pairing_id: str, pairing_secret: str,
                       device_id: str) -> str:
        self._require_enabled()
        return self.pairings.redeem(pairing_id=pairing_id,
                                   pairing_secret=pairing_secret,
                                   device_id=device_id)

    def is_identity_current(self, identity: dict[str, Any]) -> bool:
        """Return whether an authenticated device remains approved and unchanged."""
        return self._enabled and self.registry.is_current(identity)

    def authenticate(self, first_frame: str | bytes) -> dict[str, Any]:
        if not self._enabled:
            raise CompanionAuthenticationError("authentication rejected")
        return self.authenticator.authenticate(first_frame)
