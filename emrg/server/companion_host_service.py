"""Composition layer for local companion pairing and device management.

This service intentionally opens no network listener. Its pairing-management
methods are for a trusted local host UI only; the future WSS adapter may call
redeem/authenticate, but must not expose begin/approve/reject/pending to remote
clients.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from emrg.server.companion_auth import CompanionAuthenticator
from emrg.server.companion_credentials import CredentialRegistry
from emrg.server.companion_pairing import PairingTransactions


class CompanionHostService:
    """Compose pairing, credentials and auth primitives behind one host API."""

    def __init__(self, registry_path: Path):
        self.registry = CredentialRegistry(registry_path)
        self.pairings = PairingTransactions(self.registry)
        self.authenticator = CompanionAuthenticator(self.registry)

    # Trusted local host UI operations
    def begin_pairing(self, *, workspace_id: str, cwd: str,
                      scopes: set[str]) -> dict[str, str | int]:
        return self.pairings.begin(workspace_id=workspace_id, cwd=cwd, scopes=scopes)

    def pending_pairings(self) -> list[dict[str, object]]:
        return self.pairings.pending_requests()

    def decide_pairing(self, pairing_id: str, *, approved: bool) -> bool:
        return self.pairings.approve(pairing_id, approved=approved)

    def cancel_pairing(self, pairing_id: str) -> bool:
        return self.pairings.cancel(pairing_id)

    def registered_devices(self) -> list[dict[str, Any]]:
        return self.registry.list_devices()

    def revoke_device(self, device_id: str) -> bool:
        return self.registry.revoke(device_id)

    # Future WSS adapter operations; still transport-independent.
    def redeem_pairing(self, *, pairing_id: str, pairing_secret: str,
                       device_id: str) -> str:
        return self.pairings.redeem(pairing_id=pairing_id,
                                   pairing_secret=pairing_secret,
                                   device_id=device_id)

    def authenticate(self, first_frame: str | bytes) -> dict[str, Any]:
        return self.authenticator.authenticate(first_frame)
