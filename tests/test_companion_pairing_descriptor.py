import json
from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest

from emrg.server.companion_tls import CompanionTlsError
from emrg.server.companion_tls_identity import provision_host_identity
from emrg.server.companion_pairing_descriptor import (
    MAX_DESCRIPTOR_TTL_SECONDS,
    create_pairing_descriptor,
)


def test_descriptor_is_android_compatible_and_credential_free(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost", "127.0.0.1"])
    payload = create_pairing_descriptor(
        identity, "wss://localhost:8443", now_epoch_seconds=1000
    )
    descriptor = json.loads(payload)
    assert descriptor == {
        "version": 1,
        "endpoint": "wss://localhost:8443",
        "fingerprint_sha256": identity.fingerprint_sha256,
        "expires_at": 1000 + MAX_DESCRIPTOR_TTL_SECONDS,
    }
    assert not any(key in descriptor for key in ("credential", "token", "secret", "password"))


def test_descriptor_uses_custom_ttl_and_normalizes_trailing_slash(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    descriptor = json.loads(create_pairing_descriptor(
        identity, "wss://localhost:443/", ttl_seconds=60, now_epoch_seconds=50
    ))
    assert descriptor["endpoint"] == "wss://localhost:443"
    assert descriptor["expires_at"] == 110


@pytest.mark.parametrize("endpoint", [
    "ws://localhost:8443",
    "wss://user:pass@localhost:8443",
    "wss://localhost:8443/path",
    "wss://localhost:8443?token=x",
    "wss://localhost:8443#fragment",
    "wss://localhost:99999",
    "not-an-endpoint",
])
def test_descriptor_rejects_insecure_or_malformed_endpoints(tmp_path, endpoint):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    with pytest.raises(CompanionTlsError):
        create_pairing_descriptor(identity, endpoint)


def test_descriptor_requires_endpoint_host_in_certificate_san(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    with pytest.raises(CompanionTlsError, match="not covered"):
        create_pairing_descriptor(identity, "wss://other.example:8443")


@pytest.mark.parametrize("ttl", [0, 181, -1, True, 1.5])
def test_descriptor_rejects_invalid_lifetime(tmp_path, ttl):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    with pytest.raises(CompanionTlsError):
        create_pairing_descriptor(identity, "wss://localhost:8443", ttl_seconds=ttl)


def test_descriptor_rejects_invalid_clock(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    with pytest.raises(CompanionTlsError):
        create_pairing_descriptor(identity, "wss://localhost:8443", now_epoch_seconds="bad")

def test_descriptor_rejects_expired_host_identity(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    expired = replace(identity, expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
    with pytest.raises(CompanionTlsError, match="identity is expired"):
        create_pairing_descriptor(expired, "wss://localhost:8443")


def test_descriptor_expiry_never_outlives_host_certificate(tmp_path):
    identity = provision_host_identity(tmp_path / "tls", ["localhost"])
    cert_expiry = datetime.now(timezone.utc) + timedelta(seconds=30)
    short_lived = replace(identity, expires_at=cert_expiry)
    descriptor = json.loads(create_pairing_descriptor(
        short_lived, "wss://localhost:8443", ttl_seconds=180,
        now_epoch_seconds=int(datetime.now(timezone.utc).timestamp())
    ))
    assert descriptor["expires_at"] <= int(cert_expiry.timestamp())
