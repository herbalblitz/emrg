"""Daemon-owned opt-in lifecycle for the Companion WSS listener.

The runtime is inert until a trusted local caller explicitly starts it. It
provisions a stable, protected TLS identity, binds only the exact IP literal
requested by the caller, and returns a short-lived credential-free QR
descriptor. Stopping disables host policy and closes the listener.
"""
from __future__ import annotations

import asyncio
import ipaddress
from pathlib import Path
from typing import Any

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_pairing_descriptor import create_pairing_descriptor
from emrg.server.companion_tls import CompanionTlsError
from emrg.server.companion_tls_identity import provision_host_identity
from emrg.server.companion_wss_listener import CompanionWssListener


class CompanionRuntimeError(ValueError):
    """Safe, bounded error from local Companion runtime control."""


class CompanionRuntime:
    """Own one opt-in listener and its persistent host TLS identity."""

    def __init__(self, host: CompanionHostService, identity_directory: Path):
        if not isinstance(host, CompanionHostService):
            raise TypeError("host must be a CompanionHostService")
        self._host = host
        self._identity_directory = Path(identity_directory)
        self._listener: CompanionWssListener | None = None
        self._endpoint: str | None = None
        self._lock = asyncio.Lock()

    @property
    def running(self) -> bool:
        return self._listener is not None and self._listener.running

    async def start(self, payload: object) -> dict[str, Any]:
        """Serialize the complete startup against stop and concurrent starts."""
        async with self._lock:
            return await self._start_locked(payload)

    async def _start_locked(self, payload: object) -> dict[str, Any]:
        """Start only from an exact, explicit local bind request."""
        if not isinstance(payload, dict) or set(payload) != {"bind_address", "port"}:
            raise CompanionRuntimeError("invalid listener start request")
        bind_address, port = payload["bind_address"], payload["port"]
        if not isinstance(bind_address, str):
            raise CompanionRuntimeError("invalid listener bind address")
        try:
            address = ipaddress.ip_address(bind_address.strip())
            config = CompanionListenerConfig.build(str(address), port)
            identity = provision_host_identity(self._identity_directory, [str(address)])
        except (ValueError, CompanionTlsError):
            raise CompanionRuntimeError("invalid listener configuration or TLS identity") from None

        host_text = f"[{address}]" if address.version == 6 else str(address)
        endpoint = f"wss://{host_text}:{config.port}"
        try:
            descriptor = create_pairing_descriptor(identity, endpoint)
        except CompanionTlsError:
            raise CompanionRuntimeError("could not create pairing descriptor") from None

        if self.running:
            raise CompanionRuntimeError("listener already running")
        listener = CompanionWssListener(
            self._host, config, identity_directory=self._identity_directory,
        )
        self._host.enable()
        try:
            await listener.start()
        except Exception:
            self._host.disable()
            await listener.close()
            raise CompanionRuntimeError("listener could not be started") from None
        self._listener = listener
        self._endpoint = endpoint
        return {
            "running": True,
            "endpoint": endpoint,
            "fingerprint_sha256": identity.fingerprint_sha256,
            "descriptor": descriptor,
            "expires_at": int(identity.expires_at.timestamp()),
        }

    async def stop(self) -> dict[str, Any]:
        """Disable pairing and close all listener sockets."""
        async with self._lock:
            listener, self._listener = self._listener, None
            self._endpoint = None
            self._host.disable()
            if listener is not None:
                await listener.close()
        return {"running": False}

    async def close(self) -> None:
        """Daemon shutdown hook; safe when never started or already stopped."""
        await self.stop()
