"""Create short-lived, credential-free QR descriptors for Companion pairing.

The descriptor contains endpoint metadata and a TLS fingerprint only. Device
credentials are issued after local host approval and never travel in the QR.
"""
from __future__ import annotations

import ipaddress
import json
import time
from urllib.parse import urlsplit

from emrg.server.companion_tls import CompanionTlsError, normalize_fingerprint
from emrg.server.companion_tls_identity import HostTlsIdentity

DESCRIPTOR_VERSION = 1
MAX_DESCRIPTOR_TTL_SECONDS = 180


def _normalize_endpoint_host(endpoint: str) -> tuple[str, str]:
    if not isinstance(endpoint, str) or not endpoint:
        raise CompanionTlsError("invalid pairing endpoint")
    try:
        parsed = urlsplit(endpoint)
        if (parsed.scheme.lower() != "wss" or not parsed.hostname or
                parsed.username is not None or parsed.password is not None or
                parsed.query or parsed.fragment or parsed.path not in ("", "/")):
            raise CompanionTlsError("invalid pairing endpoint")
        port = parsed.port
        if port is not None and not 1 <= port <= 65535:
            raise CompanionTlsError("invalid pairing endpoint")
        host = parsed.hostname.rstrip(".")
        try:
            normalized_host = str(ipaddress.ip_address(host))
        except ValueError:
            normalized_host = host.encode("idna").decode("ascii").lower()
        normalized_endpoint = endpoint.rstrip("/")
        return normalized_host, normalized_endpoint
    except (ValueError, UnicodeError):
        raise CompanionTlsError("invalid pairing endpoint") from None


def create_pairing_descriptor(identity: HostTlsIdentity, endpoint: str, *,
                              ttl_seconds: int = MAX_DESCRIPTOR_TTL_SECONDS,
                              now_epoch_seconds: int | None = None) -> str:
    """Build Android-compatible v1 QR JSON for a provisioned TLS identity."""
    if isinstance(ttl_seconds, bool) or not isinstance(ttl_seconds, int):
        raise CompanionTlsError("invalid pairing descriptor lifetime")
    if not 1 <= ttl_seconds <= MAX_DESCRIPTOR_TTL_SECONDS:
        raise CompanionTlsError("invalid pairing descriptor lifetime")
    host, normalized_endpoint = _normalize_endpoint_host(endpoint)
    if host not in identity.san_hosts:
        raise CompanionTlsError("endpoint host is not covered by host TLS identity")
    fingerprint = normalize_fingerprint(identity.fingerprint_sha256)
    formatted_fingerprint = ":".join(
        f"{byte:02X}" for byte in bytes.fromhex(fingerprint)
    )
    now = int(time.time()) if now_epoch_seconds is None else now_epoch_seconds
    if isinstance(now, bool) or not isinstance(now, int):
        raise CompanionTlsError("invalid pairing descriptor time")
    descriptor = {
        "version": DESCRIPTOR_VERSION,
        "endpoint": normalized_endpoint,
        "fingerprint_sha256": formatted_fingerprint,
        "expires_at": now + ttl_seconds,
    }
    return json.dumps(descriptor, separators=(",", ":"), ensure_ascii=True)
