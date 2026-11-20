"""Daemon IPC boundary tests for trusted local Companion controls."""
import asyncio
import base64
import json
import logging

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_local_control import CompanionLocalControl
from emrg.server.daemon import EmrgServer


class _Writer:
    def __init__(self):
        self.frames = []
        # Models a local peer: the daemon binds 127.0.0.1, so a real
        # companion connection always carries a loopback address.
        self.remote_address = ("127.0.0.1", 1234)

    async def send(self, payload):
        self.frames.append(json.loads(payload))

    async def close(self):
        pass


def _server(tmp_path):
    host = CompanionHostService(tmp_path / "credentials.json")
    server = object.__new__(EmrgServer)
    server._companion_host_service = host
    server._companion_local_control = CompanionLocalControl(host)
    class RuntimeStatus:
        def status(self):
            return {
                "listener_running": False, "endpoint": None,
                "fingerprint_sha256": None, "active_connections": 0,
            }
    server._companion_runtime = RuntimeStatus()
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
        "result": {
            "enabled": False, "pending_count": 0, "listener_running": False,
            "endpoint": None, "fingerprint_sha256": None, "active_connections": 0,
        },
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


def test_daemon_companion_control_rejects_non_local_peer(tmp_path):
    server, host = _server(tmp_path)
    host.enable()
    pairing_id = _create_pending(host)
    writer = _Writer()
    writer.remote_address = ("192.0.2.10", 1234)
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "pairing.decide",
        "payload": {"pairing_id": pairing_id, "approved": True},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": False,
        "error": "local Companion control unavailable",
    }]
    # Nothing was dispatched: the pending pairing is still undecided.
    assert host.pending_pairings_v2()[0]["approved"] is None


def test_daemon_companion_control_rejects_peer_without_address(tmp_path):
    server, _host = _server(tmp_path)
    writer = _Writer()
    del writer.remote_address
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "status",
        "payload": {},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": False,
        "error": "local Companion control unavailable",
    }]


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


def test_daemon_companion_control_refuses_unsafe_session_id_first(tmp_path):
    # The per-message session-id guard runs before the companion branch:
    # a path-escaping session id never reaches the control handler.
    server, host = _server(tmp_path)
    host.enable()
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "status",
        "payload": {},
        "session_id": "../..",
    }, writer))
    assert len(writer.frames) == 1
    assert writer.frames[0]["error"].startswith("invalid session id")
    assert "type" not in writer.frames[0] or writer.frames[0].get("type") != "companion_local_control_result"


def test_daemon_companion_decide_on_unknown_pairing_decides_nothing(tmp_path):
    server, host = _server(tmp_path)
    host.enable()
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "pairing.decide",
        "payload": {"pairing_id": "A" * 43, "approved": True},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": True,
        "result": {"decided": False},
    }]


def test_daemon_companion_decide_twice_decides_only_once(tmp_path):
    server, host = _server(tmp_path)
    host.enable()
    pairing_id = _create_pending(host)
    writer = _Writer()
    for _ in range(2):
        asyncio.run(server._process_message({
            "type": "companion_local_control",
            "operation": "pairing.decide",
            "payload": {"pairing_id": pairing_id, "approved": True},
        }, writer))
    assert writer.frames == [
        {"type": "companion_local_control_result", "ok": True, "result": {"decided": True}},
        {"type": "companion_local_control_result", "ok": True, "result": {"decided": False}},
    ]


def test_daemon_companion_cancel_on_unknown_pairing_cancels_nothing(tmp_path):
    server, host = _server(tmp_path)
    host.enable()
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "pairing.cancel",
        "payload": {"pairing_id": "A" * 43},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": True,
        "result": {"cancelled": False},
    }]


def test_daemon_companion_decide_with_malformed_payload_is_rejected(tmp_path):
    server, _host = _server(tmp_path)
    writer = _Writer()
    asyncio.run(server._process_message({
        "type": "companion_local_control",
        "operation": "pairing.decide",
        "payload": {"pairing_id": "A" * 43},
    }, writer))
    assert writer.frames == [{
        "type": "companion_local_control_result",
        "ok": False,
        "error": "invalid local control request",
    }]


def test_daemon_companion_control_logs_allowed_and_denied_actions(tmp_path, caplog):
    server, _host = _server(tmp_path)
    writer = _Writer()
    with caplog.at_level(logging.INFO, logger="emrg.server.daemon"):
        asyncio.run(server._process_message({
            "type": "companion_local_control",
            "operation": "status",
            "payload": {},
        }, writer))
    assert "companion local control allowed: operation='status'" in caplog.text
    writer.remote_address = ("192.0.2.10", 1234)
    with caplog.at_level(logging.WARNING, logger="emrg.server.daemon"):
        asyncio.run(server._process_message({
            "type": "companion_local_control",
            "operation": "status",
            "payload": {},
        }, writer))
    assert "companion local control denied" in caplog.text
    assert "192.0.2.10" in caplog.text
