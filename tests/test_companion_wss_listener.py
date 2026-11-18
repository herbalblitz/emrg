import asyncio
import json

import pytest

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_tls import CompanionTlsError
from emrg.server.companion_wss_listener import CompanionWssListener


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
