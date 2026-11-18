import asyncio

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

    
@pytest.mark.asyncio
async def test_disabling_host_closes_registered_connections(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    listener = _listener(tmp_path, host)
    listener._loop = asyncio.get_running_loop()

    class Client:
        def __init__(self):
            self.closed = []
        async def close(self, *, code, reason):
            self.closed.append((code, reason))

    client = Client()
    listener._clients.add(client)
    host.enable()
    host.disable()
    await asyncio.sleep(0)
    assert client.closed == [(1008, "Companion disabled")]
    assert client in listener._clients  # handler owns removal; close wakes recv
    listener._clients.clear()
    listener._loop = None
