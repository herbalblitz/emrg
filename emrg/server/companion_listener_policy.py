"""Explicit, fail-closed bind policy for a future Companion WSS listener.

This module validates configuration only. It deliberately performs no DNS
resolution, socket creation, firewall changes, or listener startup.
"""
from __future__ import annotations

import ipaddress
from dataclasses import dataclass

from emrg.server.companion_tls import CompanionTlsError


DEFAULT_MAX_CONNECTIONS = 8
DEFAULT_HANDSHAKE_TIMEOUT_SECONDS = 10
DEFAULT_IDLE_TIMEOUT_SECONDS = 60
DEFAULT_CLOSE_TIMEOUT_SECONDS = 5
MIN_PORT = 1024
MAX_PORT = 65535


@dataclass(frozen=True)
class CompanionListenerConfig:
    """Validated explicit IP/port and resource bounds for the dedicated listener."""

    bind_address: str
    port: int
    max_connections: int = DEFAULT_MAX_CONNECTIONS
    handshake_timeout_seconds: float = DEFAULT_HANDSHAKE_TIMEOUT_SECONDS
    idle_timeout_seconds: float = DEFAULT_IDLE_TIMEOUT_SECONDS
    close_timeout_seconds: float = DEFAULT_CLOSE_TIMEOUT_SECONDS

    @classmethod
    def build(cls, bind_address: str, port: int, *,
              max_connections: int = DEFAULT_MAX_CONNECTIONS,
              handshake_timeout_seconds: float = DEFAULT_HANDSHAKE_TIMEOUT_SECONDS,
              idle_timeout_seconds: float = DEFAULT_IDLE_TIMEOUT_SECONDS,
              close_timeout_seconds: float = DEFAULT_CLOSE_TIMEOUT_SECONDS) -> "CompanionListenerConfig":
        address = normalize_bind_address(bind_address)
        if isinstance(port, bool) or not isinstance(port, int) or not MIN_PORT <= port <= MAX_PORT:
            raise CompanionTlsError("invalid companion listener port")
        if isinstance(max_connections, bool) or not isinstance(max_connections, int) or not 1 <= max_connections <= 64:
            raise CompanionTlsError("invalid companion connection limit")
        _bounded_timeout(handshake_timeout_seconds, 1, 30, "handshake")
        _bounded_timeout(idle_timeout_seconds, 5, 300, "idle")
        _bounded_timeout(close_timeout_seconds, 1, 15, "close")
        return cls(address, port, max_connections, float(handshake_timeout_seconds),
                   float(idle_timeout_seconds), float(close_timeout_seconds))


def normalize_bind_address(value: str) -> str:
    """Accept only explicit unicast IP literals; wildcard binds are forbidden."""
    if not isinstance(value, str) or not value.strip():
        raise CompanionTlsError("explicit companion bind address required")
    candidate = value.strip()
    try:
        address = ipaddress.ip_address(candidate)
    except ValueError:
        raise CompanionTlsError("companion bind address must be an IP literal") from None
    if address.is_unspecified or address.is_multicast or address.is_reserved:
        raise CompanionTlsError("unsafe companion bind address")
    return str(address)


def _bounded_timeout(value: float, minimum: float, maximum: float, label: str) -> None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CompanionTlsError(f"invalid companion {label} timeout")
    if not minimum <= float(value) <= maximum:
        raise CompanionTlsError(f"invalid companion {label} timeout")
