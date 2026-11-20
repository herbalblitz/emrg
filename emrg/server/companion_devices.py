"""Device list/revoke for the companion Devices screen.

A thin wrapper over :class:`emrg.server.companion_host_service.CompanionHostService`
— there is no daemon op and no gateway port to do here; the host service
already owns the credential registry. Two rules live in this wrapper:

* **No self-revocation mid-connection.** A device cannot revoke its own
  credential while its socket is open: the request is refused with an
  error and the connection is left alone (revoking it underneath the
  listener would kill the socket silently on the next frame's identity
  re-check, with no answer ever reaching the device).
* **Every revoke is audit-logged** (allowed and denied), in the same
  ``companion <area> allowed/denied: operation=... device=...`` shape the
  daemon's audit lines use, so the audit tail picks it up. Payload
  contents never reach the log — a revoke carries no payload beyond ids.
"""
from __future__ import annotations

import logging
from typing import Any

_LOG = logging.getLogger(__name__)


class CompanionDevicesError(ValueError):
    """Safe device refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def list_devices(host_service: Any) -> list[dict[str, Any]]:
    """Return the registry's safe device metadata (never salts/verifiers)."""
    return host_service.registered_devices()


def revoke_device(host_service: Any, *, device_id: str,
                  current_device_id: str) -> dict[str, Any]:
    """Revoke one device credential, unless it is the caller's own.

    Returns ``{"device_id": ..., "revoked": True}``. Self-revocation and
    unknown devices are refused with :class:`CompanionDevicesError`.
    """
    if not isinstance(device_id, str) or not device_id:
        raise CompanionDevicesError("invalid_device", "unknown device")
    if not isinstance(current_device_id, str) or not current_device_id:
        raise CompanionDevicesError("invalid_device", "unknown device")
    if device_id == current_device_id:
        _LOG.warning(
            "companion devices denied: operation=%r device=%r target=%r",
            "devices.revoke", current_device_id, device_id)
        raise CompanionDevicesError(
            "cannot_revoke_self",
            "a device cannot revoke its own credential while connected")
    revoked = host_service.revoke_device(device_id)
    if not revoked:
        raise CompanionDevicesError("device_not_found", "unknown device")
    _LOG.info(
        "companion devices allowed: operation=%r device=%r target=%r",
        "devices.revoke", current_device_id, device_id)
    return {"device_id": device_id, "revoked": True}
