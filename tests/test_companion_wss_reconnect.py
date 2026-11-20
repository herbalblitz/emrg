"""Reconnect regression tests for the authenticated read-only companion transport."""

import asyncio
import json

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_wss_listener import CompanionWssListener


def test_same_paired_device_can_reconnect_with_fresh_nonce(tmp_path):
    async def run():
        host = CompanionHostService(tmp_path / "registry.json")
        host.enable()
        credential = host.registry.issue(
            device_id="android-reconnect",
            workspace_id="workspace-reconnect",
            cwd=str(tmp_path),
            scopes={"status"},
        )
        listener = CompanionWssListener(
            host,
            CompanionListenerConfig.build("127.0.0.1", 8765),
            tmp_path / "cert.pem",
            tmp_path / "key.pem",
        )

        class Socket:
            def __init__(self, nonce, request_id):
                self.frames = iter([
                    json.dumps({
                        "type": "auth",
                        "protocol": "1.0",
                        "device_id": "android-reconnect",
                        "credential": credential,
                        "client_nonce": nonce,
                    }),
                    json.dumps({"type": "status", "request_id": request_id}),
                ])
                self.sent = []

            async def recv(self):
                try:
                    return next(self.frames)
                except StopIteration:
                    raise asyncio.TimeoutError()

            async def send(self, data):
                assert isinstance(data, str)
                self.sent.append(json.loads(data))

            async def close(self, **kwargs):
                pass

        # Model a socket closing after the first authenticated session, then
        # the same paired Android device opening a new socket.
        first = Socket("nonce-android-reconnect-0001", "status-first")
        second = Socket("nonce-android-reconnect-0002", "status-second")

        for socket in (first, second):
            try:
                await listener._session(socket)
            except asyncio.TimeoutError:
                pass
            else:
                raise AssertionError("fake socket should end its receive loop")

        for socket, request_id in (
            (first, "status-first"),
            (second, "status-second"),
        ):
            assert socket.sent[0]["type"] == "auth.ok"
            assert socket.sent[0]["workspace_id"] == "workspace-reconnect"
            assert socket.sent[1] == {
                "type": "status.result",
                "request_id": request_id,
                "listener": "wss",
                "enabled": True,
            }

    asyncio.run(run())
