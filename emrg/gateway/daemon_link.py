"""The gateway's connection to the real EMRG daemon.

One upstream WebSocket per paired device, kept alive **independently of the
phone's socket**. This is the whole reason a phone can survive a tunnel, a
screen lock or a process kill without losing a running turn: the daemon cancels
its tool task when the *upstream* connection closes, so the gateway must be the
party that stays connected. The phone attaches and detaches from that link; it
does not own it.

Every frame gets a monotonically increasing ``gw_seq`` and enters a bounded
ring buffer. A reconnecting phone sends the last sequence it saw and receives
the gap, which is enough for a short network drop; a longer absence is filled
from ``list_history`` by the client, not invented here.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any

from websockets.exceptions import ConnectionClosed

from emrg.gateway.protocol import ERR_DAEMON_UNAVAILABLE, GatewayError, frame

logger = logging.getLogger("emrg.gateway")

BUFFER_FRAMES = 1000


@dataclass
class LinkSnapshot:
    device_id: str
    seq: int
    attached: int
    active: bool
    closed: bool
    last_activity: float
    engine_version: str = ""


class DaemonLink:
    def __init__(
        self,
        device_id: str,
        *,
        idle_timeout: float,
        on_close=None,
    ) -> None:
        self.device_id = device_id
        self.idle_timeout = idle_timeout
        self._on_close = on_close
        self._ws = None
        self._reader_task: asyncio.Task | None = None
        self._queues: set[asyncio.Queue] = set()
        self._buffer: deque[tuple[int, dict]] = deque(maxlen=BUFFER_FRAMES)
        self._seq = 0
        self._send_lock = asyncio.Lock()
        self.closed = False
        self.active = False
        self.last_activity = time.monotonic()
        self.engine_version = ""
        self._version_event = asyncio.Event()
        self._connect_lock = asyncio.Lock()

    # ── lifecycle ────────────────────────────────────────────────────

    async def start(self) -> None:
        async with self._connect_lock:
            if self._ws is not None and not self.closed:
                return
            await _ensure_daemon()
            from emrg.connect import AuthError, connect_to_server

            last_error: Exception | None = None
            for attempt in range(8):
                try:
                    self._ws = await connect_to_server()
                    break
                except AuthError as exc:
                    # A token mismatch is not transient, and retrying it eight
                    # times only delays a message the host must act on.
                    raise GatewayError(
                        ERR_DAEMON_UNAVAILABLE,
                        f"the gateway cannot authenticate to the host daemon: {exc}. "
                        "Restart the gateway after the daemon has written its token.",
                        retryable=False,
                    ) from exc
                except (ConnectionRefusedError, OSError, TimeoutError) as exc:
                    last_error = exc
                    await asyncio.sleep(0.5)
            else:
                raise GatewayError(
                    ERR_DAEMON_UNAVAILABLE,
                    f"the EMRG daemon did not accept a connection: {last_error}",
                    retryable=True,
                )
            self.closed = False
            self._reader_task = asyncio.create_task(self._reader(), name=f"gw-link-{self.device_id}")
            # Ask for the identity frame the daemon already answers. It gives
            # the hello payload a real engine version and proves the token was
            # accepted before the phone sees "connected".
            await self.send({"type": "ping"})

    async def _reader(self) -> None:
        assert self._ws is not None
        try:
            while True:
                raw = await self._ws.recv()
                if isinstance(raw, bytes):
                    try:
                        raw = raw.decode("utf-8")
                    except UnicodeDecodeError:
                        continue
                try:
                    value = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                if not isinstance(value, dict):
                    continue
                if value.get("type") == "pong":
                    version = value.get("current_version") or value.get("installed_version")
                    if isinstance(version, str) and version:
                        self.engine_version = version
                        self._version_event.set()
                self._seq += 1
                value["gw_seq"] = self._seq
                self._buffer.append((self._seq, value))
                if value.get("type") == "turn_start":
                    self.active = True
                elif value.get("type") in {"done", "cancelled"}:
                    self.active = False
                self.last_activity = time.monotonic()
                self._fanout(value)
        except ConnectionClosed:
            pass
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("daemon link reader failed for %s", self.device_id, exc_info=True)
        finally:
            self.closed = True
            self._ws = None
            notice = frame(
                "gw_error",
                code=ERR_DAEMON_UNAVAILABLE,
                message="the host daemon connection closed",
                retryable=True,
            )
            self._seq += 1
            notice["gw_seq"] = self._seq
            self._buffer.append((self._seq, notice))
            self._fanout(notice)
            if self._on_close is not None:
                try:
                    self._on_close(self)
                except Exception:
                    logger.debug("link close callback failed", exc_info=True)

    async def ensure_version(self, timeout: float = 1.0) -> str:
        """The engine version, waiting briefly for the first pong.

        Only the greeting wants this: an empty version on a screen is a worse
        answer than one second of latency on the very first attach. A link that
        already has the value returns at once, and a daemon that never answers
        leaves the field empty rather than delaying the phone.
        """
        if self.engine_version:
            return self.engine_version
        try:
            await asyncio.wait_for(self._version_event.wait(), timeout)
        except asyncio.TimeoutError:
            pass
        return self.engine_version

    # ── phone attachment ─────────────────────────────────────────────

    def attach(self, queue: asyncio.Queue, since_seq: int = 0) -> int:
        self._queues.add(queue)
        self.last_activity = time.monotonic()
        replay = 0
        if since_seq > 0:
            oldest = self._buffer[0][0] if self._buffer else self._seq + 1
            if since_seq < oldest - 1:
                _put(queue, frame(
                    "gw_gap",
                    message="the reconnect gap is older than the buffer; reload session history",
                    oldest_seq=oldest,
                    last_seq=self._seq,
                ))
            for seq, item in list(self._buffer):
                if seq > since_seq:
                    _put(queue, item)
                    replay += 1
        return replay

    def detach(self, queue: asyncio.Queue) -> None:
        self._queues.discard(queue)
        self.last_activity = time.monotonic()

    @property
    def attached(self) -> int:
        return len(self._queues)

    # ── frames ───────────────────────────────────────────────────────

    async def send(self, value: dict) -> None:
        if self.closed or self._ws is None:
            raise GatewayError(
                ERR_DAEMON_UNAVAILABLE,
                "the host daemon is not connected",
                retryable=True,
            )
        payload = json.dumps(value, ensure_ascii=False)
        async with self._send_lock:
            await self._ws.send(payload)
        self.last_activity = time.monotonic()

    def _fanout(self, value: dict) -> None:
        for queue in list(self._queues):
            _put(queue, value)

    # ── reaping ──────────────────────────────────────────────────────

    def should_reap(self, now: float) -> bool:
        if self._queues:
            return False
        if self.active:
            return False
        return (now - self.last_activity) >= self.idle_timeout

    async def close(self) -> None:
        self.closed = True
        if self._reader_task is not None:
            self._reader_task.cancel()
            try:
                await self._reader_task
            except (asyncio.CancelledError, Exception):
                pass
            self._reader_task = None
        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None

    def snapshot(self) -> LinkSnapshot:
        return LinkSnapshot(
            device_id=self.device_id,
            seq=self._seq,
            attached=len(self._queues),
            active=self.active,
            closed=self.closed,
            last_activity=self.last_activity,
            engine_version=self.engine_version,
        )


class LinkRegistry:
    """Owns the per-device links and the idle sweep."""

    def __init__(self, *, idle_timeout: float) -> None:
        self.idle_timeout = idle_timeout
        self._links: dict[str, DaemonLink] = {}
        self._lock = asyncio.Lock()

    async def get_or_create(self, device_id: str) -> DaemonLink:
        async with self._lock:
            link = self._links.get(device_id)
            if link is not None and link.closed:
                self._links.pop(device_id, None)
                link = None
            if link is None:
                link = DaemonLink(
                    device_id,
                    idle_timeout=self.idle_timeout,
                    on_close=lambda closed: self._links.pop(closed.device_id, None),
                )
                self._links[device_id] = link
                await link.start()
            return link

    def get(self, device_id: str) -> DaemonLink | None:
        return self._links.get(device_id)

    async def reap(self) -> None:
        now = time.monotonic()
        for link in list(self._links.values()):
            if link.should_reap(now) or link.closed:
                self._links.pop(link.device_id, None)
                await link.close()

    async def close_all(self) -> None:
        links = list(self._links.values())
        self._links.clear()
        for link in links:
            await link.close()

    def snapshot(self) -> list[LinkSnapshot]:
        return [link.snapshot() for link in self._links.values()]


def _put(queue: asyncio.Queue, value: dict) -> None:
    """Never let a slow/dead phone apply backpressure to the daemon link."""
    try:
        queue.put_nowait(value)
    except asyncio.QueueFull:
        try:
            queue.get_nowait()
        except asyncio.QueueEmpty:
            pass
        try:
            queue.put_nowait(value)
        except asyncio.QueueFull:
            pass


async def _ensure_daemon() -> None:
    """Start the daemon when it is not already listening.

    The desktop app normally owns this lifecycle. The gateway is also runnable
    on its own (a headless Windows box, a Linux host), so it asks the same
    manager the TUI asks instead of duplicating spawn logic.
    """
    from emrg.client import daemon_manager

    try:
        if daemon_manager.is_running():
            return
    except Exception:
        logger.debug("daemon liveness probe failed", exc_info=True)
    await daemon_manager.start_daemon()
