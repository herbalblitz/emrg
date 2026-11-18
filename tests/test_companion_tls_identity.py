import os
import stat
import ssl
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_tls import CompanionTlsError
from emrg.server.companion_tls_identity import (
    CERT_FILENAME,
    DPAPI_KEY_FILENAME,
    KEY_FILENAME,
    create_identity_server_context,
    provision_host_identity,
)


def _key_path(directory: Path) -> Path:
    return directory / (DPAPI_KEY_FILENAME if os.name == "nt" else KEY_FILENAME)


def test_provision_identity_and_persist_fingerprint_across_restarts(tmp_path):
    hosts = ["localhost", "127.0.0.1"]
    identity = provision_host_identity(tmp_path / "identity", hosts)
    again = provision_host_identity(tmp_path / "identity", hosts)
    assert identity.fingerprint_sha256 == again.fingerprint_sha256
    assert len(identity.fingerprint_sha256) == 64
    cert = x509.load_pem_x509_certificate(identity.certificate_path.read_bytes())
    sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    assert sans.get_values_for_type(x509.DNSName) == ["localhost"]
    assert str(sans.get_values_for_type(x509.IPAddress)[0]) == "127.0.0.1"
    now = datetime.now(timezone.utc)
    assert identity.expires_at > now
    assert identity.expires_at <= now + timedelta(days=366)


def test_identity_key_and_directory_are_private_on_posix(tmp_path):
    if os.name != "posix":
        pytest.skip("POSIX permission bits are not meaningful on this platform")
    directory = tmp_path / "private-identity"
    provision_host_identity(directory, ["localhost"])
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    assert stat.S_IMODE(_key_path(directory).stat().st_mode) == 0o600
    assert stat.S_IMODE((directory / CERT_FILENAME).stat().st_mode) == 0o600


def test_existing_identity_rejects_host_change_without_explicit_reset(tmp_path):
    directory = tmp_path / "identity"
    provision_host_identity(directory, ["localhost"])
    with pytest.raises(CompanionTlsError, match="SAN does not match"):
        provision_host_identity(directory, ["other.example"])


def test_explicit_reset_rotates_fingerprint(tmp_path):
    directory = tmp_path / "identity"
    first = provision_host_identity(directory, ["localhost"])
    second = provision_host_identity(directory, ["localhost"], reset=True)
    assert first.fingerprint_sha256 != second.fingerprint_sha256


def test_corrupt_or_partial_identity_fails_closed(tmp_path):
    directory = tmp_path / "identity"
    directory.mkdir()
    (directory / CERT_FILENAME).write_text("corrupt", encoding="utf-8")
    with pytest.raises(CompanionTlsError):
        provision_host_identity(directory, ["localhost"])
    (directory / CERT_FILENAME).unlink()
    _key_path(directory).write_bytes(b"orphan key")
    with pytest.raises(CompanionTlsError, match="incomplete"):
        provision_host_identity(directory, ["localhost"])


def test_tls_server_context_loads_identity_without_opening_socket(tmp_path):
    directory = tmp_path / "identity"
    provision_host_identity(directory, ["localhost"])
    context = create_identity_server_context(directory)
    assert isinstance(context, ssl.SSLContext)
    assert context.minimum_version == ssl.TLSVersion.TLSv1_2


@pytest.mark.parametrize("host", ["", "not a host", "bad..domain", "-invalid.example", "a" * 254])
def test_invalid_host_names_rejected(tmp_path, host):
    with pytest.raises(CompanionTlsError):
        provision_host_identity(tmp_path / "identity", [host])


def test_symlink_identity_file_rejected(tmp_path):
    if os.name == "nt":
        pytest.skip("symlink creation requires elevated/developer permissions on Windows")
    directory = tmp_path / "identity"
    directory.mkdir()
    target = tmp_path / "target"
    target.write_text("not a certificate", encoding="utf-8")
    (directory / CERT_FILENAME).symlink_to(target)
    with pytest.raises(CompanionTlsError, match="symlinks"):
        provision_host_identity(directory, ["localhost"])


def test_identity_rejects_certificate_key_mismatch(tmp_path):
    directory = tmp_path / "identity"
    provision_host_identity(directory, ["localhost"])
    replacement_key = ec.generate_private_key(ec.SECP256R1()).private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    _key_path(directory).write_bytes(replacement_key)
    with pytest.raises(CompanionTlsError):
        provision_host_identity(directory, ["localhost"])


def test_concurrent_identity_provisioning_keeps_one_stable_pin(tmp_path):
    directory = tmp_path / "identity"
    with ThreadPoolExecutor(max_workers=6) as pool:
        identities = list(pool.map(lambda _: provision_host_identity(directory, ["localhost"]), range(6)))
    assert len({identity.fingerprint_sha256 for identity in identities}) == 1
