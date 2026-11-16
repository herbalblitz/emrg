"""The gateway protocol over a real TLS socket.

A fake daemon link stands in for the engine: these tests are about the
*handshake and dispatch* — pairing, token auth, API-version refusal, the proxy
allow-list, blocked host-lifecycle frames, and the gateway's own file/status
operations. The daemon link's own buffering and replay are tested against a real
daemon where one is available.
"""

from __future__ import annotations

import asyncio
import json
import ssl

from websockets.asyncio.client import connect

from emrg.gateway.config import GatewayConfig, GatewayPaths
from emrg.gateway.daemon_link import LinkSnapshot
from emrg.gateway.identity import _token_hash
from emrg.gateway.server import GatewayServer


class FakeLink:
    engine_version = "0.3.55"

    def __init__(self) -> None:
        self.sent: list[dict] = []
        self.queue = None
        self.closed = False
        self.active = False
        self.attached = 0

    async def ensure_version(self, timeout: float = 1.0) -> str:
        return self.engine_version

    def attach(self, queue, since_seq: int = 0) -> int:
        self.queue = queue
        self.attached += 1
        return 0

    def detach(self, queue) -> None:
        self.attached -= 1

    async def send(self, value: dict) -> None:
        self.sent.append(value)

    async def close(self) -> None:
        self.closed = True

    def snapshot(self) -> LinkSnapshot:
        return LinkSnapshot(
            device_id="fake",
            seq=7,
            attached=self.attached,
            active=False,
            closed=self.closed,
            last_activity=0.0,
            engine_version=self.engine_version,
        )


def _client_ssl() -> ssl.SSLContext:
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


async def _recv(ws) -> dict:
    return json.loads(await asyncio.wait_for(ws.recv(), 5))


async def _return(value):
    return value


def _server(tmp_path, fake: FakeLink | None = None) -> GatewayServer:
    paths = GatewayPaths.under(tmp_path / "gateway")
    paths.ensure()
    server = GatewayServer(GatewayConfig(host="127.0.0.1", port=0), paths)
    if fake is not None:
        server.links.get_or_create = lambda device_id: _return(fake)
        server.links.snapshot = lambda: [fake.snapshot()]
    return server


def test_pairing_issues_a_token_once_and_refuses_a_wrong_code(tmp_path):
    server = _server(tmp_path)
    ssl_ctx = _client_ssl()

    async def scenario():
        await server.start()
        port = server._server.sockets[0].getsockname()[1]
        try:
            issued = server.devices.issue_pairing_code()
            wrong = "00000000" if issued["code"] != "00000000" else "11111111"
            async with connect(f"wss://127.0.0.1:{port}/v1/pair", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({"type": "gw_pair", "code": wrong, "device_name": "Pixel"}))
                answer = await _recv(ws)
                assert answer["type"] == "gw_error"
                assert answer["code"] == "pairing_rejected"

            # The wrong attempt counted against the previous code; asking again
            # for a fresh code is the client-visible result.
            issued = server.devices.issue_pairing_code()
            async with connect(f"wss://127.0.0.1:{port}/v1/pair", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({
                    "type": "gw_pair", "code": issued["code"],
                    "device_name": "Pixel 9", "app_version": "1.0.0",
                }))
                answer = await _recv(ws)
            assert answer["type"] == "gw_pair_ok"
            assert answer["token"].startswith("emrgd_")
            assert answer["device"]["name"] == "Pixel 9"
            stored = (tmp_path / "gateway" / "devices.json").read_text(encoding="utf-8")
            assert answer["token"] not in stored
            assert _token_hash(answer["token"]) in stored
        finally:
            await server.close()

    asyncio.run(scenario())


def test_auth_refuses_an_unknown_token_and_a_newer_api(tmp_path):
    server = _server(tmp_path)
    ssl_ctx = _client_ssl()

    async def scenario():
        await server.start()
        port = server._server.sockets[0].getsockname()[1]
        try:
            async with connect(f"wss://127.0.0.1:{port}/v1/ws", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({"type": "gw_auth", "token": "emrgd_nope", "api_version": "1.0"}))
                answer = await _recv(ws)
                assert answer["type"] == "gw_error"
                assert answer["code"] == "auth_failed"

            async with connect(f"wss://127.0.0.1:{port}/v1/ws", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({"type": "gw_auth", "token": "emrgd_nope", "api_version": "2.0"}))
                answer = await _recv(ws)
                assert answer["code"] == "api_version"
        finally:
            await server.close()

    asyncio.run(scenario())


def test_an_authenticated_session_pings_reads_files_and_blocks_shutdown(tmp_path):
    fake = FakeLink()
    server = _server(tmp_path, fake)
    ssl_ctx = _client_ssl()
    work = tmp_path / "work"
    work.mkdir()
    target = work / "notes.md"
    target.write_text("line one\nline two\n", encoding="utf-8")

    async def scenario():
        await server.start()
        port = server._server.sockets[0].getsockname()[1]
        try:
            issued = server.devices.issue_pairing_code()
            device, token = server.devices.pair(issued["code"], "Pixel")
            async with connect(f"wss://127.0.0.1:{port}/v1/ws", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({
                    "type": "gw_auth", "token": token, "api_version": "1.0",
                    "device_id": device.id, "since_seq": 0, "app_version": "1.0.0",
                }))
                hello = await _recv(ws)
                attached = await _recv(ws)
                assert hello["type"] == "gw_hello_ok"
                assert hello["engine_version"] == "0.3.55"
                assert "terminal" in hello["capabilities"]
                assert attached["type"] == "gw_attached"

                await ws.send(json.dumps({"type": "gw_ping", "rid": "r1"}))
                pong = await _recv(ws)
                assert pong["type"] == "gw_pong"
                assert pong["rid"] == "r1"

                await ws.send(json.dumps({
                    "type": "gw_read_file", "rid": "r2", "path": str(target),
                    "start_line": 2, "line_limit": 1,
                }))
                content = await _recv(ws)
                assert content["type"] == "gw_file_content"
                assert content["content"] == "line two"
                assert content["truncated"] is False

                await ws.send(json.dumps({"type": "gw_status", "rid": "r3"}))
                status = await _recv(ws)
                assert status["type"] == "gw_status"
                assert status["devices"] == 1

                await ws.send(json.dumps({"type": "ping", "rid": "r4"}))
                await asyncio.sleep(0.05)
                assert fake.sent and fake.sent[-1]["type"] == "ping"

                await ws.send(json.dumps({"type": "shutdown", "rid": "r5"}))
                refused = await _recv(ws)
                assert refused["type"] == "gw_error"
                assert refused["code"] == "forbidden"
        finally:
            await server.close()

    asyncio.run(scenario())


def test_a_revoked_device_token_stops_working(tmp_path):
    fake = FakeLink()
    server = _server(tmp_path, fake)
    ssl_ctx = _client_ssl()

    async def scenario():
        await server.start()
        port = server._server.sockets[0].getsockname()[1]
        try:
            first = server.devices.issue_pairing_code()
            admin, admin_token = server.devices.pair(first["code"], "admin")
            second = server.devices.issue_pairing_code()
            victim, victim_token = server.devices.pair(second["code"], "victim")
            async with connect(f"wss://127.0.0.1:{port}/v1/ws", ssl=ssl_ctx) as ws:
                await ws.send(json.dumps({"type": "gw_auth", "token": admin_token,
                                          "api_version": "1.0", "device_id": admin.id}))
                await _recv(ws)
                await _recv(ws)
                await ws.send(json.dumps({"type": "gw_revoke_device", "rid": "x",
                                          "device_id": victim.id}))
                answer = await _recv(ws)
                assert answer["type"] == "gw_device_revoked"
                assert answer["revoked"] is True
            assert server.devices.authenticate(victim_token) is None
        finally:
            await server.close()

    asyncio.run(scenario())
