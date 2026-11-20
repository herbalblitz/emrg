"""Create short-lived, credential-free QR descriptors for Companion pairing.

The descriptor contains endpoint metadata and a TLS fingerprint only. Device
credentials are issued after local host approval and never travel in the QR.
"""
from __future__ import annotations

import base64
import ipaddress
import json
import re
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
        authority_host = f"[{normalized_host}]" if ":" in normalized_host else normalized_host
        authority = f"{authority_host}:{port}" if port is not None else authority_host
        normalized_endpoint = f"wss://{authority}"
        return normalized_host, normalized_endpoint
    except (ValueError, UnicodeError):
        raise CompanionTlsError("invalid pairing endpoint") from None


def create_pairing_descriptor(identity: HostTlsIdentity, endpoint: str, *,
                              ttl_seconds: int = MAX_DESCRIPTOR_TTL_SECONDS,
                              now_epoch_seconds: int | None = None,
                              pairing_id: str | None = None) -> str:
    """Build a credential-free v1 descriptor or transaction-bound v2 QR JSON."""
    if pairing_id is not None:
        if not isinstance(pairing_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", pairing_id):
            raise CompanionTlsError("invalid pairing descriptor transaction")
        try:
            decoded_pairing_id = base64.urlsafe_b64decode(
                pairing_id + "=" * (-len(pairing_id) % 4)
            )
        except (ValueError, TypeError):
            raise CompanionTlsError("invalid pairing descriptor transaction") from None
        if (len(decoded_pairing_id) != 32 or
                base64.urlsafe_b64encode(decoded_pairing_id).rstrip(b"=").decode("ascii") != pairing_id):
            raise CompanionTlsError("invalid pairing descriptor transaction")
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
    certificate_expiry = int(identity.expires_at.timestamp())
    if certificate_expiry <= now:
        raise CompanionTlsError("host TLS identity is expired")
    descriptor_expiry = min(now + ttl_seconds, certificate_expiry)
    descriptor = {
        "version": 2 if pairing_id is not None else DESCRIPTOR_VERSION,
        "endpoint": normalized_endpoint,
        "fingerprint_sha256": formatted_fingerprint,
        "expires_at": descriptor_expiry,
    }
    if pairing_id is not None:
        descriptor["pairing_id"] = pairing_id
    return json.dumps(descriptor, separators=(",", ":"), ensure_ascii=True)
