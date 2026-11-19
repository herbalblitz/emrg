"""The gateway's WebSocket server.

Two endpoints share one TLS listener:

``wss://<host>:56032/v1/pair``
    one-shot pairing. The first frame carries the 8-digit code printed by
    ``emrg-gateway pair``; the reply carries the device token **once**.

``wss://<host>:56032/v1/ws``
    the session endpoint. The first frame carries the device token; after
    ``gw_hello_ok`` the connection is a multiplexed pipe: daemon frames flow
    downstream unchanged, phone requests flow upstream either to the daemon
    (allow-listed proxy frames) or to a gateway handler.

The listener is TLS 1.3 with the host's self-signed certificate. Android pins
the certificate by SPKI, so there is no CA and no hostname dependency: the
phone's pin survives a DHCP address change.

Nothing here owns the agent. A per-device :class:`~emrg.gateway.daemon_link.DaemonLink`
holds the daemon connection and outlives the phone's socket; the phone attaches
and detaches. That separation is what lets a turn continue while the phone is
locked, in a tunnel, or dead.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import urllib.parse
from collections import defaultdict, deque
from typing import Any

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from emrg.gateway import API_VERSION, __version__
from emrg.gateway.audit import AuditLog
from emrg.gateway.config import GatewayConfig, GatewayPaths, gateway_paths, load_config
from emrg.gateway.daemon_link import DaemonLink, LinkRegistry
from emrg.gateway.exec import ExecManager, run as run_exec
from emrg.gateway.files import delete as delete_file, read as read_file, write as write_file
from emrg.gateway.identity import Device, DeviceStore, PairingError
from emrg.gateway import config_api, logs
from emrg.gateway.policy import (
    BLOCKED_TYPES,
    CONFIG_LIMIT,
    EXEC_LIMIT,
    GATEWAY_TYPES,
    LOG_LIMIT,
    MUTATE_LIMIT,
    PROXIED_TYPES,
    READ_LIMIT,
    Limit,
    RateLimiter,
    classify,
)
from emrg.gateway.protocol import (
    ERR_AUTH_FAILED,
    ERR_AUTH_REQUIRED,
    ERR_BAD_FRAME,
    ERR_DAEMON_UNAVAILABLE,
    ERR_FORBIDDEN,
    ERR_INTERNAL,
    ERR_INVALID_ARGUMENT,
    ERR_RATE_LIMITED,
    ERR_UNKNOWN_TYPE,
    ERR_VERSION,
    GatewayError,
    compatible,
    frame,
    hello,
    parse_frame,
)
from emrg.gateway.tls import ensure_certificate, load_server_context

logger = logging.getLogger("emrg.gateway")

AUTH_TIMEOUT_SECONDS = 10.0
PAIR_WINDOW_SECONDS = 600.0
PAIR_ATTEMPTS_PER_IP = 10
QUEUE_FRAMES = 2000

_LIMITS = {
    "read": Limit(READ_LIMIT),
    "mutate": Limit(MUTATE_LIMIT),
    "chat": Limit(MUTATE_LIMIT),
    "exec": Limit(EXEC_LIMIT),
    "config": Limit(CONFIG_LIMIT),
    "logs": Limit(LOG_LIMIT),
}


class GatewayServer:
    def __init__(
        self,
        config: GatewayConfig | None = None,
        paths: GatewayPaths | None = None,
    ) -> None:
        self.config = config or load_config()
        self.paths = paths or gateway_paths()
        self.devices = DeviceStore(
            self.paths.devices,
            self.paths.pairing,
            max_devices=self.config.max_devices,
        )
        self.audit = AuditLog(
            self.paths.audit,
            max_bytes=self.config.audit_max_bytes,
            backups=self.config.audit_backups,
        )
        self.links = LinkRegistry(idle_timeout=self.config.idle_timeout)
        self.limiter = RateLimiter()
        self.execs = ExecManager()
        self._pair_attempts: dict[str, deque[float]] = defaultdict(deque)
        self._sockets: dict[str, set[ServerConnection]] = defaultdict(set)
        self._server = None
        self._reaper: asyncio.Task | None = None
        self.started_at = time.time()

    # ── lifecycle ────────────────────────────────────────────────────

    async def start(self):
        """Create the TLS listener and the reaper; return the websockets Server.

        Split from :meth:`serve` so a test (or an embedding host) can bind port
        0, learn the port the kernel chose, and drive the real handler without
        reaching into private state.
        """
        cert = ensure_certificate(self.paths.cert, self.paths.key)
        context = load_server_context(self.paths.cert, self.paths.key)
        logger.info(
            "gateway listening on wss://%s:%d (fingerprint %s)",
            self.config.host,
            self.config.port,
            cert.fingerprint,
        )
        self._server = await serve(
            self._handler,
            self.config.host,
            self.config.port,
            ssl=context,
            max_size=self.config.frame_limit,
            ping_interval=25,
            ping_timeout=25,
            close_timeout=5,
        )
        self._reaper = asyncio.create_task(self._reap_loop(), name="gw-reaper")
        return self._server

    async def serve(self) -> None:
        await self.start()
        try:
            await self._server.wait_closed()
        finally:
            if self._reaper is not None:
                self._reaper.cancel()
                try:
                    await self._reaper
                except (asyncio.CancelledError, Exception):
                    pass
                self._reaper = None
            await self.links.close_all()

    def stop(self) -> None:
        if self._server is not None:
            self._server.close()

    async def close(self) -> None:
        """Stop the listener and the reaper, then close every daemon link."""
        if self._server is not None:
            self._server.close()
        if self._reaper is not None:
            self._reaper.cancel()
            try:
                await self._reaper
            except (asyncio.CancelledError, Exception):
                pass
            self._reaper = None
        if self._server is not None:
            try:
                await self._server.wait_closed()
            except Exception:
                pass
        await self.links.close_all()

    async def _reap_loop(self) -> None:
        while True:
            await asyncio.sleep(30.0)
            try:
                await self.links.reap()
            except Exception:
                logger.debug("gateway reaper failed", exc_info=True)

    # ── HTTP/WS entry ────────────────────────────────────────────────

    async def _handler(self, ws: ServerConnection) -> None:
        path = urllib.parse.urlsplit(getattr(ws.request, "path", "/")).path
        try:
            if path in {"/v1/pair", "/pair"}:
                await self._handle_pair(ws)
            elif path in {"/v1/ws", "/ws", "/"}:
                await self._handle_client(ws)
            else:
                await ws.close(code=1008, reason="unknown endpoint")
        except ConnectionClosed:
            pass
        except Exception:
            logger.warning("gateway handler failed for %s", path, exc_info=True)
            try:
                await ws.close(code=1011, reason="internal error")
            except Exception:
                pass

    # ── pairing ──────────────────────────────────────────────────────

    async def _handle_pair(self, ws: ServerConnection) -> None:
        address = _address(ws)
        if not self._pair_allowed(address):
            await _send(ws, GatewayError(
                ERR_RATE_LIMITED,
                "too many pairing attempts from this address; wait and try again",
                retryable=True,
            ).frame())
            await ws.close(code=1008, reason="rate limited")
            return
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=AUTH_TIMEOUT_SECONDS)
            value = parse_frame(raw)
        except (asyncio.TimeoutError, GatewayError, ConnectionClosed):
            await ws.close(code=1008, reason="pairing handshake timeout")
            return
        if value.get("type") != "gw_pair":
            await _send(ws, GatewayError(ERR_AUTH_REQUIRED, "expected gw_pair as the first frame").frame())
            await ws.close(code=1008, reason="expected gw_pair")
            return
        self._pair_attempts[address].append(time.monotonic())
        try:
            device, token = self.devices.pair(
                str(value.get("code", "")),
                str(value.get("device_name", "Android device")),
                app_version=str(value.get("app_version", "")),
            )
        except PairingError as exc:
            self.audit.append(
                "pair",
                decision="deny",
                detail={"code": exc.code, "address": address},
            )
            await _send(ws, GatewayError(exc.code, exc.message).frame())
            await ws.close(code=1008, reason="pairing refused")
            return
        self.audit.append(
            "pair",
            device_id=device.id,
            device_name=device.name,
            detail={"address": address, "app_version": device.app_version},
        )
        await _send(ws, frame(
            "gw_pair_ok",
            api_version=API_VERSION,
            device=device.public(),
            token=token,
            gateway={
                "host": self.config.public_host or self.config.host,
                "port": self.config.port,
                "api_version": API_VERSION,
                "gateway_version": __version__,
            },
            hint="store this token in Android Keystore; it is shown once",
        ))
        await ws.close(code=1000, reason="paired")

    def _pair_allowed(self, address: str) -> bool:
        bucket = self._pair_attempts[address]
        cutoff = time.monotonic() - PAIR_WINDOW_SECONDS
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        return len(bucket) < PAIR_ATTEMPTS_PER_IP

    # ── authenticated endpoint ───────────────────────────────────────

    async def _handle_client(self, ws: ServerConnection) -> None:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=AUTH_TIMEOUT_SECONDS)
        except (asyncio.TimeoutError, ConnectionClosed):
            await ws.close(code=1008, reason="auth timeout")
            return
        try:
            value = parse_frame(raw)
        except GatewayError as exc:
            await _send(ws, exc.frame())
            await ws.close(code=1008, reason="bad auth frame")
            return
        if value.get("type") != "gw_auth":
            await _send(ws, GatewayError(ERR_AUTH_REQUIRED, "expected gw_auth as the first frame").frame())
            await ws.close(code=1008, reason="expected gw_auth")
            return
        client_version = str(value.get("api_version", "0"))
        if not compatible(client_version):
            await _send(ws, GatewayError(
                ERR_VERSION,
                f"this gateway speaks API {API_VERSION}; the app sent {client_version}. "
                "Update the app or the host.",
            ).frame())
            await ws.close(code=1008, reason="api version")
            return
        token = value.get("token")
        device = self.devices.authenticate(token if isinstance(token, str) else "")
        if device is None:
            self.audit.append(
                "auth",
                decision="deny",
                detail={"address": _address(ws), "app_version": value.get("app_version", "")},
            )
            await _send(ws, GatewayError(ERR_AUTH_FAILED, "unknown or revoked device token").frame())
            await ws.close(code=1008, reason="auth failed")
            return
        claimed = value.get("device_id")
        if isinstance(claimed, str) and claimed and claimed != device.id:
            self.audit.append(
                "auth",
                decision="deny",
                device_id=device.id,
                device_name=device.name,
                detail={"reason": "device_id mismatch", "address": _address(ws)},
            )
            await _send(ws, GatewayError(ERR_AUTH_FAILED, "device id does not match the token").frame())
            await ws.close(code=1008, reason="auth failed")
            return
        since_seq = value.get("since_seq", 0)
        try:
            since_seq = max(0, int(since_seq))
        except (TypeError, ValueError):
            since_seq = 0
        app_version = str(value.get("app_version", ""))[:120]
        if app_version and app_version != device.app_version:
            device.app_version = app_version
            self.devices.touch(device.id)

        try:
            link = await self.links.get_or_create(device.id)
        except GatewayError as exc:
            await _send(ws, exc.frame())
            await ws.close(code=1011, reason="daemon unavailable")
            return
        except Exception as exc:
            logger.warning("cannot open daemon link for %s", device.id, exc_info=True)
            await _send(ws, GatewayError(
                ERR_DAEMON_UNAVAILABLE,
                f"the host daemon could not be reached: {exc}",
                retryable=True,
            ).frame())
            await ws.close(code=1011, reason="daemon unavailable")
            return

        queue: asyncio.Queue = asyncio.Queue(maxsize=QUEUE_FRAMES)
        replayed = link.attach(queue, since_seq)
        self._sockets[device.id].add(ws)
        self.audit.append("connect", device_id=device.id, device_name=device.name,
                          detail={"address": _address(ws), "since_seq": since_seq, "replayed": replayed})
        sender = None
        try:
            # The greeting goes out before the pump starts: a replayed frame
            # from a running turn must arrive after `gw_hello_ok`, not before it.
            engine_version = await link.ensure_version(1.0)
            await _send(ws, hello(
                self.config,
                engine_version=engine_version,
                host=self.config.public_host or self.config.host,
                port=self.config.port,
            ))
            await _send(ws, frame("gw_attached", replayed=replayed, seq=link.snapshot().seq))
            sender = asyncio.create_task(self._send_loop(ws, queue, device),
                                         name=f"gw-send-{device.id}")
            async for raw in ws:
                try:
                    value = parse_frame(raw)
                except GatewayError as exc:
                    await _send(ws, exc.frame())
                    continue
                try:
                    await self._dispatch(device, link, ws, value)
                except GatewayError as exc:
                    await _send(ws, exc.frame(str(value.get("rid") or "")))
                except Exception as exc:
                    logger.warning("gateway dispatch failed for %s/%s", device.id, value.get("type"),
                                   exc_info=True)
                    await _send(ws, GatewayError(
                        ERR_INTERNAL, f"the request failed: {exc}"
                    ).frame(str(value.get("rid") or "")))
        except ConnectionClosed:
            pass
        finally:
            if sender is not None:
                sender.cancel()
                try:
                    await sender
                except (asyncio.CancelledError, Exception):
                    pass
            link.detach(queue)
            self._sockets[device.id].discard(ws)
            if not self._sockets[device.id]:
                self._sockets.pop(device.id, None)
            self.audit.append("disconnect", device_id=device.id, device_name=device.name,
                              detail={"address": _address(ws)})
            # The link is deliberately not closed here. The daemon cancels a
            # running turn when its upstream connection drops, so closing it on
            # a phone disconnect would kill the very turn the phone expects to
            # resume. The idle reaper closes it later.

    async def _send_loop(self, ws: ServerConnection, queue: asyncio.Queue, device: Device) -> None:
        try:
            while True:
                item = await queue.get()
                if not await _send(ws, item):
                    break
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.debug("gateway send loop ended for %s", device.id, exc_info=True)

    # ── dispatch ─────────────────────────────────────────────────────

    async def _dispatch(
        self,
        device: Device,
        link: DaemonLink,
        ws: ServerConnection,
        value: dict,
    ) -> None:
        type_ = value.get("type")
        rid = str(value.get("rid") or "") or None
        if not isinstance(type_, str) or not type_:
            raise GatewayError(ERR_BAD_FRAME, "frame has no type")

        if type_ == "gw_ping":
            await _send(ws, frame("gw_pong", rid, now=time.time(), api_version=API_VERSION))
            return
        if type_ == "gw_attach":
            queue = None
            # A second attach on the same socket is a client-side resume: the
            # existing queue is the one attached above, and replaying from the
            # buffer into it is the same operation. The caller identifies the
            # socket, so this is only useful for an app that lost its own
            # sequence tracking.
            del queue
            since = value.get("since_seq", 0)
            try:
                since = max(0, int(since))
            except (TypeError, ValueError):
                since = 0
            await _send(ws, frame("gw_attach_ok", rid, seq=link.snapshot().seq, since_seq=since,
                                  note="frames are streamed on this socket; use list_history to rebuild"))
            return

        if type_ in {"gw_exec", "gw_exec_cancel", "gw_read_file", "gw_write_file", "gw_delete_file",
                     "gw_config_get", "gw_config_set", "gw_logs_tail", "gw_devices",
                     "gw_revoke_device", "gw_audit_tail", "gw_status"}:
            await self._gateway_op(device, link, ws, value, type_, rid)
            return

        if type_ in BLOCKED_TYPES:
            self.audit.append(f"deny.{type_}", decision="deny", device_id=device.id,
                              device_name=device.name)
            raise GatewayError(ERR_FORBIDDEN, f"{type_} is a host-lifecycle action and is not available from a phone")
        if type_ in GATEWAY_TYPES:
            raise GatewayError(ERR_UNKNOWN_TYPE, f"{type_} is not implemented on this gateway")
        if type_ not in PROXIED_TYPES:
            raise GatewayError(
                ERR_FORBIDDEN,
                f"{type_!r} is not on this gateway's allow-list; the desktop can still use it",
            )

        self._scope(device, "chat")
        category = classify(type_)
        self.limiter.check(device.id, category, _LIMITS[category])
        if type_ in MUTATE_LIMITS or category in {"chat", "mutate"}:
            self.audit.append(f"daemon.{type_}", device_id=device.id, device_name=device.name,
                              detail=_proxy_detail(value))
        # The daemon ignores fields it does not know, so `rid` travels with the
        # request and the phone keeps it for its own log. Responses are not
        # rewritten: engine frames are the engine's contract, and a chat stream
        # must stay byte-identical to what the desktop sees.
        await link.send(value)

    async def _gateway_op(
        self,
        device: Device,
        link: DaemonLink,
        ws: ServerConnection,
        value: dict,
        type_: str,
        rid: str | None,
    ) -> None:
        if type_ == "gw_exec":
            self._scope(device, "exec")
            self.limiter.check(device.id, "exec", _LIMITS["exec"])
            self.audit.append("exec", device_id=device.id, device_name=device.name,
                              detail={"command": value.get("command", ""),
                                      "cwd": value.get("cwd", ""),
                                      "mode": value.get("mode", "")})
            timeout = value.get("timeout", self.config.effective_command_timeout)
            if isinstance(timeout, bool) or not isinstance(timeout, (int, float)):
                raise GatewayError(ERR_INVALID_ARGUMENT, "timeout must be a number of seconds")
            result = await run_exec(
                command=str(value.get("command", "")),
                cwd=str(value.get("cwd") or ""),
                session_id=value.get("session_id"),
                timeout=float(timeout),
                requested_mode=value.get("mode"),
                confirm_danger=bool(value.get("confirm_danger", False)),
                confirm_risky=bool(value.get("confirm_risky", False)),
                config=self.config,
                device_id=device.id,
            )
            await _send(ws, frame("gw_exec_result", rid, **_exec_payload(result)))
            return

        if type_ == "gw_exec_cancel":
            self._scope(device, "exec")
            exec_id = str(value.get("exec_id", ""))
            if not exec_id:
                raise GatewayError(ERR_INVALID_ARGUMENT, "exec_id is required")
            cancelled = await self.execs.cancel(device.id, exec_id)
            self.audit.append("exec.cancel", device_id=device.id, device_name=device.name,
                              detail={"exec_id": exec_id, "cancelled": cancelled})
            await _send(ws, frame("gw_exec_cancelled", rid, exec_id=exec_id, cancelled=cancelled))
            return

        if type_ == "gw_read_file":
            self._scope(device, "files")
            self.limiter.check(device.id, "read", _LIMITS["read"])
            result = read_file(
                str(value.get("path", "")),
                start_line=int(value.get("start_line", 1) or 1),
                line_limit=_optional_int(value.get("line_limit")),
            )
            await _send(ws, frame("gw_file_content", rid, **result.__dict__))
            return

        if type_ == "gw_write_file":
            self._scope(device, "files")
            self.limiter.check(device.id, "mutate", _LIMITS["mutate"])
            self.audit.append("file.write", device_id=device.id, device_name=device.name,
                              detail={"path": value.get("path", ""), "bytes": len(str(value.get("content", "")))})
            result = write_file(
                str(value.get("path", "")),
                value.get("content"),
                cwd=str(value.get("cwd") or ""),
                session_id=value.get("session_id"),
                config=self.config,
                expected_sha256=value.get("expected_sha256"),
                create_parents=bool(value.get("create_parents", False)),
                mode=value.get("mode"),
                confirm_danger=bool(value.get("confirm_danger", False)),
            )
            await _send(ws, frame("gw_file_written", rid, **result.__dict__))
            return

        if type_ == "gw_delete_file":
            self._scope(device, "files")
            self.limiter.check(device.id, "mutate", _LIMITS["mutate"])
            self.audit.append("file.delete", device_id=device.id, device_name=device.name,
                              detail={"path": value.get("path", "")})
            result = delete_file(
                str(value.get("path", "")),
                cwd=str(value.get("cwd") or ""),
                session_id=value.get("session_id"),
                config=self.config,
                mode=value.get("mode"),
                confirm_danger=bool(value.get("confirm_danger", False)),
            )
            await _send(ws, frame("gw_file_deleted", rid, **result))
            return

        if type_ == "gw_config_get":
            self._scope(device, "admin")
            self.limiter.check(device.id, "config", _LIMITS["config"])
            await _send(ws, frame("gw_config", rid, **config_api.snapshot(self.config)))
            return

        if type_ == "gw_config_set":
            self._scope(device, "admin")
            self.limiter.check(device.id, "config", _LIMITS["config"])
            patch = value.get("patch") or {}
            gateway_patch = value.get("gateway") or {}
            details: dict[str, Any] = {}
            if patch:
                self.audit.append("config.set", device_id=device.id, device_name=device.name,
                                  detail={"engine_keys": sorted(patch)})
                details["engine"] = config_api.apply_patch(patch, device_id=device.id)
            if gateway_patch:
                self.audit.append("config.set", device_id=device.id, device_name=device.name,
                                  detail={"gateway_keys": sorted(gateway_patch)})
                details["gateway"] = config_api.apply_gateway_patch(gateway_patch)
                # The persisted options are the ones the next request reads; the
                # in-memory copy must not keep answering from the old file.
                self.config = load_config()
            if not details:
                raise GatewayError(ERR_INVALID_ARGUMENT, "patch or gateway must be present")
            await _send(ws, frame("gw_config_saved", rid, **details))
            return

        if type_ == "gw_logs_tail":
            self._scope(device, "admin")
            self.limiter.check(device.id, "logs", _LIMITS["logs"])
            lines = _optional_int(value.get("lines")) or 200
            result = logs.tail(str(value.get("source", "daemon")),
                               lines=lines,
                               config=self.config)
            await _send(ws, frame("gw_logs", rid, **result))
            return

        if type_ == "gw_devices":
            self._scope(device, "admin")
            self.limiter.check(device.id, "read", _LIMITS["read"])
            snapshots = {snap.device_id: snap for snap in self.links.snapshot()}
            devices = []
            for item in self.devices.public_devices():
                snap = snapshots.get(item["id"])
                item = dict(item)
                item["connected"] = bool(snap and snap.attached)
                item["link_open"] = bool(snap and not snap.closed)
                item["active_turn"] = bool(snap and snap.active)
                item["last_seq"] = snap.seq if snap else 0
                devices.append(item)
            await _send(ws, frame("gw_devices", rid, devices=devices, max_devices=self.config.max_devices))
            return

        if type_ == "gw_revoke_device":
            self._scope(device, "admin")
            target = str(value.get("device_id", "")).strip()
            if not target:
                raise GatewayError(ERR_INVALID_ARGUMENT, "device_id is required")
            if target == device.id:
                raise GatewayError(ERR_FORBIDDEN, "use the host to revoke the device you are using")
            revoked = self.devices.revoke(target)
            if revoked:
                link_to_close = self.links.get(target)
                if link_to_close is not None:
                    await link_to_close.close()
                # A live socket keeps its `Device` object, so clearing the token
                # alone would leave it talking. Close every socket that device
                # attached, and it must pair again.
                for socket in list(self._sockets.get(target, set())):
                    try:
                        await socket.close(code=1008, reason="device revoked")
                    except Exception:
                        pass
                self._sockets.pop(target, None)
            self.audit.append("device.revoke", device_id=device.id, device_name=device.name,
                              decision="allow" if revoked else "missing", detail={"target": target})
            await _send(ws, frame("gw_device_revoked", rid, device_id=target, revoked=revoked))
            return

        if type_ == "gw_audit_tail":
            self._scope(device, "admin")
            self.limiter.check(device.id, "logs", _LIMITS["logs"])
            result = self.audit.tail(int(value.get("lines", 200) or 200))
            await _send(ws, frame("gw_audit", rid, **result))
            return

        if type_ == "gw_status":
            self._scope(device, "chat")
            snaps = self.links.snapshot()
            await _send(ws, frame(
                "gw_status",
                rid,
                uptime_seconds=int(time.time() - self.started_at),
                devices=len(self.devices.devices()),
                links=[
                    {
                        "device_id": snap.device_id,
                        "seq": snap.seq,
                        "attached": snap.attached,
                        "active": snap.active,
                        "closed": snap.closed,
                        "engine_version": snap.engine_version,
                    }
                    for snap in snaps
                ],
                queue_frames=QUEUE_FRAMES,
            ))
            return

        raise GatewayError(ERR_UNKNOWN_TYPE, f"unknown gateway operation {type_!r}")

    def _scope(self, device: Device, scope: str) -> None:
        if scope not in device.scopes:
            raise GatewayError(
                ERR_FORBIDDEN,
                f"this device was paired without the {scope!r} scope",
            )


# ── helpers ──────────────────────────────────────────────────────────

#: Kept separate from ``policy.MUTATING_TYPES`` so the audit line can be
#: produced without importing the policy's full vocabulary here twice.
MUTATE_LIMITS = {"task", "cancel", "approval_response", "compact", "clear_session",
                 "delete_session", "rename_session"}


def _proxy_detail(value: dict) -> dict:
    """Metadata for the audit line, never the message body or a key."""
    detail: dict[str, Any] = {}
    for key in ("session_id", "cwd", "model", "mode", "op", "task_id", "name"):
        item = value.get(key)
        if isinstance(item, (str, int, float, bool)):
            detail[key] = item
    message = value.get("message")
    if isinstance(message, str):
        detail["message_chars"] = len(message)
    return detail


def _exec_payload(result) -> dict:
    return {
        "exec_id": result.exec_id,
        "cwd": result.cwd,
        "mode": result.mode,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "exit_code": result.exit_code,
        "signal": result.signal,
        "timed_out": result.timed_out,
        "sandbox": result.sandbox,
        "duration_ms": result.duration_ms,
        "stdout_truncated": result.stdout_truncated,
        "stderr_truncated": result.stderr_truncated,
    }


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool):
        raise GatewayError(ERR_INVALID_ARGUMENT, "line_limit must be an integer >= 1")
    try:
        number = int(value)
    except (TypeError, ValueError, OverflowError) as exc:
        raise GatewayError(ERR_INVALID_ARGUMENT, "line_limit must be an integer >= 1") from exc
    return number


def _address(ws: ServerConnection) -> str:
    peer = getattr(ws, "remote_address", None)
    if isinstance(peer, tuple) and peer:
        return str(peer[0])
    return "unknown"


async def _send(ws: ServerConnection, payload: dict) -> bool:
    try:
        await ws.send(json.dumps(payload, ensure_ascii=False))
        return True
    except (ConnectionClosed, OSError):
        return False


def main_serve(config: GatewayConfig | None = None) -> int:
    """Synchronous entry used by the CLI."""
    server = GatewayServer(config)
    try:
        asyncio.run(server.serve())
    except KeyboardInterrupt:
        return 0
    return 0
