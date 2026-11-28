"""Dedicated opt-in WSS transport for EMRG Companion.

Authenticated operations: status, session listing, session management,
chat (with per-session stream fan-out), file reads, sandbox management,
terminal execution, file writes/deletes, the Batch B screens
(memory, models, rant, evolution, tasks, skills) dispatched to the daemon
in-process, and the Batch C screens (hosts via daemon ``list_agents``;
logs/audit/devices/settings via the additive ``companion_logs`` /
``companion_audit`` / ``companion_devices`` / ``companion_config``
modules). Host-lifecycle operations (``shutdown``, ``init_auto_evolve``)
are never reachable from a remote companion.

Daemon-native operations run in-process through the daemon object supplied
via ``daemon_accessor``; the listener never opens a socket to the daemon.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import unicodedata
from typing import Any

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from emrg.server.companion_audit import CompanionAuditError
from emrg.server.companion_audit import tail as companion_audit_tail
from emrg.server.companion_chat import (
    CompanionDaemonError,
    CompanionStreamHub,
    answer_approval,
    call_daemon_message,
    cancel_turn,
    start_task,
)
from emrg.server.companion_config import (
    CompanionConfigError,
    apply_companion_patch,
    apply_patch as companion_apply_settings_patch,
)
from emrg.server.companion_config import snapshot as companion_settings_snapshot
from emrg.server.companion_devices import (
    CompanionDevicesError,
    list_devices as companion_list_devices,
    revoke_device as companion_revoke_device,
)
from emrg.server.companion_exec import (
    CompanionExecConfig,
    CompanionExecError,
    ExecManager,
)
from emrg.server.companion_logs import CompanionLogsError
from emrg.server.companion_logs import tail as companion_logs_tail
from emrg.server.companion_files import (
    CompanionFileError,
    delete as companion_delete_file,
    resolve_under_root,
    write as companion_write_file,
)
from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_pairing import PairingError
from emrg.server.companion_pairing_v2 import PairingV2Error
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_protocol import (
    CompanionProtocolError, encode_server_frame, validate_client_frame,
    validate_pairing_v2_frame,
)
from emrg.server.companion_tls import create_server_context, CompanionTlsError
from emrg.server.companion_tls_identity import create_identity_server_context
from emrg.session import Session
from pathlib import Path

_LOG = logging.getLogger(__name__)
_MAX_REMOTE_SESSIONS = 200
_SESSION_TEXT_LIMITS = {"session_id": 128, "created_at": 64, "updated_at": 64, "title": 256}
_PAIRING_RATE_WINDOW_SECONDS = 60.0
_PAIRING_RATE_MAX_ATTEMPTS = 10
_PAIRING_RATE_MAX_PEERS = 1024

# Companion frame -> (daemon message type, expected daemon reply type).
_SESSION_DAEMON_OPS: dict[str, tuple[str, str]] = {
    "sessions.resume": ("resume_session", "resume_result"),
    "sessions.rename": ("rename_session", "rename_result"),
    "sessions.delete": ("delete_session", "session_deleted"),
    "sessions.clear": ("clear_session", "clear_result"),
    "sessions.compact": ("compact", "compact_result"),
    "sessions.rewind": ("rewind_session", "rewind_result"),
    "sessions.history": ("list_history", "history_list"),
    "sessions.resolve_cwd": ("resolve_session_cwd", "session_cwd_result"),
}

#: Session operations that mutate state (audit-logged; reads are not).
_SESSION_MUTATING_OPS: frozenset[str] = frozenset({
    "sessions.resume", "sessions.rename", "sessions.delete",
    "sessions.clear", "sessions.compact", "sessions.rewind",
})

# Batch B — companion frame -> (daemon message type, expected daemon
# reply type). ``rant`` is the one daemon op whose reply carries no
# ``type`` field (``{"ok": ..., "count": ...}``), so its reply type is
# None and the first captured frame is the answer.
_BATCH_B_DAEMON_OPS: dict[str, tuple[str, str | None]] = {
    "memory.list": ("list_memories", "memories_list"),
    "memory.read": ("read_memory", "memory_content"),
    "models.list": ("list_models", "models_list"),
    "models.set": ("set_model", "model_set"),
    "rant.list": ("list_rants", "rants_list"),
    "rant.submit": ("rant", None),
    "evolution.summary": ("evolution_summary", "evolution_summary"),
    "tasks.list": ("list_tasks", "tasks_list"),
    "tasks.trigger": ("trigger_task", "trigger_result"),
    "tasks.create": ("task_create", "task_result"),
    "tasks.update": ("task_update", "task_result"),
    "tasks.delete": ("task_delete", "task_result"),
    "tasks.templates": ("task_template_list", "templates_list"),
    "tasks.template_create": ("task_template_create", "template_result"),
    "tasks.template_update": ("task_template_update", "template_result"),
    "tasks.template_delete": ("task_template_delete", "template_result"),
    "skills.list": ("list_skills", "skills_list"),
    "skills.available": ("skills_available", "skills_available_result"),
    "skills.install": ("skills_install", "skills_install_result"),
    "skills.update": ("skills_update", "skills_update_result"),
}

#: Batch B operations that mutate state (audit-logged; reads are not).
#: ``tasks.*`` create/update/delete fail cleanly when the scheduler is not
#: running — the daemon's error is surfaced, never masked.
_BATCH_B_MUTATING_OPS: frozenset[str] = frozenset({
    "models.set", "rant.submit",
    "tasks.trigger", "tasks.create", "tasks.update", "tasks.delete",
    "tasks.template_create", "tasks.template_update", "tasks.template_delete",
    "skills.install", "skills.update",
})

#: The daemon's whole vocabulary for a task record (daemon.py
#: ``task_create``/``task_update``). The companion ``tasks.create`` /
#: ``tasks.update`` frames carry one nested ``task`` dict; only these keys
#: are flattened into the daemon message — anything else the client put in
#: the dict never reaches the daemon.
_TASK_FIELD_ALLOWLIST: frozenset[str] = frozenset({
    "name", "task_type", "agent", "interval", "enabled", "repo",
    "description", "sandbox", "schedule", "when",
})

#: Default history page when the client leaves ``limit`` unset (the daemon
#: answers the whole history for limit=None, which would blow the 256 KiB
#: frame cap on a long session).
DEFAULT_HISTORY_LIMIT = 100

#: Batch C operations dispatched by ``_dispatch_batch_c``. ``hosts.list``
#: is daemon-native (``list_agents``); the rest are companion-side modules.
_BATCH_C_OPS: frozenset[str] = frozenset({
    "hosts.list", "logs.tail", "audit.tail",
    "devices.list", "devices.revoke", "settings.get", "settings.set",
})

#: Maximum agents returned by hosts.list (same bound as sessions.list).
_MAX_REMOTE_AGENTS = 200


def _error(request_id: str | None, code: str, detail: str | None = None) -> dict[str, Any]:
    frame: dict[str, Any] = {"type": "error", "request_id": request_id, "code": code}
    if detail:
        frame["error"] = detail[:200]
    return frame


def _safe_remote_text(value: str, limit: int) -> bool:
    """Reject display-control and invalid Unicode before metadata reaches clients."""
    return not any(unicodedata.category(char) in {"Cc", "Cf", "Cs"}
                   for char in value[:limit])


def _encode_wss_text_frame(frame: dict[str, Any]) -> str:
    """Encode JSON as a WebSocket text frame, not a binary frame.

    The shared encoder returns bounded UTF-8 bytes; websockets treats bytes as
    binary messages, which the Android Companion client intentionally rejects.
    """
    return encode_server_frame(frame).decode("utf-8")


def _remote_session_metadata(records: object) -> list[dict[str, Any]]:
    """Expose a bounded metadata allowlist; never forward local paths or raw meta."""
    if not isinstance(records, list):
        return []
    safe: list[dict[str, Any]] = []
    for record in records[:_MAX_REMOTE_SESSIONS]:
        if not isinstance(record, dict):
            continue
        item: dict[str, Any] = {}
        for key, limit in _SESSION_TEXT_LIMITS.items():
            value = record.get(key)
            if isinstance(value, str) and _safe_remote_text(value, limit):
                item[key] = value[:limit]
        count = record.get("message_count")
        if (isinstance(count, int) and not isinstance(count, bool)
                and 0 <= count <= 2**31 - 1):
            item["message_count"] = count
        safe.append(item)
    return safe


class CompanionWssListener:
    """Lifecycle-managed bounded WSS server; must be explicitly started."""

    def __init__(self, host_service: CompanionHostService,
                 config: CompanionListenerConfig, cert_file=None, key_file=None, *,
                 identity_directory=None, daemon_accessor=None,
                 exec_config: CompanionExecConfig | None = None):
        """Create a listener with a protected identity directory or explicit PEM paths.

        Production integrations should pass identity_directory so Windows DPAPI
        keys are unwrapped only for the in-memory TLS context. The explicit
        certificate/key pair remains available for isolated tests and existing
        callers; mixing the two forms is rejected.

        ``daemon_accessor`` is an optional callable returning the running
        daemon instance (or None) — the narrow in-process accessor for
        daemon-native operations. Without it, daemon-backed frames answer
        ``daemon_unavailable``; the listener never opens a socket to the
        daemon. ``exec_config`` is the host-side terminal/file policy
        (danger opt-ins); the default denies danger for every device.
        """
        if identity_directory is not None:
            if cert_file is not None or key_file is not None:
                raise ValueError("choose identity_directory or cert_file/key_file")
        elif cert_file is None or key_file is None:
            raise ValueError("TLS identity directory or both certificate/key paths required")
        if daemon_accessor is not None and not callable(daemon_accessor):
            raise TypeError("daemon_accessor must be callable")
        self._host = host_service
        self._config = config
        self._cert_file = cert_file
        self._key_file = key_file
        self._identity_directory = identity_directory
        self._daemon_accessor = daemon_accessor
        self._exec_config = exec_config or CompanionExecConfig()
        self._exec_manager = ExecManager()
        self._hub = CompanionStreamHub()
        self._server = None
        self._starting = False
        self._semaphore = asyncio.Semaphore(config.max_connections)
        self._active = 0
        self._closing = False
        self._disposed = False
        self._loop: asyncio.AbstractEventLoop | None = None
        self._clients: set[Any] = set()
        self._authenticated_clients: dict[Any, str] = {}
        self._pairing_attempts: dict[str, list[float]] = {}
        self._host.add_state_callback(self._on_host_state)
        self._host.add_device_change_callback(self._on_device_identity_changed)

    @property
    def running(self) -> bool:
        return self._server is not None

    @property
    def active_connections(self) -> int:
        return self._active

    async def start(self) -> None:
        if self._disposed:
            raise RuntimeError("companion listener has been disposed")
        if self.running or self._starting:
            raise RuntimeError("companion listener already running or starting")
        if not self._host.enabled:
            raise CompanionTlsError("companion disabled")
        self._starting = True
        try:
            tls = (create_identity_server_context(self._identity_directory)
                   if self._identity_directory is not None
                   else create_server_context(self._cert_file, self._key_file))
            self._closing = False
            self._loop = asyncio.get_running_loop()
            server = await serve(
                self._handle,
                self._config.bind_address,
                self._config.port,
                ssl=tls,
                max_size=256 * 1024,
                max_queue=4,
                open_timeout=self._config.handshake_timeout_seconds,
                ping_interval=20,
                ping_timeout=20,
                close_timeout=self._config.close_timeout_seconds,
            )
            # Host policy may be disabled (or stop() called) while serve() is
            # awaiting socket startup. Do not publish a newly-bound server after
            # that shutdown signal has already been observed.
            if self._closing or not self._host.enabled:
                server.close()
                await server.wait_closed()
                raise CompanionTlsError("companion disabled during listener startup")
            self._server = server
        finally:
            self._starting = False

    async def stop(self) -> None:
        """Stop accepting, close clients, and wait boundedly for handlers."""
        await self._shutdown(code=1001, reason="Listener stopped")
        self._loop = None

    async def close(self) -> None:
        """Permanently dispose this listener and unregister its host callback."""
        if self._disposed:
            return
        self._disposed = True
        try:
            await self.stop()
        finally:
            self._host.remove_state_callback(self._on_host_state)
            self._host.remove_device_change_callback(self._on_device_identity_changed)

    async def _shutdown(self, *, code: int, reason: str) -> None:
        server, self._server = self._server, None
        self._closing = True
        await self._close_clients(code=code, reason=reason)
        if server is not None:
            server.close()
            await server.wait_closed()

    def _on_host_state(self, enabled: bool) -> None:
        """Schedule connection closure promptly when local policy is disabled."""
        if enabled or self._loop is None or not self._loop.is_running():
            return
        def shutdown_if_still_disabled() -> None:
            # Host transitions notify outside the host lock. A rapid
            # disable->enable can therefore queue this callback after the host
            # is already enabled again; ignore that stale notification.
            if not self._host.enabled:
                asyncio.create_task(
                    self._shutdown(code=1008, reason="Companion disabled"))

        try:
            self._loop.call_soon_threadsafe(shutdown_if_still_disabled)
        except RuntimeError:
            pass

    def _on_device_identity_changed(self, device_id: str) -> None:
        """Close every live socket whose credential was issued, rotated, or revoked."""
        if self._loop is None or not self._loop.is_running():
            return

        def close_changed_device() -> None:
            clients = tuple(
                websocket for websocket, active_device_id in self._authenticated_clients.items()
                if active_device_id == device_id
            )
            if clients:
                asyncio.create_task(self._close_device_clients(clients))

        try:
            self._loop.call_soon_threadsafe(close_changed_device)
        except RuntimeError:
            pass

    async def _close_device_clients(self, clients: tuple[Any, ...]) -> None:
        await asyncio.gather(
            *(client.close(code=1008, reason="Device authorization changed")
              for client in clients),
            return_exceptions=True,
        )

    async def _close_clients(self, *, code: int, reason: str) -> None:
        clients = tuple(self._clients)
        if clients:
            await asyncio.gather(*(client.close(code=code, reason=reason) for client in clients),
                                 return_exceptions=True)

    async def _handle(self, websocket) -> None:
        if self._closing or not self._host.enabled:
            await websocket.close(code=1008, reason="Companion disabled")
            return
        # Semaphore acquisition is bounded by handshake/idle policy; excess
        # connections are closed immediately instead of accumulating tasks.
        if self._semaphore.locked():
            await websocket.close(code=1013, reason="Connection limit")
            return
        await self._semaphore.acquire()
        self._active += 1
        self._clients.add(websocket)
        try:
            await self._session(websocket)
        except (ConnectionClosed, asyncio.TimeoutError):
            pass
        except (CompanionProtocolError, ValueError):
            await websocket.close(code=1008, reason="Protocol rejected")
        except Exception:
            _LOG.warning("companion connection handler failed")
            await websocket.close(code=1011, reason="Internal error")
        finally:
            self._clients.discard(websocket)
            self._active -= 1
            self._semaphore.release()

    def _allow_pairing_attempt(self, websocket) -> bool:
        """Bound unauthenticated pairing work per peer and cap limiter memory."""
        address = getattr(websocket, "remote_address", None)
        peer = str(address[0]) if isinstance(address, tuple) and address else "unknown"
        now = time.monotonic()
        # Expire old peer buckets before enforcing the memory cap. Never
        # evict a live peer bucket to admit a new address: doing so would let
        # an attacker rotate source addresses and reset another peer's limit.
        expired_peers = [
            key for key, stamps in self._pairing_attempts.items()
            if not stamps or now - stamps[-1] >= _PAIRING_RATE_WINDOW_SECONDS
        ]
        for key in expired_peers:
            del self._pairing_attempts[key]
        if peer not in self._pairing_attempts and len(self._pairing_attempts) >= _PAIRING_RATE_MAX_PEERS:
            return False
        attempts = [stamp for stamp in self._pairing_attempts.get(peer, [])
                    if now - stamp < _PAIRING_RATE_WINDOW_SECONDS]
        if len(attempts) >= _PAIRING_RATE_MAX_ATTEMPTS:
            self._pairing_attempts[peer] = attempts
            return False
        attempts.append(now)
        self._pairing_attempts[peer] = attempts
        return True

    async def _handle_pairing_v2(self, websocket, frame: dict[str, Any]) -> None:
        """Handle one pinned-TLS pairing frame; approval remains host-local."""
        if not self._host.enabled or self._closing:
            await websocket.close(code=1008, reason="Companion disabled")
            return
        if not self._allow_pairing_attempt(websocket):
            await websocket.close(code=1013, reason="Pairing rate limit")
            return
        try:
            if frame["type"] == "pairing.request":
                result = self._host.request_pairing_v2(
                    pairing_id=frame["pairing_id"],
                    device_id=frame["device_id"],
                    public_key=frame["public_key"],
                    requested_scopes=frame["requested_scopes"],
                    client_nonce=frame["client_nonce"],
                )
                response = {
                    "type": "pairing.challenge",
                    "protocol": "2.0",
                    "pairing_id": frame["pairing_id"],
                    **result,
                }
            else:
                credential = self._host.redeem_pairing_v2(
                    pairing_id=frame["pairing_id"],
                    device_id=frame["device_id"],
                    challenge_id=frame["challenge_id"],
                    signature=frame["signature"],
                )
                response = {
                    "type": "pairing.credential",
                    "protocol": "2.0",
                    "pairing_id": frame["pairing_id"],
                    "device_id": frame["device_id"],
                    "credential": credential,
                }
        except (PairingError, PairingV2Error):
            # Never disclose transaction existence, approval state, or proof
            # failure. Approval and rejection remain host-local operations.
            response = {
                "type": "pairing.error",
                "protocol": "2.0",
                "pairing_id": frame["pairing_id"],
                "code": "pairing_unavailable",
            }
        await websocket.send(_encode_wss_text_frame(response))

    async def _session(self, websocket) -> None:
        first = await asyncio.wait_for(
            websocket.recv(), timeout=self._config.handshake_timeout_seconds)
        # Pairing v2 is a one-frame TLS endpoint. The Android client must
        # validate the host certificate pin before sending either frame.
        # These frames never establish an authenticated companion session.
        try:
            initial = json.loads(first)
        except (TypeError, UnicodeDecodeError, json.JSONDecodeError):
            initial = None
        if isinstance(initial, dict) and initial.get("type") in {
            "pairing.request", "pairing.redeem"
        }:
            frame = validate_pairing_v2_frame(first)
            await self._handle_pairing_v2(websocket, frame)
            return

        identity = self._host.authenticate(first)
        # Track the authenticated device before checking current authorization.
        # If a credential change races authentication, either this check observes
        # it or the already-registered observer closes the socket.
        self._authenticated_clients[websocket] = identity["device_id"]
        try:
            if not self._host.is_identity_current(identity):
                await websocket.close(code=1008, reason="Device authorization changed")
                return
            # Validate envelope again to guarantee exact auth-frame shape.
            validate_client_frame(first, authenticated=False)
            await websocket.send(_encode_wss_text_frame({
                "type": "auth.ok", "protocol": "1.0",
                "workspace_id": identity["workspace_id"],
                "scopes": sorted(identity["scopes"]),
            }))
            while self._host.enabled and not self._closing:
                frame_data = await asyncio.wait_for(
                    websocket.recv(), timeout=self._config.idle_timeout_seconds)
                # Retain request-time revalidation as defense in depth if an
                # observer cannot be scheduled or the registry changes externally.
                if not self._host.is_identity_current(identity):
                    await websocket.close(code=1008, reason="Device authorization changed")
                    return
                frame = validate_client_frame(
                    frame_data, authenticated=True,
                    scopes=frozenset(identity["scopes"]),
                    workspace_id=identity["workspace_id"])
                response = await self._dispatch(frame, identity, websocket)
                if response is not None:
                    await websocket.send(_encode_wss_text_frame(response))
        finally:
            self._authenticated_clients.pop(websocket, None)
            await self._hub.unsubscribe(websocket, daemon=self._daemon())
        await websocket.close(code=1001, reason="Companion disabled")

    def _daemon(self) -> Any | None:
        """Return the running daemon instance, or None when unwired."""
        if self._daemon_accessor is None:
            return None
        try:
            return self._daemon_accessor()
        except Exception:  # noqa: BLE001 — a broken accessor fails closed
            return None

    @staticmethod
    def _relativize(root: Path, absolute: str) -> str:
        """Render an absolute host path as workspace-relative for the client.

        Absolute host paths are never sent to a remote device; the client
        works in workspace-relative paths and the server re-joins them.
        """
        try:
            relative = Path(absolute).relative_to(root)
        except (ValueError, TypeError):
            return "."
        return relative.as_posix() or "."

    def _resolve_workspace_path(self, root: Path, client_path: str) -> Path:
        """Resolve a client path inside the bound workspace (refuses escapes)."""
        return resolve_under_root(root, client_path)

    def _daemon_result(self, frame: dict[str, Any], reply: dict[str, Any]) -> dict[str, Any]:
        """Translate one daemon reply frame into a ``<type>.result`` frame."""
        if isinstance(reply.get("error"), str):
            return _error(frame.get("request_id"), "daemon_error", reply["error"])
        result = {key: value for key, value in reply.items() if key != "type"}
        return {
            "type": f"{frame['type']}.result",
            "request_id": frame["request_id"],
            "workspace_id": frame["workspace_id"],
            **result,
        }

    async def _dispatch(self, frame: dict[str, Any], identity: dict[str, Any],
                        websocket: Any) -> dict[str, Any] | None:
        """Dispatch one validated frame. Never raises for a bad request."""
        kind = frame["type"]
        request_id = frame.get("request_id")
        try:
            if kind == "status":
                return {
                    "type": "status.result", "request_id": request_id,
                    "listener": "wss", "enabled": self._host.enabled,
                }
            if kind == "sessions.list":
                # The workspace path is taken only from the approved device
                # credential record. Never accept a remote cwd/path override.
                sessions = await asyncio.to_thread(
                    Session.list_sessions, Path(identity["cwd"]))
                sessions = _remote_session_metadata(sessions)
                return {
                    "type": "sessions.result", "request_id": request_id,
                    "workspace_id": identity["workspace_id"], "sessions": sessions,
                }
            if kind in _SESSION_DAEMON_OPS:
                return await self._dispatch_session_op(frame, identity)
            if kind in ("chat.send", "chat.cancel", "chat.approve"):
                return await self._dispatch_chat(frame, identity, websocket)
            if kind in ("files.list", "files.read"):
                return await self._dispatch_files_read(frame, identity)
            if kind in ("sandbox.set", "sandbox.roots"):
                return await self._dispatch_sandbox(frame, identity)
            if kind in ("terminal.exec", "terminal.exec_cancel"):
                return await self._dispatch_exec(frame, identity)
            if kind in ("files.write", "files.delete"):
                return await self._dispatch_files_write(frame, identity)
            if kind in _BATCH_B_DAEMON_OPS:
                return await self._dispatch_batch_b(frame, identity)
            if kind in _BATCH_C_OPS:
                return await self._dispatch_batch_c(frame, identity)
            return _error(request_id, "not_implemented")
        except (CompanionDaemonError, CompanionExecError, CompanionFileError,
                CompanionLogsError, CompanionAuditError, CompanionConfigError,
                CompanionDevicesError) as exc:
            code = getattr(exc, "code", "daemon_error")
            return _error(request_id, code, str(exc))
        except Exception:  # noqa: BLE001 — one bad frame must not kill the socket
            _LOG.warning("companion dispatch failed: operation=%r", kind)
            return _error(request_id, "internal_error")

    async def _dispatch_session_op(self, frame: dict[str, Any],
                                   identity: dict[str, Any]) -> dict[str, Any]:
        daemon = self._daemon()
        if daemon is None:
            return _error(frame.get("request_id"), "daemon_unavailable")
        daemon_type, reply_type = _SESSION_DAEMON_OPS[frame["type"]]
        message: dict[str, Any] = {
            "type": daemon_type,
            "session_id": frame["session_id"],
            "cwd": identity["cwd"],
        }
        if frame["type"] == "sessions.rename":
            message["title"] = frame.get("title", "")
        elif frame["type"] == "sessions.rewind":
            message["record_index"] = frame["record_index"]
        elif frame["type"] == "sessions.history":
            for name in ("include_records", "limit", "offset", "before_index"):
                if name in frame:
                    message[name] = frame[name]
            # The client leaves limit unset; default it server-side so a
            # long session cannot blow the frame cap with a full replay.
            message.setdefault("limit", DEFAULT_HISTORY_LIMIT)
        elif frame["type"] == "sessions.resolve_cwd":
            pass  # daemon echoes the identity cwd as the candidate
        if frame["type"] in _SESSION_MUTATING_OPS:
            _LOG.info("companion session allowed: operation=%r session=%r device=%r",
                      frame["type"], frame["session_id"], identity["device_id"])
        replies = await call_daemon_message(
            daemon, message, session_id=frame["session_id"], cwd=identity["cwd"])
        reply = next((item for item in replies if item.get("type") == reply_type),
                     replies[0] if replies else None)
        if reply is None:
            return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
        return self._daemon_result(frame, reply)

    async def _dispatch_batch_b(self, frame: dict[str, Any],
                                identity: dict[str, Any]) -> dict[str, Any]:
        """Dispatch one Batch B frame to the daemon in-process.

        Every Batch B screen is daemon-native: the companion frame is
        translated to the daemon message type and the daemon's reply is
        translated back to ``<type>.result``. The daemon's error is
        surfaced unchanged (notably ``scheduler not running`` for task
        create/update/delete/trigger when no scheduler runs); skills
        install/update results pass through with no added sandboxing;
        ``set_model`` is global engine state and is restricted only by
        the ``models.manage`` scope check the validator already did.
        No session subscription is registered: every Batch B reply is a
        direct ``_send`` to the capture sink, so a concurrent turn's
        broadcast frames can never pollute the reply selection.
        """
        daemon = self._daemon()
        if daemon is None:
            return _error(frame.get("request_id"), "daemon_unavailable")
        kind = frame["type"]
        daemon_type, reply_type = _BATCH_B_DAEMON_OPS[kind]
        message: dict[str, Any] = {"type": daemon_type}
        if kind in ("memory.list", "memory.read"):
            # cwd is always the host-bound workspace; a session scope also
            # needs the session id the client named.
            message["cwd"] = identity["cwd"]
            if "scope" in frame:
                message["scope"] = frame["scope"]
            if "session_id" in frame:
                message["session_id"] = frame["session_id"]
            if kind == "memory.read":
                message["memory_id"] = frame["memory_id"]
        elif kind == "models.set":
            message["model"] = frame["model"]
        elif kind == "rant.list":
            if "status" in frame:
                message["status"] = frame["status"]
        elif kind == "rant.submit":
            message["message"] = frame["message"]
            if "agent" in frame:
                message["agent"] = frame["agent"]
        elif kind == "evolution.summary":
            if "limit" in frame:
                message["limit"] = frame["limit"]
        elif kind == "tasks.trigger" or kind == "tasks.delete":
            message["name"] = frame["name"]
        elif kind in ("tasks.create", "tasks.update"):
            # The daemon reads task fields at the top level; the companion
            # frame nests them in one ``task`` dict.
            task = frame.get("task") or {}
            if kind == "tasks.update":
                message["name"] = frame["name"]
            for key in _TASK_FIELD_ALLOWLIST:
                if key in task:
                    message[key] = task[key]
        elif kind in ("tasks.template_create", "tasks.template_update"):
            # The scheduler stores a template as (name, prompt): the prompt
            # string is extracted from the nested ``template`` dict.
            message["name"] = frame["name"]
            template = frame.get("template") or {}
            message["prompt"] = template.get("prompt", "")
        elif kind == "tasks.template_delete":
            message["name"] = frame["name"]
        elif kind == "skills.list":
            # The loader resolves skills against the session's directory;
            # the bound workspace is the only root a remote device may use.
            message["cwd"] = identity["cwd"]
        elif kind == "skills.install":
            message["name"] = frame["name"]
            if "confirmed" in frame:
                message["confirmed"] = frame["confirmed"]
        if kind in _BATCH_B_MUTATING_OPS:
            target = frame.get("name") or frame.get("model") or ""
            _LOG.info("companion batch-b allowed: operation=%r target=%r device=%r",
                      kind, target, identity["device_id"])
        replies = await call_daemon_message(daemon, message)
        if reply_type is None:
            reply = replies[0] if replies else None
        else:
            reply = next((item for item in replies if item.get("type") == reply_type),
                         replies[0] if replies else None)
        if reply is None:
            return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
        result = self._daemon_result(frame, reply)
        if kind in ("memory.list", "memory.read", "skills.list"):
            self._relativize_batch_b_paths(frame, identity, result)
        return result

    def _relativize_batch_b_paths(self, frame: dict[str, Any],
                                  identity: dict[str, Any],
                                  result: dict[str, Any]) -> None:
        """Rewrite absolute host paths in a Batch B reply as workspace-relative.

        Absolute host paths are never sent to a remote device. Memory
        replies carry ``directory`` / ``index_path`` / ``path``; the skills
        list carries a ``path`` per skill and per shadow side. Paths outside
        the workspace (e.g. user-level skill dirs) render as ``.``.
        """
        root = Path(identity["cwd"]).resolve()
        for key in ("directory", "index_path", "path"):
            if isinstance(result.get(key), str):
                result[key] = self._relativize(root, result[key])
        if frame["type"] == "skills.list":
            skills = result.get("skills")
            if isinstance(skills, list):
                for skill in skills:
                    if isinstance(skill, dict) and isinstance(skill.get("path"), str):
                        skill["path"] = self._relativize(root, skill["path"])
            shadows = result.get("shadows")
            if isinstance(shadows, list):
                for shadow in shadows:
                    if not isinstance(shadow, dict):
                        continue
                    for side in ("kept", "dropped"):
                        entry = shadow.get(side)
                        if isinstance(entry, dict) and isinstance(entry.get("path"), str):
                            entry["path"] = self._relativize(root, entry["path"])

    async def _dispatch_chat(self, frame: dict[str, Any], identity: dict[str, Any],
                             websocket: Any) -> dict[str, Any]:
        daemon = self._daemon()
        if daemon is None:
            return _error(frame.get("request_id"), "daemon_unavailable")
        device_id = identity["device_id"]
        if frame["type"] == "chat.cancel":
            # A remote client may cancel only sessions whose stream it joined.
            # Never treat a client-supplied session id as an authorization token.
            subscribed = self._hub.sessions_for(websocket)
            if frame.get("session_id") and frame["session_id"] not in subscribed:
                return _error(frame.get("request_id"), "session_not_subscribed")
            # No session_id: cancel every turn this connection is subscribed
            # to (the client cancels "the active turn" without naming it).
            targets = ([frame["session_id"]] if frame.get("session_id")
                       else subscribed)
            stopped = False
            for session_id in targets:
                stopped = await cancel_turn(
                    daemon, session_id=session_id, device_id=device_id) or stopped
            return {"type": "chat.cancel.result", "request_id": frame.get("request_id"),
                    "workspace_id": identity["workspace_id"],
                    "session_id": frame.get("session_id"), "stopped": stopped}
        if frame["type"] == "chat.approve":
            pending = getattr(daemon, "_pending_approvals", {}).get(
                frame["approval_request_id"])
            subscribed = self._hub.sessions_for(websocket)
            if pending is None or pending[1] not in subscribed:
                return _error(frame.get("request_id"), "approval_not_for_subscribed_session")
            answered = answer_approval(
                daemon, approval_request_id=frame["approval_request_id"],
                approved=frame["approved"], device_id=device_id)
            return {"type": "chat.approve.result", "request_id": frame.get("request_id"),
                    "workspace_id": identity["workspace_id"],
                    "approval_request_id": frame["approval_request_id"],
                    "answered": answered}
        # chat.send: subscribe BEFORE starting the turn so task_queued and
        # the turn's stream frames reach this connection.
        session_id = frame.get("session_id")
        if not session_id:
            return _error(frame.get("request_id"), "session_required",
                          "chat.send requires session_id")
        await self._hub.subscribe(
            websocket, session_id=session_id,
            scopes=frozenset(identity["scopes"]), device_id=device_id,
            daemon=daemon, cwd=identity["cwd"])
        outcome = await start_task(
            daemon, session_id=session_id, cwd=identity["cwd"],
            prompt=frame["prompt"], request_id=frame.get("request_id"),
            device_id=device_id)
        return {"type": "chat.send.result", "request_id": frame.get("request_id"),
                "workspace_id": identity["workspace_id"],
                "session_id": session_id, **outcome}

    async def _dispatch_files_read(self, frame: dict[str, Any],
                                   identity: dict[str, Any]) -> dict[str, Any]:
        daemon = self._daemon()
        if daemon is None:
            return _error(frame.get("request_id"), "daemon_unavailable")
        root = Path(identity["cwd"]).resolve()
        try:
            target = self._resolve_workspace_path(root, frame["path"])
        except CompanionFileError as exc:
            return _error(frame.get("request_id"), exc.code, str(exc))
        if frame["type"] == "files.list":
            replies = await call_daemon_message(
                daemon, {"type": "list_files", "path": str(target)})
            reply = next((item for item in replies if item.get("type") == "files_list"),
                         replies[0] if replies else None)
            if reply is None:
                return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
            result = self._daemon_result(frame, reply)
            if isinstance(result.get("path"), str):
                result["path"] = self._relativize(root, result["path"])
            entries = result.get("entries")
            if isinstance(entries, list):
                for entry in entries:
                    if isinstance(entry, dict) and isinstance(entry.get("path"), str):
                        entry["path"] = self._relativize(root, entry["path"])
            return result
        message: dict[str, Any] = {"type": "read_file", "path": str(target)}
        for name in ("start_line", "line_limit"):
            if name in frame:
                message[name] = frame[name]
        replies = await call_daemon_message(daemon, message)
        reply = next((item for item in replies if item.get("type") == "file_content"),
                     replies[0] if replies else None)
        if reply is None:
            return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
        result = self._daemon_result(frame, reply)
        if isinstance(result.get("path"), str):
            result["path"] = self._relativize(root, result["path"])
        return result

    async def _dispatch_sandbox(self, frame: dict[str, Any],
                                identity: dict[str, Any]) -> dict[str, Any]:
        daemon = self._daemon()
        if daemon is None:
            return _error(frame.get("request_id"), "daemon_unavailable")
        if frame["type"] == "sandbox.set":
            _LOG.info("companion sandbox allowed: operation=%r session=%r device=%r",
                      "sandbox.set", frame["session_id"], identity["device_id"])
            replies = await call_daemon_message(daemon, {
                "type": "set_sandbox", "session_id": frame["session_id"],
                "cwd": identity["cwd"], "mode": frame["mode"],
            }, session_id=frame["session_id"], cwd=identity["cwd"])
            reply = next((item for item in replies if item.get("type") == "sandbox_set"),
                         replies[0] if replies else None)
            if reply is None:
                return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
            return self._daemon_result(frame, reply)
        # sandbox.roots: add/remove paths are validated inside the bound
        # workspace before the daemon ever sees them; list needs no path.
        path = ""
        if frame["op"] in ("add", "remove"):
            root = Path(identity["cwd"]).resolve()
            try:
                path = str(self._resolve_workspace_path(root, frame["path"]))
            except CompanionFileError as exc:
                return _error(frame.get("request_id"), exc.code, str(exc))
            _LOG.info("companion sandbox allowed: operation=%r session=%r device=%r",
                      "sandbox.roots", frame["session_id"], identity["device_id"])
        replies = await call_daemon_message(daemon, {
            "type": "set_sandbox_roots", "session_id": frame["session_id"],
            "cwd": identity["cwd"], "op": frame["op"], "path": path,
            "kind": frame.get("kind"),
        }, session_id=frame["session_id"], cwd=identity["cwd"])
        reply = next((item for item in replies if item.get("type") == "sandbox_roots"),
                     replies[0] if replies else None)
        if reply is None:
            return _error(frame.get("request_id"), "daemon_error", "no reply from daemon")
        return self._daemon_result(frame, reply)

    async def _dispatch_exec(self, frame: dict[str, Any],
                             identity: dict[str, Any]) -> dict[str, Any]:
        device_id = identity["device_id"]
        if frame["type"] == "terminal.exec_cancel":
            cancelled = await self._exec_manager.cancel(device_id, frame["exec_id"])
            return {"type": "terminal.exec_cancel.result",
                    "request_id": frame.get("request_id"),
                    "workspace_id": identity["workspace_id"],
                    "exec_id": frame["exec_id"], "cancelled": cancelled}
        result = await self._exec_manager.run_exec(
            device_id=device_id,
            command=frame["command"],
            cwd=identity["cwd"],
            session_id=frame.get("session_id"),
            timeout=frame.get("timeout"),
            requested_mode=frame.get("mode"),
            confirm_danger=bool(frame.get("confirm_danger", False)),
            confirm_risky=bool(frame.get("confirm_risky", False)),
            config=self._exec_config,
        )
        return {"type": "terminal.exec.result", "request_id": frame.get("request_id"),
                "workspace_id": identity["workspace_id"],
                "exec_id": result.exec_id, "cwd": result.cwd, "mode": result.mode,
                "stdout": result.stdout, "stderr": result.stderr,
                "exit_code": result.exit_code, "signal": result.signal,
                "timed_out": result.timed_out, "sandbox": result.sandbox,
                "duration_ms": result.duration_ms,
                "stdout_truncated": result.stdout_truncated,
                "stderr_truncated": result.stderr_truncated}

    async def _dispatch_files_write(self, frame: dict[str, Any],
                                    identity: dict[str, Any]) -> dict[str, Any]:
        device_id = identity["device_id"]
        if frame["type"] == "files.delete":
            outcome = companion_delete_file(
                frame["path"], root=identity["cwd"],
                session_id=frame.get("session_id"), config=self._exec_config,
                mode=frame.get("mode"), confirm_danger=bool(frame.get("confirm_danger", False)),
                device_id=device_id)
            return {"type": "files.delete.result", "request_id": frame.get("request_id"),
                    "workspace_id": identity["workspace_id"], **outcome}
        outcome = companion_write_file(
            frame["path"], frame["content"], root=identity["cwd"],
            session_id=frame.get("session_id"), config=self._exec_config,
            expected_sha256=frame.get("expected_sha256"),
            create_parents=bool(frame.get("create_parents", False)),
            mode=frame.get("mode"), confirm_danger=bool(frame.get("confirm_danger", False)),
            device_id=device_id)
        return {"type": "files.write.result", "request_id": frame.get("request_id"),
                "workspace_id": identity["workspace_id"],
                "path": outcome.path, "bytes_written": outcome.bytes_written,
                "sha256": outcome.sha256, "created": outcome.created,
                "mode": outcome.mode, "line_count": outcome.line_count}

    @staticmethod
    def _agent_display_path(root: Path, absolute: str) -> str:
        """Render one agent directory for the Hosts screen.

        Inside the bound workspace it is workspace-relative, like every
        other path the listener returns. Outside it the full host path
        never leaves the host: only the final directory name is shown.
        The Hosts screen lists workspaces; it never needs a host path to
        select one, because sessions stay bound to the identity cwd.
        """
        if not isinstance(absolute, str) or not absolute:
            return "."
        try:
            return Path(absolute).relative_to(root).as_posix() or "."
        except (ValueError, TypeError):
            return Path(absolute).name or "."

    async def _dispatch_batch_c(self, frame: dict[str, Any],
                                identity: dict[str, Any]) -> dict[str, Any]:
        """Dispatch one Batch C frame.

        ``hosts.list`` reuses daemon ``list_agents`` in-process.
        ``logs.tail`` / ``audit.tail`` tail allowlisted host logs (paths
        are sanitised in the modules; the client never names a file).
        ``devices.list`` / ``devices.revoke`` wrap the host service; the
        wrapper blocks self-revocation and audit-logs every revoke.
        ``settings.get`` / ``settings.set`` snapshot / patch the engine
        config under the strict allowlist in ``companion_config``; the
        patch is audit-logged by key name only, never values.
        Reads are not audit-logged, matching Batches A/B.
        """
        kind = frame["type"]
        request_id = frame.get("request_id")
        workspace_id = identity["workspace_id"]
        root = Path(identity["cwd"]).resolve()
        if kind == "hosts.list":
            daemon = self._daemon()
            if daemon is None:
                return _error(request_id, "daemon_unavailable")
            replies = await call_daemon_message(daemon, {"type": "list_agents"})
            reply = next((item for item in replies if item.get("type") == "agents_list"),
                         replies[0] if replies else None)
            if reply is None:
                return _error(request_id, "daemon_error", "no reply from daemon")
            agents: list[dict[str, Any]] = []
            for entry in reply.get("agents", [])[:_MAX_REMOTE_AGENTS]:
                if not isinstance(entry, dict):
                    continue
                repo = entry.get("repo", "")
                agents.append({
                    "name": entry.get("name", "")[:256]
                    if isinstance(entry.get("name", ""), str) else "",
                    "repo": repo[:256] if isinstance(repo, str) else "",
                    "path": self._agent_display_path(root, entry.get("path", "")),
                    "latest_session_at": entry.get("latest_session_at", "")[:64]
                    if isinstance(entry.get("latest_session_at", ""), str) else "",
                })
            return {"type": "hosts.list.result", "request_id": request_id,
                    "workspace_id": workspace_id, "agents": agents}
        if kind == "logs.tail":
            result = await asyncio.to_thread(
                companion_logs_tail, frame.get("source", "daemon"),
                lines=frame.get("lines", 200), workspace_root=root)
            return {"type": "logs.tail.result", "request_id": request_id,
                    "workspace_id": workspace_id, **result}
        if kind == "audit.tail":
            result = await asyncio.to_thread(
                companion_audit_tail, frame.get("limit", 200), workspace_root=root)
            return {"type": "audit.tail.result", "request_id": request_id,
                    "workspace_id": workspace_id, **result}
        if kind == "devices.list":
            devices = await asyncio.to_thread(
                companion_list_devices, self._host)
            return {"type": "devices.list.result", "request_id": request_id,
                    "workspace_id": workspace_id, "devices": devices}
        if kind == "devices.revoke":
            outcome = await asyncio.to_thread(
                companion_revoke_device, self._host,
                device_id=frame["device_id"],
                current_device_id=identity["device_id"])
            return {"type": "devices.revoke.result", "request_id": request_id,
                    "workspace_id": workspace_id, **outcome}
        if kind == "settings.get":
            result = await asyncio.to_thread(
                companion_settings_snapshot,
                exec_config=self._exec_config, listener_config=self._config)
            return {"type": "settings.get.result", "request_id": request_id,
                    "workspace_id": workspace_id, **result}
        # settings.set: the engine TOML write and the companion-policy
        # changes are validated together in the module (an unknown key
        # rejects the whole patch before anything is written); the
        # listener then installs the returned live-config changes.
        result = await asyncio.to_thread(
            companion_apply_settings_patch, frame["patch"],
            device_id=identity["device_id"])
        if result["companion_changed"]:
            self._exec_config, self._config = apply_companion_patch(
                self._exec_config, self._config, result["companion_changed"])
        return {"type": "settings.set.result", "request_id": request_id,
                "workspace_id": workspace_id,
                "changed": result["changed"],
                "reloadable": result["reloadable"],
                "hints": result["hints"],
                "restart_required": result["restart_required"],
                "backup_created": result["backup_created"]}
