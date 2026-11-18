"""Credential authentication for the companion protocol.

This is a transport-independent gate. The future WSS handler must call it after
TLS identity verification and before marking a connection authenticated.
"""
from __future__ import annotations

from typing import Any

from emrg.server.companion_credentials import CredentialRegistry, CredentialRegistryError
from emrg.server.companion_protocol import CompanionProtocolError, validate_client_frame


class CompanionAuthenticationError(ValueError):
    """Safe authentication rejection; does not expose token or device data."""


class CompanionAuthenticator:
    def __init__(self, registry: CredentialRegistry):
        self._registry = registry

    def authenticate(self, first_frame: str | bytes) -> dict[str, Any]:
        """Validate the auth envelope and return only authorized device metadata."""
        try:
            frame = validate_client_frame(first_frame, authenticated=False)
            record = self._registry.verify(
                device_id=frame["device_id"], credential=frame["credential"])
        except (CompanionProtocolError, CredentialRegistryError):
            raise CompanionAuthenticationError("authentication rejected") from None
        if record is None:
            raise CompanionAuthenticationError("authentication rejected")
        # The verifier's returned metadata is the authoritative policy binding.
        # Never return token, salt, or verifier fields from the registry.
        return {
            "device_id": record["device_id"],
            "workspace_id": record["workspace_id"],
            "cwd": record["cwd"],
            "scopes": record["scopes"],
        }
