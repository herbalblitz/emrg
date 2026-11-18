"""TLS primitives for the future EMRG Companion host.

This module intentionally creates no sockets and starts no listener. TLS identity
provisioning and listener lifecycle remain explicit host-UI responsibilities.
"""
from __future__ import annotations

import hashlib
import ssl
from pathlib import Path

FINGERPRINT_BYTES = 32
TLS_MINIMUM_VERSION = ssl.TLSVersion.TLSv1_2


class CompanionTlsError(ValueError):
    """Safe TLS identity/configuration failure."""


def certificate_fingerprint_sha256(certificate_der: bytes) -> str:
    """Return normalized SHA-256 pin (lowercase hex, no separators)."""
    if not isinstance(certificate_der, bytes) or not certificate_der:
        raise CompanionTlsError("certificate data unavailable")
    return hashlib.sha256(certificate_der).hexdigest()


def normalize_fingerprint(value: str) -> str:
    """Normalize colon/space-delimited SHA-256 fingerprints; reject malformed pins."""
    if not isinstance(value, str):
        raise CompanionTlsError("invalid fingerprint")
    normalized = value.replace(":", "").replace(" ", "").strip().lower()
    if len(normalized) != FINGERPRINT_BYTES * 2:
        raise CompanionTlsError("invalid fingerprint")
    try:
        bytes.fromhex(normalized)
    except ValueError:
        raise CompanionTlsError("invalid fingerprint") from None
    return normalized


def fingerprint_matches(certificate_der: bytes, expected: str) -> bool:
    """Constant-time comparison against a certificate's DER SHA-256 digest."""
    import hmac

    actual = certificate_fingerprint_sha256(certificate_der)
    pin = normalize_fingerprint(expected)
    return hmac.compare_digest(actual, pin)


def create_server_context(cert_file: Path, key_file: Path) -> ssl.SSLContext:
    """Load a dedicated host identity and return a hardened TLS server context.

    SSLContext.load_cert_chain validates certificate/key compatibility. This
    helper does not bind, accept, or expose a socket and does not configure
    application/device authentication.
    """
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = TLS_MINIMUM_VERSION
    context.options |= ssl.OP_NO_COMPRESSION
    try:
        context.load_cert_chain(certfile=str(cert_file), keyfile=str(key_file))
    except (OSError, ssl.SSLError):
        raise CompanionTlsError("host TLS identity could not be loaded") from None
    return context
