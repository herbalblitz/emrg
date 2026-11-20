"""Companion chat dispatch + per-session stream fan-out.

This module is the companion side of the daemon's chat path. It calls the
daemon **in-process** through the daemon object the listener was given
(``daemon_accessor``); it never opens a socket to the daemon and never
speaks the daemon socket protocol over loopback. ``emrg/server/daemon.py``
is not modified: the access below uses only the daemon's existing methods
and registries, reached through duck-typed attributes so tests can pass a
fake daemon.

How a turn reaches a companion device:

* The listener subscribes the companion websocket to the hub for the
  session (``CompanionStreamHub.subscribe``). The hub registers one sink
  object in the daemon's ``_session_subscribers`` for that session, keyed
  by the session's canonical cwd — the daemon's ``_broadcast`` already
  filters by (session, cwd), so the sink receives exactly the frames a
  local client in that session would receive.
* ``start_task`` replicates the daemon ``_handle_client`` ``task`` branch
  (session safety check, input-history filing, session tier resolution,
  one-active-task-per-session queue, ``_run_tool_loop_locked``). All turn
  output (verified at the daemon's ``_broadcast`` call sites) goes through
  ``_broadcast``: ``turn_start``, ``text`` (the text-chunk frame is
  ``{"type": "text", "text": chunk}``), ``turn_end``, ``tool_start``,
  ``tool_result``, ``tool_end``, ``approval_request``,
  ``approval_resolved``, ``cancelled``, ``task_queued``,
  ``queued_requeue``, ``queued_cancelled``, ``user_message`` and the
  terminal ``done`` frame. The tool loop never calls ``_send(ws, ...)``
  directly, so the ``ws`` argument is a capture sink for direct errors.
* The hub fans each broadcast frame out to every companion subscriber of
  the session, except tool-activity frames (``tool_start`` /
  ``tool_result`` / ``tool_end`` / ``function``), which reach only
  subscribers whose granted scopes include ``tools.monitor``.
* Replay: the hub keeps a bounded ring buffer of the session's stream
  frames (the gateway's ``since_seq`` replay is the reference pattern;
  the frozen companion contract carries no ``since_seq`` field, so a
  device that subscribes while a turn is already running receives the
  buffered frames of that turn from the start).

Audit logging follows the daemon's ``companion_local_control`` rule:
operation + session/pairing id, never payload contents.
"""
from __future__ import annotations

import asyncio
import json
import logging
from collections import deque
from pathlib import Path
from typing import Any

from emrg.protocol import TaskRequest
from emrg.server.companion_protocol import encode_server_frame
from emrg.server.companion_scopes import TOOL_ACTIVITY_TYPES

_LOG = logging.getLogger(__name__)

#: Daemon message types the companion listener may forward in-process.
#: Anything else (notably ``shutdown`` and ``init_auto_evolve``) is never
#: reachable from a remote companion. Batch B adds the memory / models /
#: rant / evolution / tasks / skills operations; Batch C (hosts / logs /
#: audit / devices / settings) needs companion-side handlers, not daemon
#: forwarding, so nothing from it appears here.
ALLOWED_DAEMON_TYPES: frozenset[str] = frozenset({
    "resume_session", "rename_session", "delete_session", "clear_session",
    "compact", "rewind_session", "list_history", "resolve_session_cwd",
    "list_files", "read_file", "set_sandbox", "set_sandbox_roots",
    "list_memories", "read_memory", "list_models", "set_model",
    "list_rants", "rant", "evolution_summary",
    "list_tasks", "trigger_task", "task_create", "task_update",
    "task_delete", "task_template_list", "task_template_create",
    "task_template_update", "task_template_delete",
    "list_skills", "skills_available", "skills_install", "skills_update",
    # Batch C — hosts.list reuses the daemon-native list_agents.
    "list_agents",
})

#: Stream frames buffered per session for replay to late subscribers.
MAX_REPLAY_FRAMES = 200


class CompanionDaemonError(ValueError):
    """Safe failure from an in-process daemon call (no paths or payloads)."""


class _CaptureSink:
    """Duck-typed daemon ``ws``: captures direct ``_send`` replies.

    The daemon's ``_send(ws, data)`` calls ``await ws.send(json.dumps(data))``;
    this sink records the parsed frames instead of putting them on a socket.
    """

    def __init__(self) -> None:
        self.frames: list[dict[str, Any]] = []
        # Present so daemon peer checks never see a missing attribute.
        self.remote_address = None

    async def send(self, payload: str) -> None:
        try:
            frame = json.loads(payload)
        except (TypeError, ValueError):
            return
        if isinstance(frame, dict):
            self.frames.append(frame)

    async def close(self, *args: Any, **kwargs: Any) -> None:
        return None


async def call_daemon_message(
    daemon: Any, message: dict[str, Any], *,
    session_id: str | None = None, cwd: str | None = None,
) -> list[dict[str, Any]]:
    """Run one daemon ``_process_message`` dispatch in-process and capture replies.

    The capture sink is temporarily registered as a daemon subscriber for the
    session (when one is named) so broadcast-only replies — ``compact``
    answers exclusively via ``_broadcast`` — are captured too. The
    subscription is removed before returning.
    """
    msg_type = message.get("type")
    if msg_type not in ALLOWED_DAEMON_TYPES:
        raise CompanionDaemonError("daemon operation not allowed")
    sink = _CaptureSink()
    subscribers = getattr(daemon, "_session_subscribers", None)
    registered = False
    if session_id and isinstance(subscribers, dict) and cwd:
        subscribers.setdefault(session_id, {})[sink] = cwd
        registered = True
    try:
        await daemon._process_message(dict(message), sink)
    except CompanionDaemonError:
        raise
    except Exception as exc:  # noqa: BLE001 — daemon internals must not leak
        raise CompanionDaemonError(
            f"daemon operation failed: {type(exc).__name__}") from None
    finally:
        if registered:
            subscribers.get(session_id, {}).pop(sink, None)
    return sink.frames


class _HubSink:
    """One daemon subscriber per session, forwarding broadcasts into the hub."""

    def __init__(self, hub: "CompanionStreamHub", session_id: str) -> None:
        self._hub = hub
        self.session_id = session_id
        self.remote_address = None

    async def send(self, payload: str) -> None:
        try:
            frame = json.loads(payload)
        except (TypeError, ValueError):
            return
        if isinstance(frame, dict):
            await self._hub._on_daemon_frame(self.session_id, frame)

    async def close(self, *args: Any, **kwargs: Any) -> None:
        return None


class _Subscriber:
    __slots__ = ("websocket", "scopes", "device_id")

    def __init__(self, websocket: Any, scopes: frozenset[str], device_id: str) -> None:
        self.websocket = websocket
        self.scopes = scopes
        self.device_id = device_id


class CompanionStreamHub:
    """Per-session stream subscriptions for companion websockets.

    The hub and the daemon share one event loop (the listener runs on the
    daemon's loop), so no locking is needed. A companion websocket is
    removed from every session when its connection closes; the daemon sink
    for a session is removed when its last companion subscriber leaves.
    """

    def __init__(self) -> None:
        self._subscribers: dict[str, set[_Subscriber]] = {}
        self._buffers: dict[str, deque[dict[str, Any]]] = {}
        self._daemon_sinks: dict[str, _HubSink] = {}

    def subscribed_sessions(self) -> list[str]:
        return sorted(self._subscribers)

    def sessions_for(self, websocket: Any) -> list[str]:
        """Sessions one companion connection is subscribed to, sorted."""
        return sorted(
            session_id for session_id, members in self._subscribers.items()
            if any(m.websocket is websocket for m in members))

    def buffered(self, session_id: str) -> list[dict[str, Any]]:
        return list(self._buffers.get(session_id, ()))

    async def subscribe(self, websocket: Any, *, session_id: str,
                        scopes: frozenset[str], device_id: str,
                        daemon: Any = None, cwd: str | None = None) -> None:
        """Subscribe one companion connection to a session's stream.

        Replays the buffered frames of a turn already in progress, then
        registers the daemon sink on first subscriber. ``daemon``/``cwd``
        are required for the daemon side; without them the subscription is
        companion-local only (used by tests that drive the hub directly).
        """
        subscriber = _Subscriber(websocket, scopes, device_id)
        members = self._subscribers.setdefault(session_id, set())
        members.add(subscriber)
        # Replay first: the device must see the turn from its start, not
        # from the moment it subscribed.
        for frame in self.buffered(session_id):
            if not await self._send_to(subscriber, frame):
                members.discard(subscriber)
                return
        if daemon is not None and session_id not in self._daemon_sinks:
            sink = _HubSink(self, session_id)
            subscribers = getattr(daemon, "_session_subscribers", None)
            if isinstance(subscribers, dict):
                subscribers.setdefault(session_id, {})[sink] = cwd or ""
                self._daemon_sinks[session_id] = sink

    async def unsubscribe(self, websocket: Any, *, daemon: Any = None) -> None:
        """Remove one companion connection from every session it joined."""
        for session_id in list(self._subscribers):
            members = self._subscribers[session_id]
            members = {m for m in members if m.websocket is not websocket}
            if members:
                self._subscribers[session_id] = members
                continue
            del self._subscribers[session_id]
            self._buffers.pop(session_id, None)
            sink = self._daemon_sinks.pop(session_id, None)
            if sink is not None and daemon is not None:
                subscribers = getattr(daemon, "_session_subscribers", None)
                if isinstance(subscribers, dict):
                    subscribers.get(session_id, {}).pop(sink, None)

    async def _on_daemon_frame(self, session_id: str, frame: dict[str, Any]) -> None:
        # The daemon keys broadcasts by session but does not stamp every
        # frame with it: tool_start/tool_end and the LLM-error terminal frame
        # carry no session_id of their own. Stamp it here so every streamed
        # frame a device receives carries session_id; request_id is forwarded
        # exactly where the daemon provides it and never invented.
        frame = dict(frame)
        frame.setdefault("session_id", session_id)
        buffer = self._buffers.setdefault(session_id, deque(maxlen=MAX_REPLAY_FRAMES))
        buffer.append(frame)
        for subscriber in list(self._subscribers.get(session_id, ())):
            if not await self._send_to(subscriber, frame):
                self._subscribers[session_id].discard(subscriber)
        if frame.get("type") == "turn_end":
            # The turn is over; a later subscriber starts from a clean slate
            # instead of replaying a finished turn.
            self._buffers.pop(session_id, None)

    async def _send_to(self, subscriber: _Subscriber, frame: dict[str, Any]) -> bool:
        """Send one stream frame to one subscriber; False when it is gone."""
        if (frame.get("type") in TOOL_ACTIVITY_TYPES
                and "tools.monitor" not in subscriber.scopes):
            return True
        try:
            raw = encode_server_frame(frame).decode("utf-8")
        except Exception:  # noqa: BLE001 — oversized stream frames are dropped
            _LOG.warning("companion stream frame dropped: oversized frame for session")
            return True
        try:
            await subscriber.websocket.send(raw)
        except Exception:  # noqa: BLE001 — a dead socket drops its subscription
            return False
        return True


async def start_task(
    daemon: Any, *, session_id: str, cwd: str, prompt: str,
    request_id: str | None = None, device_id: str = "",
) -> dict[str, Any]:
    """Start (or queue) one agent turn, mirroring the daemon's ``task`` branch.

    Returns ``{"started": True}`` or ``{"queued": True, "position": n}``.
    Raises :class:`CompanionDaemonError` on a refused or failed start.
    """
    _LOG.info("companion chat allowed: operation=%r session=%r device=%r",
              "chat.send", session_id, device_id)
    sink = _CaptureSink()
    refuse = getattr(daemon, "_refuse_an_unsafe_session_id", None)
    if callable(refuse) and await refuse(sink, "task", session_id):
        detail = ""
        for frame in sink.frames:
            if isinstance(frame.get("error"), str):
                detail = frame["error"]
        raise CompanionDaemonError(detail or "unsafe session id")
    # Lazy import: companion_chat must not import the daemon module at
    # import time — the daemon imports the companion runtime first, and the
    # tier resolver is defined after that import in daemon.py.
    from emrg.server.daemon import resolve_client_tier

    req = TaskRequest(
        id=request_id or "",
        session_id=session_id,
        cwd=cwd,
        prompt=prompt,
        timestamp="",
    )
    session = daemon._get_or_create_session(session_id, Path(cwd))
    session.append_input_history(req.prompt)
    req.sandbox = resolve_client_tier(session, req.sandbox)
    busy = getattr(daemon, "_session_busy", {})
    pending = getattr(daemon, "_session_pending", {})
    if busy.get(session_id):
        pending.setdefault(session_id, []).append((req, True))
        position = len(pending[session_id])
        await daemon._broadcast(session_id, {
            "type": "task_queued",
            "request_id": req.id,
            "session_id": session_id,
            "position": position,
        })
        return {"queued": True, "position": position}
    cancel_event = asyncio.Event()
    busy[session_id] = True
    task = asyncio.create_task(
        daemon._run_tool_loop_locked(req, sink, session, cancel_event, allow_tools=True))
    daemon._session_cancel[session_id] = cancel_event
    daemon._session_turn_task[session_id] = task
    return {"started": True}


async def cancel_turn(daemon: Any, *, session_id: str, device_id: str = "") -> bool:
    """Interrupt a session's running turn, mirroring the daemon's ``cancel`` branch.

    Returns True when a live turn was actually stopped. The turn task is
    never awaited here: the companion connection does not own it.
    """
    _LOG.info("companion chat allowed: operation=%r session=%r device=%r",
              "chat.cancel", session_id, device_id)
    event = getattr(daemon, "_session_cancel", {}).get(session_id)
    task = getattr(daemon, "_session_turn_task", {}).get(session_id)
    if event is not None:
        event.set()
    stopped = bool(task is not None and not task.done())
    if stopped:
        task.cancel()
        await daemon._broadcast(session_id, {
            "type": "cancelled",
            "session_id": session_id,
        })
    return stopped


def answer_approval(daemon: Any, *, approval_request_id: str,
                    approved: bool, device_id: str = "") -> bool:
    """Answer a pending tool approval, mirroring the daemon's ``approval_response`` branch."""
    _LOG.info("companion chat allowed: operation=%r session=%r device=%r",
              "chat.approve", approval_request_id, device_id)
    return bool(daemon._resolve_approval(
        {"request_id": approval_request_id, "approved": approved}))
