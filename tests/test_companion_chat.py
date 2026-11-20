"""Tests for companion chat dispatch, stream fan-out and approval flow."""
import asyncio
import json
import logging

import pytest

from emrg.server.companion_chat import (
    CompanionDaemonError,
    CompanionStreamHub,
    answer_approval,
    cancel_turn,
    start_task,
)
from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_wss_listener import CompanionWssListener


class FakeSession:
    def __init__(self, session_id, cwd):
        self.session_id = session_id
        self.cwd = cwd
        self.sandbox = None
        self.prompts = []

    def append_input_history(self, prompt):
        self.prompts.append(prompt)


class FakeDaemon:
    """Daemon stand-in: broadcast registry + turn machinery, no LLM."""

    def __init__(self, tmp_path):
        self._session_subscribers: dict = {}
        self._session_busy: dict = {}
        self._session_pending: dict = {}
        self._session_cancel: dict = {}
        self._session_turn_task: dict = {}
        self._session_task_cwds: dict = {}
        self._session_terminal_frame: dict = {}
        self._session_turn_started: dict = {}
        self._pending_approvals: dict = {}
        self.sessions: dict[str, FakeSession] = {}
        self.tmp_path = tmp_path
        self.turn_started = asyncio.Event()
        self.turn_gate = asyncio.Event()

    async def _broadcast(self, session_id, frame):
        subs = self._session_subscribers.get(session_id, {})
        task_cwd = self._session_task_cwds.get(session_id)
        targets = ([w for w, c in subs.items() if c == task_cwd]
                   if task_cwd else list(subs))
        for ws in targets:
            await ws.send(json.dumps(frame))

    async def _broadcast_all(self, frame, exclude=None):
        return None

    def _refuse_an_unsafe_session_id(self, ws, msg_type, session_id):
        async def check():
            return False
        return check()

    def _get_or_create_session(self, session_id, cwd):
        session = FakeSession(session_id, str(cwd))
        self.sessions[session_id] = session
        return session

    async def _run_tool_loop_locked(self, req, ws, session, cancel_event,
                                    allow_tools=True):
        session_id = session.session_id
        self._session_task_cwds[session_id] = str(session.cwd)
        await self._broadcast(session_id, {
            "type": "turn_start", "session_id": session_id,
            "started_at": 1.0, "sandbox": req.sandbox})
        self.turn_started.set()
        await self.turn_gate.wait()
        await self._broadcast(session_id, {
            "request_id": req.id, "content": "hel", "done": False,
            "delta": True, "session_id": session_id})
        # tool_start carries NO session_id from the daemon (verified in
        # daemon.py) — the hub must stamp it.
        await self._broadcast(session_id, {
            "type": "tool_start", "request_id": req.id,
            "tool_name": "read", "tool_call_id": "tc-1",
            "arguments": {}, "intent": "", "call_label": "read"})
        await self._broadcast(session_id, {
            "request_id": req.id, "content": "lo", "done": False,
            "delta": True, "session_id": session_id})
        await self._broadcast(session_id, {
            "request_id": req.id, "content": "hello", "done": True,
            "delta": False, "session_id": session_id})
        await self._broadcast(session_id, {
            "type": "turn_end", "session_id": session_id})
        self._session_task_cwds.pop(session_id, None)
        self._session_busy[session_id] = False

    def _resolve_approval(self, data):
        pending = self._pending_approvals.get(data.get("request_id"))
        if pending is None:
            return False
        future, _ = pending
        if future.done():
            return True
        future.set_result(bool(data.get("approved")))
        return True


class Socket:
    def __init__(self):
        self.frames: list[dict] = []

    async def send(self, data):
        self.frames.append(json.loads(data))

    async def close(self, **kwargs):
        pass


async def _run_turn(daemon, hub, socket_a, socket_b):
    """Start a turn on socket A, subscribe B mid-turn, let it finish."""
    await hub.subscribe(socket_a, session_id="s-1",
                        scopes=frozenset({"chat.send", "tools.monitor"}),
                        device_id="phone-a", daemon=daemon, cwd=str(daemon.tmp_path))
    outcome = await start_task(
        daemon, session_id="s-1", cwd=str(daemon.tmp_path),
        prompt="hello", request_id="req-1", device_id="phone-a")
    assert outcome == {"started": True}
    await asyncio.wait_for(daemon.turn_started.wait(), timeout=5)
    await hub.subscribe(socket_b, session_id="s-1",
                        scopes=frozenset({"chat.send"}),
                        device_id="phone-b", daemon=daemon, cwd=str(daemon.tmp_path))
    daemon.turn_gate.set()
    task = daemon._session_turn_task["s-1"]
    await asyncio.wait_for(task, timeout=5)
    return outcome


def test_chat_send_dispatch_streams_to_subscriber(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        hub = CompanionStreamHub()
        socket_a, socket_b = Socket(), Socket()
        await _run_turn(daemon, hub, socket_a, socket_b)
        # A (chat + tools.monitor) sees everything, in order.
        assert socket_a.frames[0]["type"] == "turn_start"
        deltas = [f for f in socket_a.frames if f.get("delta") is True]
        assert [f["content"] for f in deltas] == ["hel", "lo"]
        assert any(f.get("done") is True for f in socket_a.frames)
        assert socket_a.frames[-1]["type"] == "turn_end"
        # Every streamed frame carries session_id (the hub stamps the ones
        # the daemon sends without it, e.g. tool_start).
        assert all(f.get("session_id") == "s-1" for f in socket_a.frames)
        tool = next(f for f in socket_a.frames if f.get("type") == "tool_start")
        assert tool["session_id"] == "s-1"
        assert tool["request_id"] == "req-1"
        # B joined mid-turn without tools.monitor: replayed the turn from
        # its start, but never sees the tool frame.
        assert socket_b.frames[0]["type"] == "turn_start"
        assert not any(f.get("type") == "tool_start" for f in socket_b.frames)
        assert any(f.get("done") is True for f in socket_b.frames)
        assert socket_b.frames[-1]["type"] == "turn_end"
        # The prompt was filed as recallable input history.
        assert daemon.sessions["s-1"].prompts == ["hello"]
    asyncio.run(run())


def test_chat_send_queues_when_session_busy(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        daemon._session_busy["s-1"] = True
        hub = CompanionStreamHub()
        socket = Socket()
        await hub.subscribe(socket, session_id="s-1",
                            scopes=frozenset({"chat.send"}),
                            device_id="phone-a", daemon=daemon, cwd=str(tmp_path))
        outcome = await start_task(
            daemon, session_id="s-1", cwd=str(tmp_path),
            prompt="second", request_id="req-2", device_id="phone-a")
        assert outcome == {"queued": True, "position": 1}
        queued = [f for f in socket.frames if f.get("type") == "task_queued"]
        assert queued and queued[0]["request_id"] == "req-2"
    asyncio.run(run())


def test_chat_cancel_stops_live_turn_and_reports(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        hub = CompanionStreamHub()
        socket = Socket()
        await hub.subscribe(socket, session_id="s-1",
                            scopes=frozenset({"chat.send"}),
                            device_id="phone-a", daemon=daemon, cwd=str(tmp_path))
        daemon._session_turn_task["s-1"] = asyncio.create_task(asyncio.sleep(60))
        daemon._session_cancel["s-1"] = asyncio.Event()
        stopped = await cancel_turn(daemon, session_id="s-1", device_id="phone-a")
        assert stopped is True
        await asyncio.sleep(0)  # let the cancelled sleep task finish winding down
        assert daemon._session_turn_task["s-1"].cancelled()
        assert daemon._session_cancel["s-1"].is_set()
        assert {"type": "cancelled", "session_id": "s-1"} in socket.frames
        # Nothing live to stop any more.
        assert await cancel_turn(daemon, session_id="s-1") is False
    asyncio.run(run())


def test_chat_approve_round_trip(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        loop = asyncio.get_running_loop()
        future = loop.create_future()
        daemon._pending_approvals["appr-1"] = (future, "s-1")
        assert answer_approval(daemon, approval_request_id="appr-1",
                               approved=True, device_id="phone-a") is True
        assert await future is True
        assert answer_approval(daemon, approval_request_id="appr-missing",
                               approved=True) is False
    asyncio.run(run())


def test_hub_replay_buffer_clears_at_turn_end(tmp_path):
    async def run():
        hub = CompanionStreamHub()
        socket = Socket()
        await hub.subscribe(socket, session_id="s-1",
                            scopes=frozenset({"chat.send"}), device_id="d")
        await hub._on_daemon_frame("s-1", {"type": "turn_start", "session_id": "s-1"})
        await hub._on_daemon_frame("s-1", {"request_id": "r", "content": "x",
                                           "done": False, "delta": True,
                                           "session_id": "s-1"})
        assert len(hub.buffered("s-1")) == 2
        await hub._on_daemon_frame("s-1", {"type": "turn_end", "session_id": "s-1"})
        assert hub.buffered("s-1") == []
        # A late subscriber after turn_end gets nothing replayed.
        late = Socket()
        await hub.subscribe(late, session_id="s-1",
                            scopes=frozenset({"chat.send"}), device_id="d2")
        assert late.frames == []
    asyncio.run(run())


def test_chat_dispatch_via_listener(tmp_path, caplog):
    async def run():
        daemon = FakeDaemon(tmp_path)
        host = CompanionHostService(tmp_path / "registry.json")
        config = CompanionListenerConfig.build("127.0.0.1", 8765)
        listener = CompanionWssListener(
            host, config, tmp_path / "cert.pem", tmp_path / "key.pem",
            daemon_accessor=lambda: daemon)
        identity = {"device_id": "phone-1", "workspace_id": "ws",
                    "cwd": str(tmp_path),
                    "scopes": {"chat.send", "tools.monitor"}, "generation": 1}
        socket = Socket()
        with caplog.at_level(logging.INFO, logger="emrg.server.companion_chat"):
            result = await listener._dispatch(
                {"type": "chat.send", "request_id": "req-9",
                 "workspace_id": "ws", "session_id": "s-1", "prompt": "hi"},
                identity, socket)
        assert result["type"] == "chat.send.result"
        assert result["started"] is True
        assert result["session_id"] == "s-1"
        assert "companion chat allowed" in caplog.text
        # No session_id on chat.send is refused at dispatch (the protocol
        # layer rejects it earlier; this is the defensive second gate).
        result = await listener._dispatch(
            {"type": "chat.send", "request_id": "req-10",
             "workspace_id": "ws", "session_id": None, "prompt": "hi"},
            identity, socket)
        assert result["type"] == "error"
        assert result["request_id"] == "req-10"
        assert result["code"] == "session_required"
        # chat.cancel without session_id cancels this connection's turns.
        result = await listener._dispatch(
            {"type": "chat.cancel", "request_id": "req-11",
             "workspace_id": "ws"},
            identity, socket)
        assert result["type"] == "chat.cancel.result"
        assert result["stopped"] is True
        assert listener._hub.sessions_for(socket) == ["s-1"]
        await listener._hub.unsubscribe(socket, daemon=daemon)
    asyncio.run(run())



def test_hub_rejects_same_session_id_from_different_workspaces(tmp_path):
    async def run():
        hub = CompanionStreamHub()
        first, second = Socket(), Socket()
        workspace_a = tmp_path / "workspace-a"
        workspace_b = tmp_path / "workspace-b"
        await hub.subscribe(first, session_id="collision",
                            scopes=frozenset({"chat.send"}), device_id="phone-a",
                            cwd=str(workspace_a))
        with pytest.raises(CompanionDaemonError, match="another workspace"):
            await hub.subscribe(second, session_id="collision",
                                scopes=frozenset({"chat.send"}), device_id="phone-b",
                                cwd=str(workspace_b))
        assert hub.sessions_for(first) == ["collision"]
        assert hub.sessions_for(second) == []
    asyncio.run(run())


def test_chat_cancel_cannot_target_unsubscribed_session(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        host = CompanionHostService(tmp_path / "registry.json")
        listener = CompanionWssListener(
            host, CompanionListenerConfig.build("127.0.0.1", 8765),
            tmp_path / "cert.pem", tmp_path / "key.pem",
            daemon_accessor=lambda: daemon)
        socket = Socket()
        # The connection has not subscribed to "victim".
        daemon._session_turn_task["victim"] = asyncio.create_task(asyncio.sleep(60))
        daemon._session_cancel["victim"] = asyncio.Event()
        identity = {"device_id": "phone-a", "workspace_id": "ws-a",
                    "cwd": str(tmp_path), "scopes": {"chat.send"}}
        result = await listener._dispatch(
            {"type": "chat.cancel", "request_id": "cancel-1",
             "workspace_id": "ws-a", "session_id": "victim"}, identity, socket)
        assert result["code"] == "session_not_subscribed"
        assert not daemon._session_cancel["victim"].is_set()
        daemon._session_turn_task["victim"].cancel()
    asyncio.run(run())


def test_chat_approval_cannot_target_unsubscribed_session(tmp_path):
    async def run():
        daemon = FakeDaemon(tmp_path)
        future = asyncio.get_running_loop().create_future()
        daemon._pending_approvals["approval-victim"] = (future, "victim")
        host = CompanionHostService(tmp_path / "registry.json")
        listener = CompanionWssListener(
            host, CompanionListenerConfig.build("127.0.0.1", 8765),
            tmp_path / "cert.pem", tmp_path / "key.pem",
            daemon_accessor=lambda: daemon)
        identity = {"device_id": "phone-a", "workspace_id": "ws-a",
                    "cwd": str(tmp_path), "scopes": {"chat.send"}}
        result = await listener._dispatch(
            {"type": "chat.approve", "request_id": "approve-1",
             "workspace_id": "ws-a", "approval_request_id": "approval-victim",
             "approved": True}, identity, Socket())
        assert result["code"] == "approval_not_for_subscribed_session"
        assert not future.done()
    asyncio.run(run())
