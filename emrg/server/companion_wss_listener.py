"""Dedicated opt-in WSS transport for EMRG Companion.

Currently implemented remote operations are authenticated status and bounded,
read-only session listing. Chat, tool execution, filesystem access, and
pairing-management actions are deliberately not dispatched.
"""
from __future__ import annotations

import asyncio
import json
import logging
import unicodedata
from typing import Any

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_protocol import (
    CompanionProtocolError, encode_server_frame, validate_client_frame,
)
from emrg.server.companion_tls import create_server_context, CompanionTlsError
from emrg.session import Session
from pathlib import Path

_LOG = logging.getLogger(__name__)
_MAX_REMOTE_SESSIONS = 200
_SESSION_TEXT_LIMITS = {"session_id": 128, "created_at": 64, "updated_at": 64, "title": 256}


def _safe_remote_text(value: str, limit: int) -> bool:
    """Reject display-control and invalid Unicode before metadata reaches clients."""
    return not any(unicodedata.category(char) in {"Cc", "Cf", "Cs"}
                   for char in value[:limit])


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
                 config: CompanionListenerConfig, cert_file, key_file):
        self._host = host_service
        self._config = config
        self._cert_file = cert_file
        self._key_file = key_file
        self._server = None
        self._starting = False
        self._semaphore = asyncio.Semaphore(config.max_connections)
        self._active = 0
        self._closing = False
        self._disposed = False
        self._loop: asyncio.AbstractEventLoop | None = None
        self._clients: set[Any] = set()
        self._host.add_state_callback(self._on_host_state)

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
            tls = create_server_context(self._cert_file, self._key_file)
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

    async def _session(self, websocket) -> None:
        first = await asyncio.wait_for(
            websocket.recv(), timeout=self._config.handshake_timeout_seconds)
        identity = self._host.authenticate(first)
        # Validate envelope again to guarantee exact auth-frame shape.
        validate_client_frame(first, authenticated=False)
        await websocket.send(encode_server_frame({
            "type": "auth.ok", "protocol": "1.0",
            "workspace_id": identity["workspace_id"],
            "scopes": sorted(identity["scopes"]),
        }))
        while self._host.enabled and not self._closing:
            frame_data = await asyncio.wait_for(
                websocket.recv(), timeout=self._config.idle_timeout_seconds)
            # Re-check local device policy for every request. Revocation, credential
            # rotation, or workspace/scope changes terminate already-open sockets.
            if not self._host.is_identity_current(identity):
                await websocket.close(code=1008, reason="Device authorization changed")
                return
            frame = validate_client_frame(
                frame_data, authenticated=True,
                scopes=frozenset(identity["scopes"]),
                workspace_id=identity["workspace_id"])
            # Only explicitly implemented, read-only operations are dispatched.
            # Chat and all mutating/privileged operations remain unavailable.
            if frame["type"] == "status":
                response: dict[str, Any] = {
                    "type": "status.result", "request_id": frame["request_id"],
                    "listener": "wss", "enabled": self._host.enabled,
                }
            elif frame["type"] == "sessions.list":
                # The workspace path is taken only from the approved device
                # credential record. Never accept a remote cwd/path override.
                sessions = await asyncio.to_thread(
                    Session.list_sessions, Path(identity["cwd"]))
                sessions = _remote_session_metadata(sessions)
                response = {
                    "type": "sessions.result", "request_id": frame["request_id"],
                    "workspace_id": identity["workspace_id"], "sessions": sessions,
                }
            else:
                response = {"type": "error", "request_id": frame.get("request_id"),
                           "code": "not_implemented"}
            await websocket.send(encode_server_frame(response))
        await websocket.close(code=1001, reason="Companion disabled")
