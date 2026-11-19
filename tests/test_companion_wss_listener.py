import asyncio
import json

import pytest

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_tls import CompanionTlsError
from emrg.server.companion_wss_listener import CompanionWssListener, _remote_session_metadata


def _listener(tmp_path, host=None):
    host = host or CompanionHostService(tmp_path / "registry.json")
    config = CompanionListenerConfig.build("127.0.0.1", 8765)
    return CompanionWssListener(host, config, tmp_path / "cert.pem", tmp_path / "key.pem")


def test_listener_start_fails_closed_when_companion_disabled(tmp_path):
    listener = _listener(tmp_path)
    with pytest.raises(CompanionTlsError, match="disabled"):
        asyncio.run(listener.start())
    assert listener.running is False


def test_stop_is_safe_when_never_started(tmp_path):
    listener = _listener(tmp_path)
    asyncio.run(listener.stop())
    assert not listener.running
    assert listener.active_connections == 0


def test_listener_start_is_explicit_and_context_loaded_before_bind(tmp_path, monkeypatch):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    listener = _listener(tmp_path, host)
    events = []

    class FakeServer:
        def close(self):
            events.append("close")
        async def wait_closed(self):
            events.append("wait_closed")

    def fake_context(cert, key):
        events.append(("tls", cert, key))
        return object()

    async def fake_serve(handler, address, port, **kwargs):
        events.append(("bind", address, port, kwargs["ssl"]))
        return FakeServer()

    monkeypatch.setattr("emrg.server.companion_wss_listener.create_server_context", fake_context)
    monkeypatch.setattr("emrg.server.companion_wss_listener.serve", fake_serve)
    asyncio.run(listener.start())
    assert listener.running
    assert events[0][0] == "tls"
    assert events[1][0] == "bind"
    asyncio.run(listener.stop())
    assert events[-2:] == ["close", "wait_closed"]
    assert not listener.running

    
def test_disabling_host_closes_registered_connections(tmp_path):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        listener = _listener(tmp_path, host)
        listener._loop = asyncio.get_running_loop()

        class Client:
            def __init__(self):
                self.closed = []
                self.close_event = asyncio.Event()
            async def close(self, *, code, reason):
                self.closed.append((code, reason))
                self.close_event.set()

        client = Client()
        listener._clients.add(client)
        events = []
        server_closed = asyncio.Event()
        class Server:
            def close(self):
                events.append("close")
            async def wait_closed(self):
                events.append("wait_closed")
                server_closed.set()
        listener._server = Server()
        host.enable()
        host.disable()
        await asyncio.wait_for(client.close_event.wait(), timeout=1)
        await asyncio.wait_for(server_closed.wait(), timeout=1)
        assert client.closed == [(1008, "Companion disabled")]
        assert events == ["close", "wait_closed"]
        assert listener.running is False
        assert client in listener._clients  # close wakes handler; handler removes it
        listener._clients.clear()
        listener._loop = None
    asyncio.run(run())

    
def test_wss_session_authenticates_and_returns_scoped_status(tmp_path):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(device_id="phone-1", workspace_id="ws-1",
                                        cwd="/workspace", scopes={"status"})
        listener = _listener(tmp_path, host)

        class Socket:
            def __init__(self, frames):
                self.frames = iter(frames)
                self.sent = []
            async def recv(self):
                try:
                    return next(self.frames)
                except StopIteration:
                    raise asyncio.TimeoutError()
            async def send(self, data):
                self.sent.append(data)
            async def close(self, **kwargs):
                pass

        auth = {"type": "auth", "protocol": "1.0", "device_id": "phone-1",
                "credential": credential, "client_nonce": "nonce-abcdefghijkl"}
        status = {"type": "status", "request_id": "r1"}
        ws = Socket([json.dumps(auth), json.dumps(status)])
        with pytest.raises(asyncio.TimeoutError):
            await listener._session(ws)
        decoded = [json.loads(frame) for frame in ws.sent]
        assert decoded[0]["type"] == "auth.ok"
        assert decoded[0]["workspace_id"] == "ws-1"
        assert decoded[1] == {"type": "status.result", "request_id": "r1",
                              "listener": "wss", "enabled": True}
    asyncio.run(run())



def test_wss_session_lists_only_credential_bound_workspace(tmp_path, monkeypatch):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="phone-2", workspace_id="workspace-approved",
            cwd=str(tmp_path / "approved-workspace"), scopes={"sessions.list"})
        listener = _listener(tmp_path, host)
        observed = []

        def list_sessions(cwd):
            observed.append(cwd)
            return [{"session_id": "session-1", "title": "Approved workspace",
                     "cwd": "/private/host/path", "history": "must-not-leak"}]

        monkeypatch.setattr("emrg.server.companion_wss_listener.Session.list_sessions", list_sessions)

        class Socket:
            def __init__(self, frames):
                self.frames = iter(frames)
                self.sent = []
            async def recv(self):
                try:
                    return next(self.frames)
                except StopIteration:
                    raise asyncio.TimeoutError()
            async def send(self, data):
                self.sent.append(data)
            async def close(self, **kwargs):
                pass

        auth = {"type": "auth", "protocol": "1.0", "device_id": "phone-2",
                "credential": credential, "client_nonce": "nonce-abcdefghijkl"}
        request = {"type": "sessions.list", "request_id": "list-1",
                   "workspace_id": "workspace-approved"}
        ws = Socket([json.dumps(auth), json.dumps(request)])
        with pytest.raises(asyncio.TimeoutError):
            await listener._session(ws)

        assert observed == [tmp_path / "approved-workspace"]
        result = json.loads(ws.sent[1])
        assert result == {
            "type": "sessions.result", "request_id": "list-1",
            "workspace_id": "workspace-approved",
            "sessions": [{"session_id": "session-1", "title": "Approved workspace"}],
        }
    asyncio.run(run())



def test_wss_rechecks_device_revocation_before_each_request(tmp_path):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="phone-revoked", workspace_id="ws-revoked",
            cwd=str(tmp_path), scopes={"status"})
        listener = _listener(tmp_path, host)
        auth = {"type": "auth", "protocol": "1.0", "device_id": "phone-revoked",
                "credential": credential, "client_nonce": "nonce-revoked-12345"}
        status = {"type": "status", "request_id": "must-not-run"}

        class Socket:
            def __init__(self):
                self.calls = 0
                self.sent = []
                self.closed = []
            async def recv(self):
                self.calls += 1
                if self.calls == 1:
                    return json.dumps(auth)
                host.revoke_device("phone-revoked")
                return json.dumps(status)
            async def send(self, data):
                self.sent.append(json.loads(data))
            async def close(self, **kwargs):
                self.closed.append(kwargs)

        ws = Socket()
        await listener._session(ws)
        assert [frame["type"] for frame in ws.sent] == ["auth.ok"]
        assert ws.closed == [{"code": 1008, "reason": "Device authorization changed"}]
    asyncio.run(run())


def test_wss_rejects_existing_identity_after_credential_rotation(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    credential = host.registry.issue(device_id="phone-rotate", workspace_id="ws-rotate",
                                     cwd=str(tmp_path), scopes={"status"})
    identity = host.authenticate(json.dumps({
        "type": "auth", "protocol": "1.0", "device_id": "phone-rotate",
        "credential": credential, "client_nonce": "nonce-rotate-123456",
    }))
    assert host.is_identity_current(identity)
    # Rotation with identical workspace and scopes must still invalidate old sockets.
    host.registry.issue(device_id="phone-rotate", workspace_id="ws-rotate",
                        cwd=str(tmp_path), scopes={"status"})
    assert not host.is_identity_current(identity)



def test_legacy_registry_entry_remains_valid_and_rotation_is_detected(tmp_path):
    import json

    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    credential = host.registry.issue(
        device_id="legacy-phone", workspace_id="legacy-ws",
        cwd=str(tmp_path), scopes={"status"})
    registry_path = tmp_path / "registry.json"
    data = json.loads(registry_path.read_text(encoding="utf-8"))
    data["devices"]["legacy-phone"].pop("generation")
    registry_path.write_text(json.dumps(data), encoding="utf-8")

    identity = host.authenticate(json.dumps({
        "type": "auth", "protocol": "1.0", "device_id": "legacy-phone",
        "credential": credential, "client_nonce": "nonce-legacy-123456",
    }))
    assert host.is_identity_current(identity)

    host.registry.issue(device_id="legacy-phone", workspace_id="legacy-ws",
                        cwd=str(tmp_path), scopes={"status"})
    assert not host.is_identity_current(identity)


def test_active_identity_check_fails_closed_when_registry_unavailable(tmp_path, monkeypatch):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    host.registry.issue(device_id="phone-registry", workspace_id="ws-registry",
                        cwd=str(tmp_path), scopes={"status"})

    def unavailable():
        from emrg.server.companion_credentials import CredentialRegistryError
        raise CredentialRegistryError("registry unavailable")

    monkeypatch.setattr(host.registry, "_read", unavailable)
    identity = {
        "device_id": "phone-registry", "workspace_id": "ws-registry",
        "cwd": str(tmp_path), "scopes": frozenset({"status"}),
    }
    assert not host.is_identity_current(identity)



def test_remote_session_metadata_is_allowlisted_bounded_and_capped():
    records = [{
        "session_id": "s-1", "title": "A" * 400,
        "cwd": "/private/path", "history": "private transcript",
        "message_count": 3, "created_at": "2026-01-01",
    }] + [{"session_id": f"s-{i}"} for i in range(250)]
    safe = _remote_session_metadata(records)
    assert len(safe) == 200
    assert safe[0] == {
        "session_id": "s-1", "title": "A" * 256,
        "message_count": 3, "created_at": "2026-01-01",
    }
    assert all("cwd" not in item and "history" not in item for item in safe)
    invalid_counts = _remote_session_metadata([
        {"session_id": "bool", "message_count": True},
        {"session_id": "negative", "message_count": -1},
        {"session_id": "string", "message_count": "3"},
        {"session_id": "valid-zero", "message_count": 0},
    ])
    assert invalid_counts == [
        {"session_id": "bool"},
        {"session_id": "negative"},
        {"session_id": "string"},
        {"session_id": "valid-zero", "message_count": 0},
    ]
    assert _remote_session_metadata([None, "not-a-record"]) == []
    assert _remote_session_metadata("not-a-list") == []



def test_wss_chat_send_is_explicitly_not_dispatched_even_with_scope(tmp_path, monkeypatch):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="phone-chat", workspace_id="ws-chat",
            cwd=str(tmp_path), scopes={"chat.send"})
        listener = _listener(tmp_path, host)
        dispatch_attempts = []

        # Guard against accidentally wiring chat execution into this transport.
        monkeypatch.setattr(
            host, "send_chat",
            lambda **kwargs: dispatch_attempts.append(kwargs),
            raising=False,
        )

        auth = {"type": "auth", "protocol": "1.0", "device_id": "phone-chat",
                "credential": credential, "client_nonce": "nonce-chat-12345678"}
        request = {"type": "chat.send", "request_id": "chat-1",
                   "workspace_id": "ws-chat", "prompt": "hello",
                   "session_id": "session-1"}

        class Socket:
            def __init__(self):
                self.frames = iter([json.dumps(auth), json.dumps(request)])
                self.sent = []
            async def recv(self):
                try:
                    return next(self.frames)
                except StopIteration:
                    raise asyncio.TimeoutError()
            async def send(self, data):
                self.sent.append(json.loads(data))
            async def close(self, **kwargs):
                pass

        ws = Socket()
        with pytest.raises(asyncio.TimeoutError):
            await listener._session(ws)

        assert ws.sent[1] == {
            "type": "error", "request_id": "chat-1", "code": "not_implemented"
        }
        assert dispatch_attempts == []

    asyncio.run(run())


def test_wss_rejects_sessions_list_without_scope_before_dispatch(tmp_path, monkeypatch):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="phone-no-sessions", workspace_id="ws-no-sessions",
            cwd=str(tmp_path), scopes={"status"})
        listener = _listener(tmp_path, host)
        calls = []

        def forbidden_list(cwd):
            calls.append(cwd)
            raise AssertionError("unauthorized request reached session listing")

        monkeypatch.setattr(
            "emrg.server.companion_wss_listener.Session.list_sessions",
            forbidden_list,
        )
        auth = {"type": "auth", "protocol": "1.0",
                "device_id": "phone-no-sessions", "credential": credential,
                "client_nonce": "nonce-no-sessions-123"}
        request = {"type": "sessions.list", "request_id": "list-denied",
                   "workspace_id": "ws-no-sessions"}

        class Socket:
            def __init__(self):
                self.frames = iter([json.dumps(auth), json.dumps(request)])
                self.sent = []
                self.closed = []
            async def recv(self):
                try:
                    return next(self.frames)
                except StopIteration:
                    raise asyncio.TimeoutError()
            async def send(self, data):
                self.sent.append(json.loads(data))
            async def close(self, **kwargs):
                self.closed.append(kwargs)

        ws = Socket()
        await listener._handle(ws)
        assert [frame["type"] for frame in ws.sent] == ["auth.ok"]
        assert ws.closed == [{"code": 1008, "reason": "Protocol rejected"}]
        assert calls == []

    asyncio.run(run())



def test_wss_closes_existing_socket_after_credential_rotation(tmp_path):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="phone-rotate-live", workspace_id="ws-rotate-live",
            cwd=str(tmp_path), scopes={"status"})
        listener = _listener(tmp_path, host)
        auth = {"type": "auth", "protocol": "1.0",
                "device_id": "phone-rotate-live", "credential": credential,
                "client_nonce": "nonce-rotate-live-1234"}
        status = {"type": "status", "request_id": "must-not-dispatch"}

        class Socket:
            def __init__(self):
                self.calls = 0
                self.sent = []
                self.closed = []
            async def recv(self):
                self.calls += 1
                if self.calls == 1:
                    return json.dumps(auth)
                # Replace the credential without changing workspace or scopes.
                host.registry.issue(
                    device_id="phone-rotate-live", workspace_id="ws-rotate-live",
                    cwd=str(tmp_path), scopes={"status"})
                return json.dumps(status)
            async def send(self, data):
                self.sent.append(json.loads(data))
            async def close(self, **kwargs):
                self.closed.append(kwargs)

        ws = Socket()
        await listener._session(ws)
        assert [frame["type"] for frame in ws.sent] == ["auth.ok"]
        assert ws.closed == [{
            "code": 1008, "reason": "Device authorization changed"
        }]

    asyncio.run(run())



def test_disable_during_listener_start_closes_new_server(tmp_path, monkeypatch):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        listener = _listener(tmp_path, host)
        serving = asyncio.Event()
        release_bind = asyncio.Event()
        events = []

        class Server:
            def close(self):
                events.append("close")
            async def wait_closed(self):
                events.append("wait_closed")

        async def delayed_serve(*args, **kwargs):
            serving.set()
            await release_bind.wait()
            return Server()

        monkeypatch.setattr(
            "emrg.server.companion_wss_listener.create_server_context",
            lambda cert, key: object(),
        )
        monkeypatch.setattr(
            "emrg.server.companion_wss_listener.serve", delayed_serve,
        )

        start_task = asyncio.create_task(listener.start())
        await asyncio.wait_for(serving.wait(), timeout=1)
        host.disable()
        # Let the scheduled host-state callback set the shutdown flag while
        # socket binding is still suspended.
        await asyncio.sleep(0)
        release_bind.set()

        with pytest.raises(CompanionTlsError, match="during listener startup"):
            await asyncio.wait_for(start_task, timeout=1)

        assert events == ["close", "wait_closed"]
        assert listener.running is False
        assert listener._closing is True
        listener._loop = None

    asyncio.run(run())
