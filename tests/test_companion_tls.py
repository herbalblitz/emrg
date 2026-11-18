import hashlib
import ssl

import pytest

from emrg.server.companion_tls import (
    CompanionTlsError,
    certificate_fingerprint_sha256,
    fingerprint_matches,
    normalize_fingerprint,
    TLS_MINIMUM_VERSION,
)


def test_fingerprint_is_sha256_of_der_bytes():
    der = b"test certificate DER"
    expected = hashlib.sha256(der).hexdigest()
    assert certificate_fingerprint_sha256(der) == expected
    assert fingerprint_matches(der, expected)
    assert fingerprint_matches(der, expected.upper())


def test_fingerprint_normalizes_colons_and_whitespace():
    raw = "ab" * 32
    spaced = ":".join(raw[i:i + 2] for i in range(0, len(raw), 2))
    assert normalize_fingerprint("  " + spaced + "  ") == raw


@pytest.mark.parametrize("invalid", ["", "abc", "g" * 64, "00" * 31, None])
def test_invalid_fingerprint_rejected(invalid):
    with pytest.raises(CompanionTlsError):
        normalize_fingerprint(invalid)


def test_fingerprint_mismatch_is_false():
    assert not fingerprint_matches(b"der", "00" * 32)


def test_empty_der_rejected():
    with pytest.raises(CompanionTlsError):
        certificate_fingerprint_sha256(b"")


def test_server_context_fails_closed_for_missing_identity(tmp_path):
    with pytest.raises(CompanionTlsError, match="identity could not be loaded"):
        create_server_context(tmp_path / "missing-cert.pem", tmp_path / "missing-key.pem")


def test_companion_tls_policy_minimum_is_tls_12():
    assert TLS_MINIMUM_VERSION == ssl.TLSVersion.TLSv1_2
