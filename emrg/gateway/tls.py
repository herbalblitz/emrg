"""TLS identity for the gateway.

The gateway terminates TLS itself with a per-host certificate. Android pins the
certificate's **SPKI**, not a public CA: there is no CA on a home LAN, and a
pinned key survives the host's IP changing from ``192.168.1.20`` to
``192.168.1.31`` without a re-pair.

The private key never leaves ``~/.emrg/gateway/key.pem`` (mode 0600). The
fingerprint printed at pairing time is the SHA-256 of the DER certificate, the
same value the phone compares before it stores the pin.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import ipaddress
import os
import socket
import ssl
from dataclasses import dataclass
from pathlib import Path

CERT_DAYS = 3650
RENEW_BEFORE_DAYS = 30


class GatewayTlsError(RuntimeError):
    """Certificate generation/loading failed, with a readable cause."""


@dataclass(frozen=True)
class CertInfo:
    cert: Path
    key: Path
    fingerprint: str
    sans: tuple[str, ...]
    not_after: _dt.datetime


def ensure_certificate(
    cert_path: Path,
    key_path: Path,
    *,
    rotate: bool = False,
    now: _dt.datetime | None = None,
) -> CertInfo:
    """Load the host certificate, generating one when needed.

    A valid certificate is never silently regenerated: doing so would unpair
    every phone at once. Rotation is an explicit host action
    (``emrg-gateway rotate-cert``), and the CLI prints the new fingerprint.
    """
    now = now or _dt.datetime.now(_dt.timezone.utc)
    if not rotate and cert_path.is_file() and key_path.is_file():
        info = _read_info(cert_path, key_path)
        if info.not_after - now >= _dt.timedelta(days=RENEW_BEFORE_DAYS):
            return info
    return _generate(cert_path, key_path, now=now)


def _read_info(cert_path: Path, key_path: Path) -> CertInfo:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
    except ImportError as exc:  # pragma: no cover - exercised by the CLI message
        raise GatewayTlsError(_MISSING_CRYPTOGRAPHY) from exc
    try:
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        key_path.read_bytes()
    except (OSError, ValueError) as exc:
        raise GatewayTlsError(f"cannot read the gateway certificate: {exc}") from exc
    sans = _sans_from_cert(cert)
    expires = getattr(cert, "not_valid_after_utc", None)
    if expires is None:  # cryptography < 42
        expires = cert.not_valid_after.replace(tzinfo=_dt.timezone.utc)
    return CertInfo(
        cert=cert_path,
        key=key_path,
        fingerprint=certificate_fingerprint(cert_path),
        sans=sans,
        not_after=expires,
    )


def _generate(cert_path: Path, key_path: Path, *, now: _dt.datetime) -> CertInfo:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    except ImportError as exc:  # pragma: no cover - exercised by the CLI message
        raise GatewayTlsError(_MISSING_CRYPTOGRAPHY) from exc

    key = ec.generate_private_key(ec.SECP256R1())
    hostname = _safe_hostname()
    sans = _local_names(hostname)
    subject = x509.Name(
        [
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "EMRG"),
            x509.NameAttribute(NameOID.COMMON_NAME, f"EMRG Gateway ({hostname})"),
        ]
    )
    alt_names: list[x509.GeneralName] = [x509.DNSName("localhost"), x509.DNSName("emrg.local")]
    if hostname and hostname not in {"localhost", "emrg.local"}:
        alt_names.append(x509.DNSName(hostname))
    for raw in sans:
        try:
            alt_names.append(x509.IPAddress(ipaddress.ip_address(raw)))
        except ValueError:
            continue
    # DNS/IP duplicates are legal in a SAN extension but ugly in a fingerprint
    # display; dedupe by the encoded value.
    seen: set[bytes] = set()
    unique: list[x509.GeneralName] = []
    for name in alt_names:
        encoded = name.public_bytes() if hasattr(name, "public_bytes") else repr(name).encode()
        if encoded in seen:
            continue
        seen.add(encoded)
        unique.append(name)

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - _dt.timedelta(days=1))
        .not_valid_after(now + _dt.timedelta(days=CERT_DAYS))  # noqa: DTZ - aware, see `now`
        .add_extension(x509.SubjectAlternativeName(unique), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=False,
                crl_sign=False,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(
            x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),
            critical=False,
        )
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(key.public_key()),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    cert_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.parent.mkdir(parents=True, exist_ok=True)
    _write_private(
        key_path,
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ),
    )
    _write_public(cert_path, cert.public_bytes(serialization.Encoding.PEM))
    return _read_info(cert_path, key_path)


def load_server_context(cert_path: Path, key_path: Path) -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.options |= ssl.OP_NO_COMPRESSION
    try:
        context.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
    except (OSError, ssl.SSLError) as exc:
        raise GatewayTlsError(f"cannot load the gateway TLS certificate: {exc}") from exc
    return context


def certificate_fingerprint(cert_path: Path) -> str:
    """Colon-separated SHA-256 of the DER certificate (the pin the phone shows)."""
    try:
        from cryptography import x509
    except ImportError as exc:  # pragma: no cover
        raise GatewayTlsError(_MISSING_CRYPTOGRAPHY) from exc
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    from cryptography.hazmat.primitives import serialization

    der = cert.public_bytes(serialization.Encoding.DER)
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i : i + 2] for i in range(0, len(digest), 2))


def _read_sans(cert_path: Path) -> tuple[str, ...]:
    try:
        from cryptography import x509
    except ImportError:  # pragma: no cover
        return ()
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    return _sans_from_cert(cert)


def _sans_from_cert(cert) -> tuple[str, ...]:
    try:
        from cryptography import x509
    except ImportError:  # pragma: no cover
        return ()
    try:
        extension = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName)
    except x509.ExtensionNotFound:
        return ()
    values: list[str] = []
    for name in extension.value:
        if isinstance(name, x509.DNSName):
            values.append(name.value)
        elif isinstance(name, x509.IPAddress):
            values.append(str(name.value))
    return tuple(values)


def _safe_hostname() -> str:
    try:
        name = socket.gethostname().strip()
    except OSError:
        return "emrg-host"
    return name or "emrg-host"


def _local_names(hostname: str) -> tuple[str, ...]:
    """Every address the host can plausibly be reached at, best effort."""
    found: list[str] = ["127.0.0.1", "::1"]
    try:
        for info in socket.getaddrinfo(hostname, None):
            addr = info[4][0]
            try:
                ipaddress.ip_address(addr)
            except ValueError:
                continue
            found.append(addr)
    except OSError:
        pass
    # The address a UDP route would use. No packet is sent by connect(); this
    # is the standard way to learn the primary LAN address without a network
    # call. A host with no route simply skips it.
    for family, target in ((socket.AF_INET, "10.255.255.255"), (socket.AF_INET6, "2001:db8::1")):
        try:
            probe = socket.socket(family, socket.SOCK_DGRAM)
            try:
                probe.connect((target, 9))
                addr = probe.getsockname()[0]
                ipaddress.ip_address(addr)
                found.append(addr)
            finally:
                probe.close()
        except OSError:
            continue
    return tuple(dict.fromkeys(found))


def _write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def _write_public(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


_MISSING_CRYPTOGRAPHY = (
    "the gateway needs the 'cryptography' package to create its TLS certificate. "
    "Install it with: pip install 'emrg[gateway]' (or: uv sync --extra gateway)"
)
