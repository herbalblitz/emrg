"""Daemon IPC boundary tests for trusted local Companion controls."""
import asyncio
import base64
import json

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_local_control import CompanionLocalControl
from emrg.server.daemon import EmrgServer


class _Writer:
    def __init__(self):
        self.frames = []

    async def send(self, payload):
        self.frames.append(json.loads(payload))

    async def close(self):
        pass


def _server(tmp_path):
    host = CompanionHostService(tmp_path / "credentials.json")
    server = object.__new__(EmrgServer)
    server._companion_host_service = host
    server._companion_local_control = CompanionLocalControl(host)
    return server, host


def _create_pending(host):
    transaction = host.begin_pairing_v2(
        host_identity="host-fingerprint",
        workspace_id="workspace-1",
        cwd="/safe/work",
        scopes={"status"},
    )
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_key = base64.urlsafe_b64encode(public).rstrip(b"=").decode("ascii")
    nonce = base64.urlsafe_b64encode(b"x" * 32).rstrip(b"=").decode("ascii")
    host.request_pairing_v2(
        pairing_id=transaction["pairing_id"],
        device_id="android-1",
        public_key=public_key,
        requested_scopes=["status"],
        client_nonce=nonce,
    )
    return transaction["pairing_id"]


def test_daemon_companion_status_is_disabled_by_default(tmp_path):
    server, _host = _server(tmp_path)
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "status",
        "payload": {},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": True,
        "result": {"enabled": False, "pending_count": 0},
    }]


def test_daemon_companion_local_approval_routes_to_host_service(tmp_path):
    server, host = _server(tmp_path)
    host.enable()
    pairing_id = _create_pending(host)
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "pairing.decide",
        "payload": {"pairing_id": pairing_id, "approved": True},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": True,
        "result": {"decided": True},
    }]
    assert host.pending_pairings_v2()[0]["approved"] is True


def test_daemon_companion_control_rejects_unlisted_operation(tmp_path):
    server, _host = _server(tmp_path)
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "remote.approve",
        "payload": {},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": False,
        "error": "unknown local control operation",
    }]



def test_daemon_starts_and_stops_only_the_explicit_companion_listener(tmp_path):
    class Runtime:
        def __init__(self):
            self.calls = []
        async def start(self, payload):
            self.calls.append(("start", payload))
            return {"running": True, "endpoint": "wss://127.0.0.1:18476"}
        async def stop(self):
            self.calls.append(("stop", {}))
            return {"running": False}

    server, _host = _server(tmp_path)
    runtime = Runtime()
    server._companion_runtime = runtime
    writer = _Writer()

    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "listener.start",
        "payload": {"bind_address": "127.0.0.1", "port": 18476},
    }, writer))
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "listener.stop",
        "payload": {},
    }, writer))

    assert runtime.calls == [
        ("start", {"bind_address": "127.0.0.1", "port": 18476}),
        ("stop", {}),
    ]
    assert writer.frames == [
        {
            "type": "companion_local_control_result",
            "ok": True,
            "result": {"running": True, "endpoint": "wss://127.0.0.1:18476"},
        },
        {
            "type": "companion_local_control_result",
            "ok": True,
            "result": {"running": False},
        },
    ]


def test_daemon_listener_stop_rejects_unexpected_payload(tmp_path):
    server, _host = _server(tmp_path)
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "listener.stop",
        "payload": {"force": True},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": False,
        "error": "invalid listener stop request",
    }]
