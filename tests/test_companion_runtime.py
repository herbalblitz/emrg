import asyncio
import base64
import json

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_runtime import CompanionRuntime, CompanionRuntimeError


class _FakeListener:
    instances = []

    def __init__(self, host, config, *, identity_directory):
        self.host = host
        self.config = config
        self.identity_directory = identity_directory
        self.running = False
        self.closed = False
        self.instances.append(self)

    async def start(self):
        assert self.host.enabled
        self.running = True

    async def close(self):
        self.running = False
        self.closed = True


def test_runtime_is_inert_until_explicit_start_and_stops_cleanly(tmp_path, monkeypatch):
    _FakeListener.instances.clear()
    monkeypatch.setattr(
        "emrg.server.companion_runtime.CompanionWssListener", _FakeListener,
    )
    host = CompanionHostService(tmp_path / "credentials.json")
    runtime = CompanionRuntime(host, tmp_path / "tls-identity")

    assert runtime.running is False
    assert host.enabled is False
    assert runtime.status() == {
        "listener_running": False, "endpoint": None,
        "fingerprint_sha256": None, "active_connections": 0,
    }

    started = asyncio.run(runtime.start({
        "bind_address": "127.0.0.1",
        "port": 18476,
        "cwd": str(tmp_path),
    }))
    assert runtime.running is True
    assert host.enabled is True
    assert runtime.status() == {
        "listener_running": True, "endpoint": "wss://127.0.0.1:18476",
        "fingerprint_sha256": started["fingerprint_sha256"], "active_connections": 0,
    }
    assert started["running"] is True
    assert started["endpoint"] == "wss://127.0.0.1:18476"
    assert started["fingerprint_sha256"]
    descriptor = json.loads(started["descriptor"])
    assert descriptor["endpoint"] == started["endpoint"]
    assert descriptor["fingerprint_sha256"] == started["fingerprint_sha256"]
    assert descriptor["version"] == 2
    assert descriptor["pairing_id"] == started["pairing_id"]
    assert set(descriptor) == {
        "version", "endpoint", "fingerprint_sha256", "expires_at", "pairing_id",
    }
    assert _FakeListener.instances[0].config.bind_address == "127.0.0.1"
    assert _FakeListener.instances[0].identity_directory == tmp_path / "tls-identity"

    # The pairing ID carried in QR v2 is already registered by the host and
    # accepts a real device-key request; the QR itself contains no credential.
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_key = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    host.request_pairing_v2(
        pairing_id=started["pairing_id"],
        device_id="android-runtime-test",
        public_key=base64.urlsafe_b64encode(public_key).rstrip(b"=").decode("ascii"),
        requested_scopes=["status"],
        client_nonce=base64.urlsafe_b64encode(b"n" * 32).rstrip(b"=").decode("ascii"),
    )
    assert host.pending_pairings_v2()[0]["pairing_id"] == started["pairing_id"]

    assert asyncio.run(runtime.stop()) == {"running": False}
    assert host.pending_pairings_v2() == []
    assert runtime.running is False
    assert host.enabled is False
    assert runtime.status()["listener_running"] is False
    assert _FakeListener.instances[0].closed is True


@pytest.mark.parametrize("payload", [
    None,
    {},
    {"bind_address": "0.0.0.0", "port": 18476, "cwd": "/tmp"},
    {"bind_address": "127.0.0.1", "port": True, "cwd": "/tmp"},
    {"bind_address": "127.0.0.1", "port": 18476, "cwd": "/tmp", "workspace": "/tmp"},
    {"bind_address": "127.0.0.1", "port": 18476, "cwd": "/path/that/does/not/exist"},
])
def test_runtime_rejects_invalid_or_wildcard_start_payload(tmp_path, payload):
    host = CompanionHostService(tmp_path / "credentials.json")
    runtime = CompanionRuntime(host, tmp_path / "tls-identity")
    with pytest.raises(CompanionRuntimeError):
        asyncio.run(runtime.start(payload))
    assert host.enabled is False
    assert runtime.running is False


def test_runtime_does_not_leave_host_enabled_when_listener_start_fails(tmp_path, monkeypatch):
    class BrokenListener(_FakeListener):
        async def start(self):
            raise OSError("bind failed")

    monkeypatch.setattr(
        "emrg.server.companion_runtime.CompanionWssListener", BrokenListener,
    )
    host = CompanionHostService(tmp_path / "credentials.json")
    runtime = CompanionRuntime(host, tmp_path / "tls-identity")
    with pytest.raises(CompanionRuntimeError, match="could not be started"):
        asyncio.run(runtime.start({
            "bind_address": "127.0.0.1", "port": 18476, "cwd": str(tmp_path),
        }))
    assert host.enabled is False
    assert runtime.running is False
    assert BrokenListener.instances[-1].closed is True
