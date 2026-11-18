"""Provision and persist a dedicated TLS identity for the future Companion host.

No sockets/listeners are created here. On Windows, the private key is protected
with user-scoped DPAPI. On POSIX, it is stored as a PEM file with mode 0600 in
a directory requested as mode 0700. Cross-process locking and Windows UI approval
remain host-integration responsibilities.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import ipaddress
import os
import re
import ssl
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from emrg.server.companion_tls import CompanionTlsError, TLS_MINIMUM_VERSION

CERT_FILENAME = "companion-host-cert.pem"
KEY_FILENAME = "companion-host-key.pem"
DPAPI_KEY_FILENAME = "companion-host-key.dpapi"
IDENTITY_VALIDITY_DAYS = 365
_IDENTITY_LOCK = threading.RLock()


@dataclass(frozen=True)
class HostTlsIdentity:
    certificate_path: Path
    fingerprint_sha256: str
    san_hosts: tuple[str, ...]
    expires_at: dt.datetime


def _normalize_hosts(hosts: list[str] | tuple[str, ...]) -> tuple[str, ...]:
    if not isinstance(hosts, (list, tuple)) or not hosts:
        raise CompanionTlsError("at least one TLS endpoint host is required")
    normalized: set[str] = set()
    for value in hosts:
        if not isinstance(value, str) or not value.strip():
            raise CompanionTlsError("invalid TLS endpoint host")
        host = value.strip().rstrip(".")
        try:
            normalized.add(str(ipaddress.ip_address(host)))
        except ValueError:
            try:
                ascii_host = host.encode("idna").decode("ascii").lower()
            except UnicodeError:
                raise CompanionTlsError("invalid TLS endpoint host") from None
            if len(ascii_host) > 253 or not re.fullmatch(
                r"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*",
                ascii_host,
            ):
                raise CompanionTlsError("invalid TLS endpoint host")
            normalized.add(ascii_host)
    return tuple(sorted(normalized))


def _san_extension(certificate: x509.Certificate) -> tuple[str, ...]:
    try:
        san = certificate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    except x509.ExtensionNotFound:
        raise CompanionTlsError("host certificate has no SAN") from None
    values = [str(value).lower() for value in san.get_values_for_type(x509.DNSName)]
    values.extend(str(value) for value in san.get_values_for_type(x509.IPAddress))
    return tuple(sorted(set(values)))


def _protect_key(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    try:
        import ctypes
        from ctypes import wintypes

        class DataBlob(ctypes.Structure):
            _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]

        source = (ctypes.c_byte * len(data)).from_buffer_copy(data)
        input_blob = DataBlob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_byte)))
        output_blob = DataBlob()
        crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        protect = crypt32.CryptProtectData
        protect.argtypes = [ctypes.POINTER(DataBlob), wintypes.LPCWSTR,
                            ctypes.POINTER(DataBlob), ctypes.c_void_p,
                            ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
        protect.restype = wintypes.BOOL
        if not protect(ctypes.byref(input_blob), "EMRG Companion TLS key", None,
                       None, None, 0x1, ctypes.byref(output_blob)):
            raise OSError(ctypes.get_last_error())
        try:
            return ctypes.string_at(output_blob.pbData, output_blob.cbData)
        finally:
            kernel32.LocalFree(output_blob.pbData)
    except Exception:
        raise CompanionTlsError("private key protection failed") from None


def _unprotect_key(data: bytes) -> bytes:
    if os.name != "nt":
        return data
    try:
        import ctypes
        from ctypes import wintypes

        class DataBlob(ctypes.Structure):
            _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]

        source = (ctypes.c_byte * len(data)).from_buffer_copy(data)
        input_blob = DataBlob(len(data), ctypes.cast(source, ctypes.POINTER(ctypes.c_byte)))
        output_blob = DataBlob()
        crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        unprotect = crypt32.CryptUnprotectData
        unprotect.argtypes = [ctypes.POINTER(DataBlob), ctypes.POINTER(wintypes.LPWSTR),
                              ctypes.POINTER(DataBlob), ctypes.c_void_p,
                              ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
        unprotect.restype = wintypes.BOOL
        if not unprotect(ctypes.byref(input_blob), None, None, None, None,
                         0x1, ctypes.byref(output_blob)):
            raise OSError(ctypes.get_last_error())
        try:
            return ctypes.string_at(output_blob.pbData, output_blob.cbData)
        finally:
            kernel32.LocalFree(output_blob.pbData)
    except Exception:
        raise CompanionTlsError("private key protection could not be opened") from None


def _atomic_write(path: Path, data: bytes, *, mode: int = 0o600) -> None:
    fd, temporary = tempfile.mkstemp(dir=str(path.parent), prefix=".companion-identity-")
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if os.name == "posix":
            os.chmod(temporary, mode)
        os.replace(temporary, path)
    except OSError:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise CompanionTlsError("TLS identity persistence failed") from None


def _certificate_and_key(hosts: tuple[str, ...]) -> tuple[bytes, bytes]:
    now = dt.datetime.now(dt.timezone.utc)
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "EMRG Companion Host")])
    san_values = []
    for host in hosts:
        try:
            san_values.append(x509.IPAddress(ipaddress.ip_address(host)))
        except ValueError:
            san_values.append(x509.DNSName(host))
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(minutes=2))
        .not_valid_after(now + dt.timedelta(days=IDENTITY_VALIDITY_DAYS))
        .add_extension(x509.SubjectAlternativeName(san_values), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(
            x509.KeyUsage(digital_signature=True, content_commitment=False,
                          key_encipherment=False, data_encipherment=False,
                          key_agreement=False, key_cert_sign=False, crl_sign=False,
                          encipher_only=None, decipher_only=None),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )
    certificate_pem = certificate.public_bytes(serialization.Encoding.PEM)
    private_key_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    return certificate_pem, private_key_pem


def _load_and_validate(directory: Path, hosts: tuple[str, ...]) -> HostTlsIdentity:
    cert_path = directory / CERT_FILENAME
    key_path = directory / (DPAPI_KEY_FILENAME if os.name == "nt" else KEY_FILENAME)
    if cert_path.is_symlink() or key_path.is_symlink():
        raise CompanionTlsError("TLS identity files must not be symlinks")
    try:
        certificate_pem = cert_path.read_bytes()
        protected_key = key_path.read_bytes()
        private_key_pem = _unprotect_key(protected_key)
        certificate = x509.load_pem_x509_certificate(certificate_pem)
        private_key = serialization.load_pem_private_key(private_key_pem, password=None)
    except CompanionTlsError:
        raise
    except Exception:
        raise CompanionTlsError("stored TLS identity is invalid") from None
    cert_public_key = certificate.public_key()
    key_public_key = private_key.public_key()
    if not isinstance(cert_public_key, ec.EllipticCurvePublicKey) or cert_public_key.curve.name != "secp256r1":
        raise CompanionTlsError("stored TLS certificate algorithm is unsupported")
    if not isinstance(private_key, ec.EllipticCurvePrivateKey) or private_key.curve.name != "secp256r1":
        raise CompanionTlsError("stored TLS private key algorithm is unsupported")
    cert_public = cert_public_key.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    key_public = key_public_key.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if cert_public != key_public:
        raise CompanionTlsError("stored TLS certificate and key do not match")
    if certificate.issuer != certificate.subject:
        raise CompanionTlsError("stored TLS certificate is not self-signed")
    try:
        cert_public_key.verify(
            certificate.signature, certificate.tbs_certificate_bytes,
            ec.ECDSA(certificate.signature_hash_algorithm),
        )
        constraints = certificate.extensions.get_extension_for_class(x509.BasicConstraints).value
        extended_usage = certificate.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
        key_usage = certificate.extensions.get_extension_for_class(x509.KeyUsage).value
    except Exception:
        raise CompanionTlsError("stored TLS certificate is invalid") from None
    if constraints.ca or ExtendedKeyUsageOID.SERVER_AUTH not in extended_usage or not key_usage.digital_signature:
        raise CompanionTlsError("stored TLS certificate is not a server identity")
    now = dt.datetime.now(dt.timezone.utc)
    if certificate.not_valid_after_utc <= now or certificate.not_valid_before_utc > now:
        raise CompanionTlsError("stored TLS certificate is outside its validity period")
    san_hosts = _san_extension(certificate)
    if set(hosts) != set(san_hosts):
        raise CompanionTlsError("stored TLS identity SAN does not match requested endpoint hosts")
    digest = hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).digest()
    fingerprint = ":".join(f"{byte:02X}" for byte in digest)
    return HostTlsIdentity(cert_path, fingerprint, san_hosts, certificate.not_valid_after_utc)


def provision_host_identity(directory: Path, hosts: list[str] | tuple[str, ...], *,
                           reset: bool = False) -> HostTlsIdentity:
    """Thread-serialize identity provisioning; cross-process locking is not provided."""
    with _IDENTITY_LOCK:
        return _provision_host_identity(directory, hosts, reset=reset)


def _provision_host_identity(directory: Path, hosts: list[str] | tuple[str, ...], *,
                             reset: bool = False) -> HostTlsIdentity:
    """Create once or validate an existing identity; rotation requires reset=True."""
    directory = Path(directory)
    if directory.is_symlink():
        raise CompanionTlsError("TLS identity directory must not be a symlink")
    normalized_hosts = _normalize_hosts(hosts)
    cert_path = directory / CERT_FILENAME
    key_path = directory / (DPAPI_KEY_FILENAME if os.name == "nt" else KEY_FILENAME)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name == "posix":
        os.chmod(directory, 0o700)
    if cert_path.is_symlink() or key_path.is_symlink():
        raise CompanionTlsError("TLS identity files must not be symlinks")
    exists = cert_path.exists() or key_path.exists()
    if exists and not reset:
        if not cert_path.exists() or not key_path.exists():
            raise CompanionTlsError("stored TLS identity is incomplete")
        return _load_and_validate(directory, normalized_hosts)
    if reset:
        for path in (cert_path, key_path):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
    cert_pem, key_pem = _certificate_and_key(normalized_hosts)
    protected_key = _protect_key(key_pem)
    _atomic_write(key_path, protected_key)
    _atomic_write(cert_path, cert_pem)
    return _load_and_validate(directory, normalized_hosts)


def create_identity_server_context(directory: Path) -> ssl.SSLContext:
    """Thread-serialize context loading against provisioning; never opens a socket."""
    with _IDENTITY_LOCK:
        return _create_identity_server_context(directory)


def _create_identity_server_context(directory: Path) -> ssl.SSLContext:
    """Load the provisioned identity; temporary plaintext key is deleted immediately."""
    directory = Path(directory)
    try:
        certificate = x509.load_pem_x509_certificate((directory / CERT_FILENAME).read_bytes())
        san_hosts = _san_extension(certificate)
    except Exception:
        raise CompanionTlsError("stored TLS identity is invalid") from None
    identity = _load_and_validate(directory, san_hosts)
    cert_path = identity.certificate_path
    key_path = directory / (DPAPI_KEY_FILENAME if os.name == "nt" else KEY_FILENAME)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = TLS_MINIMUM_VERSION
    context.options |= ssl.OP_NO_COMPRESSION
    if os.name != "nt":
        try:
            context.load_cert_chain(str(cert_path), str(key_path))
        except (OSError, ssl.SSLError):
            raise CompanionTlsError("host TLS identity could not be loaded") from None
        return context
    try:
        private_key = _unprotect_key(key_path.read_bytes())
        with tempfile.TemporaryDirectory(prefix="emrg-companion-tls-") as temporary:
            ephemeral_key = Path(temporary) / "identity-key.pem"
            ephemeral_key.write_bytes(private_key)
            context.load_cert_chain(str(cert_path), str(ephemeral_key))
    except (OSError, ssl.SSLError, CompanionTlsError):
        raise CompanionTlsError("host TLS identity could not be loaded") from None
    return context
