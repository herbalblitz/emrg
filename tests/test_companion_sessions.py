"""Tests for sessions.manage / files.read / sandbox.manage dispatch."""
import asyncio
import json

import pytest

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_wss_listener import CompanionWssListener


class FakeDaemon:
    """Minimal in-process daemon: records _process_message calls, replies."""

    def __init__(self):
        self.calls: list[dict] = []
        self._session_subscribers: dict = {}

    async def _process_message(self, msg, ws):
        self.calls.append(dict(msg))
        kind = msg["type"]
        if kind == "compact":
            # compact answers ONLY via broadcast — the capture sink is a
            # subscriber during the call, so this must still reach it.
            await self._broadcast(msg["session_id"], {
                "type": "compact_result", "session_id": msg["session_id"],
                "messages_compacted": 2,
            })
            return
        replies = {
            "resume_session": "resume_result",
            "rename_session": "rename_result",
            "delete_session": "session_deleted",
            "clear_session": "clear_result",
            "rewind_session": "rewind_result",
            "list_history": "history_list",
            "resolve_session_cwd": "session_cwd_result",
            "list_files": "files_list",
            "read_file": "file_content",
            "set_sandbox": "sandbox_set",
            "set_sandbox_roots": "sandbox_roots",
        }
        reply = {"type": replies[kind], "session_id": msg.get("session_id"),
                 "ok": True}
        if kind == "list_files":
            reply = {"type": "files_list", "path": msg["path"],
                     "entries": [{"name": "a.txt", "path": msg["path"] + "/a.txt",
                                  "type": "file"}],
                     "truncated": False}
        if kind == "read_file":
            reply = {"type": "file_content", "path": msg["path"],
                     "content": "hello\n", "truncated": False, "binary": False}
        if kind == "list_history":
            reply = {"type": "history_list", "session_id": msg["session_id"],
                     "records": [], "total": 0}
        await ws.send(json.dumps(reply))

    async def _broadcast(self, session_id, frame):
        for sink in list(self._session_subscribers.get(session_id, {})):
            await sink.send(json.dumps(frame))


class DummySocket:
    def __init__(self):
        self.sent = []

    async def send(self, data):
        self.sent.append(json.loads(data))

    async def close(self, **kwargs):
        pass


def _listener(tmp_path, daemon, scopes):
    host = CompanionHostService(tmp_path / "registry.json")
    config = CompanionListenerConfig.build("127.0.0.1", 8765)
    listener = CompanionWssListener(
        host, config, tmp_path / "cert.pem", tmp_path / "key.pem",
        daemon_accessor=lambda: daemon)
    identity = {"device_id": "phone-1", "workspace_id": "ws",
                "cwd": str(tmp_path), "scopes": set(scopes), "generation": 1}
    return listener, identity


def _dispatch(tmp_path, daemon, frame, scopes):
    listener, identity = _listener(tmp_path, daemon, scopes)
    return asyncio.run(listener._dispatch(frame, identity, DummySocket()))


def test_sessions_manage_dispatch_returns_typed_results(tmp_path):
    daemon = FakeDaemon()
    scopes = {"sessions.manage"}
    cases = [
        ("sessions.resume", {"session_id": "s-1"}, "resume_result"),
        ("sessions.rename", {"session_id": "s-1", "title": "New"}, "rename_result"),
        ("sessions.delete", {"session_id": "s-1"}, "session_deleted"),
        ("sessions.clear", {"session_id": "s-1"}, "clear_result"),
        ("sessions.compact", {"session_id": "s-1"}, "compact_result"),
        ("sessions.rewind", {"session_id": "s-1", "record_index": 2}, "rewind_result"),
        ("sessions.resolve_cwd", {"session_id": "s-1"}, "session_cwd_result"),
    ]
    for kind, extra, _ in cases:
        frame = {"type": kind, "request_id": "r-1", "workspace_id": "ws", **extra}
        result = _dispatch(tmp_path, daemon, frame, scopes)
        assert result["type"] == f"{kind}.result", result
        assert result["request_id"] == "r-1"
        assert result["workspace_id"] == "ws"
        assert result.get("ok") is True or "messages_compacted" in result
    # The daemon always receives the host-bound cwd, never a client value.
    assert all(call["cwd"] == str(tmp_path) for call in daemon.calls)
    assert daemon.calls[1]["title"] == "New"
    assert daemon.calls[5]["record_index"] == 2


def test_sessions_history_defaults_limit_server_side(tmp_path):
    daemon = FakeDaemon()
    frame = {"type": "sessions.history", "request_id": "r",
             "workspace_id": "ws", "session_id": "s-1"}
    result = _dispatch(tmp_path, daemon, frame, {"sessions.manage"})
    assert result["type"] == "sessions.history.result"
    assert daemon.calls[0]["limit"] == 100
    frame2 = {**frame, "limit": 25}
    _dispatch(tmp_path, daemon, frame2, {"sessions.manage"})
    assert daemon.calls[1]["limit"] == 25


def test_sessions_dispatch_without_daemon_is_unavailable(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    config = CompanionListenerConfig.build("127.0.0.1", 8765)
    listener = CompanionWssListener(
        host, config, tmp_path / "cert.pem", tmp_path / "key.pem")
    identity = {"device_id": "phone-1", "workspace_id": "ws",
                "cwd": str(tmp_path), "scopes": {"sessions.manage"},
                "generation": 1}
    result = asyncio.run(listener._dispatch(
        {"type": "sessions.resume", "request_id": "r", "workspace_id": "ws",
         "session_id": "s-1"}, identity, DummySocket()))
    assert result == {"type": "error", "request_id": "r",
                      "code": "daemon_unavailable"}


def test_sessions_mutating_ops_audit_log(tmp_path, caplog):
    import logging
    daemon = FakeDaemon()
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_wss_listener"):
        result = _dispatch(tmp_path, daemon,
                           {"type": "sessions.delete", "request_id": "r",
                            "workspace_id": "ws", "session_id": "s-1"},
                           {"sessions.manage"})
    assert result["type"] == "sessions.delete.result"
    assert "companion session allowed" in caplog.text
    assert "sessions.delete" in caplog.text
    assert "s-1" in caplog.text


def test_files_read_dispatch_relativizes_paths(tmp_path):
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "a.txt").write_text("hello\n")
    daemon = FakeDaemon()
    result = _dispatch(tmp_path, daemon,
                       {"type": "files.list", "request_id": "r",
                        "workspace_id": "ws", "path": "sub"},
                       {"files.read"})
    assert result["type"] == "files.list.result"
    assert result["path"] == "sub"
    assert result["entries"][0]["path"] == "sub/a.txt"
    # The daemon receives an absolute path inside the workspace.
    assert daemon.calls[0]["path"] == str(tmp_path / "sub")
    result = _dispatch(tmp_path, daemon,
                       {"type": "files.read", "request_id": "r",
                        "workspace_id": "ws", "path": "sub/a.txt"},
                       {"files.read"})
    assert result["type"] == "files.read.result"
    assert result["content"] == "hello\n"
    assert result["path"] == "sub/a.txt"


def test_files_read_rejects_workspace_escape(tmp_path):
    daemon = FakeDaemon()
    result = _dispatch(tmp_path, daemon,
                       {"type": "files.list", "request_id": "r",
                        "workspace_id": "ws", "path": "../outside"},
                       {"files.read"})
    assert result["type"] == "error"
    assert result["code"] == "policy_refused"
    assert daemon.calls == []


def test_sandbox_dispatch_and_root_containment(tmp_path):
    daemon = FakeDaemon()
    result = _dispatch(tmp_path, daemon,
                       {"type": "sandbox.set", "request_id": "r",
                        "workspace_id": "ws", "session_id": "s-1",
                        "mode": "read-only"},
                       {"sandbox.manage"})
    assert result["type"] == "sandbox.set.result"
    assert daemon.calls[0] == {"type": "set_sandbox", "session_id": "s-1",
                               "cwd": str(tmp_path), "mode": "read-only"}
    result = _dispatch(tmp_path, daemon,
                       {"type": "sandbox.roots", "request_id": "r",
                        "workspace_id": "ws", "session_id": "s-1",
                        "op": "add", "path": "sub"},
                       {"sandbox.manage"})
    assert result["type"] == "sandbox.roots.result"
    assert daemon.calls[1]["path"] == str(tmp_path / "sub")
    result = _dispatch(tmp_path, daemon,
                       {"type": "sandbox.roots", "request_id": "r",
                        "workspace_id": "ws", "session_id": "s-1",
                        "op": "add", "path": "/etc"},
                       {"sandbox.manage"})
    assert result["type"] == "error"
    assert result["code"] == "policy_refused"
