import pytest

from emrg.server.companion_listener_policy import (
    CompanionListenerConfig,
    normalize_bind_address,
)
from emrg.server.companion_tls import CompanionTlsError


@pytest.mark.parametrize("address,expected", [
    ("127.0.0.1", "127.0.0.1"),
    ("192.168.1.20", "192.168.1.20"),
    ("2001:db8::1", "2001:db8::1"),
])
def test_explicit_unicast_bind_addresses_normalize(address, expected):
    assert normalize_bind_address(address) == expected


@pytest.mark.parametrize("address", [
    "", "localhost", "0.0.0.0", "::", "224.0.0.1", "255.255.255.255",
    "999.1.1.1", "https://example.test", "10.0.0.1:443",
])
def test_rejects_non_literal_or_unsafe_bind_addresses(address):
    with pytest.raises(CompanionTlsError):
        normalize_bind_address(address)


def test_listener_config_accepts_defaults_and_explicit_private_interface():
    config = CompanionListenerConfig.build("192.168.10.7", 8765)
    assert config.bind_address == "192.168.10.7"
    assert config.port == 8765
    assert config.max_connections == 8
    assert config.handshake_timeout_seconds == 10
    assert config.idle_timeout_seconds == 60


@pytest.mark.parametrize("kwargs", [
    {"port": 0}, {"port": 80}, {"port": 65536}, {"port": True},
    {"max_connections": 0}, {"max_connections": 65},
    {"handshake_timeout_seconds": 0}, {"handshake_timeout_seconds": 31},
    {"idle_timeout_seconds": 4}, {"idle_timeout_seconds": 301},
    {"close_timeout_seconds": 0}, {"close_timeout_seconds": 16},
])
def test_rejects_out_of_range_listener_options(kwargs):
    args = {"bind_address": "192.168.1.5", "port": 8765}
    args.update(kwargs)
    with pytest.raises(CompanionTlsError):
        CompanionListenerConfig.build(**args)
