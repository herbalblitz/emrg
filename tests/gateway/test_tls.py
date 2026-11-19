"""The gateway's TLS identity.

Android pins the SPKI of this certificate, so the properties that matter are:
the key is private, the certificate is stable across restarts (a new one unpairs
every phone), rotation is explicit, and the listener refuses anything below
TLS 1.3.
"""

from __future__ import annotations

import datetime as dt
import ssl

from emrg.gateway.tls import (
    certificate_fingerprint,
    ensure_certificate,
    load_server_context,
)


def test_the_certificate_is_created_private_and_stays_the_same(tmp_path):
    cert = tmp_path / "cert.pem"
    key = tmp_path / "key.pem"
    first = ensure_certificate(cert, key)
    assert cert.is_file() and key.is_file()
    assert (key.stat().st_mode & 0o777) == 0o600
    assert first.fingerprint.count(":") == 31
    again = ensure_certificate(cert, key)
    assert again.fingerprint == first.fingerprint, "a restart must not unpair every phone"
    assert "127.0.0.1" in first.sans


def test_rotation_is_explicit_and_produces_a_new_fingerprint(tmp_path):
    cert = tmp_path / "cert.pem"
    key = tmp_path / "key.pem"
    first = ensure_certificate(cert, key)
    rotated = ensure_certificate(cert, key, rotate=True)
    assert rotated.fingerprint != first.fingerprint
    assert certificate_fingerprint(cert) == rotated.fingerprint


def test_an_expiring_certificate_is_replaced(tmp_path):
    cert = tmp_path / "cert.pem"
    key = tmp_path / "key.pem"
    first = ensure_certificate(cert, key)
    far = dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=3650)
    renewed = ensure_certificate(cert, key, now=far)
    assert renewed.fingerprint != first.fingerprint


def test_the_server_context_requires_tls_13(tmp_path):
    cert = tmp_path / "cert.pem"
    key = tmp_path / "key.pem"
    ensure_certificate(cert, key)
    context = load_server_context(cert, key)
    assert isinstance(context, ssl.SSLContext)
    assert context.minimum_version == ssl.TLSVersion.TLSv1_3
