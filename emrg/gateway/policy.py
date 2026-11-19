"""What a paired phone may ask the daemon, and how often.

The proxy is an allow-list, not a filter: a frame type the gateway has not
listed is refused, so an upstream addition cannot become reachable on a phone
before someone decides it should be. Two daemon frames are deliberately never
forwarded: ``shutdown`` and ``init_auto_evolve`` (host lifecycle, not a
session) and ``delete_rant`` (a destructive ledger edit the desktop GUI owns;
no phone screen sends it). A phone has no business doing any of them.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque
from dataclasses import dataclass

from emrg.gateway.protocol import GatewayError, ERR_FORBIDDEN, ERR_RATE_LIMITED

#: Every request type the daemon dispatches, minus the two host-lifecycle
#: frames and the gateway's own handshake. Kept in one place so a test can
#: compare it with the daemon's dispatch table when the engine is upgraded.
PROXIED_TYPES = frozenset(
    {
        "task",
        "cancel",
        "approval_response",
        "ping",
        "compact",
        "clear_session",
        "list_sessions",
        "list_all_sessions",
        "resume_session",
        "rename_session",
        "delete_session",
        "resolve_session_cwd",
        "list_history",
        "list_input_history",
        "rewind_session",
        "list_memories",
        "read_memory",
        "list_files",
        "read_file",
        "list_models",
        "set_model",
        "set_sandbox",
        "set_sandbox_roots",
        "list_rants",
        "rant",
        "evolution_summary",
        "list_tasks",
        "task_create",
        "task_update",
        "task_delete",
        "task_template_list",
        "task_template_create",
        "task_template_update",
        "task_template_delete",
        "task_vibe_check",
        "trigger_task",
        "list_skills",
        "skills_available",
        "skills_install",
        "skills_update",
        "github_status",
        "github_connect",
        "github_connect_web",
        "github_disconnect",
        "list_agents",
        "remove_agent",
    }
)

#: Frames a phone must never send. ``shutdown`` / ``init_auto_evolve`` are
#: host-lifecycle frames: a phone must not stop the daemon or kick the
#: auto-evolve loop; those stay on the desktop/TUI. ``delete_rant`` rewrites
#: the rant ledger; the desktop GUI owns that delete, no phone screen sends
#: it. ``auth`` is handled before dispatch.
BLOCKED_TYPES = frozenset({"shutdown", "init_auto_evolve", "delete_rant", "auth"})

#: Frames that change state. They are audit-logged (without arguments) and
#: rate-limited more tightly than reads.
MUTATING_TYPES = frozenset(
    {
        "task",
        "cancel",
        "approval_response",
        "compact",
        "clear_session",
        "delete_session",
        "rename_session",
        "rewind_session",
        "set_model",
        "set_sandbox",
        "set_sandbox_roots",
        "rant",
        "task_create",
        "task_update",
        "task_delete",
        "task_template_create",
        "task_template_update",
        "task_template_delete",
        "task_vibe_check",
        "trigger_task",
        "skills_install",
        "skills_update",
        "github_connect",
        "github_connect_web",
        "github_disconnect",
        "remove_agent",
    }
)

#: Gateway-only request types. They never reach the daemon as-is.
GATEWAY_TYPES = frozenset(
    {
        "gw_auth",
        "gw_attach",
        "gw_ping",
        "gw_exec",
        "gw_exec_cancel",
        "gw_read_file",
        "gw_write_file",
        "gw_config_get",
        "gw_config_set",
        "gw_logs_tail",
        "gw_devices",
        "gw_revoke_device",
        "gw_audit_tail",
    }
)

READ_LIMIT = 120  # frames / minute / device
MUTATE_LIMIT = 30
EXEC_LIMIT = 10
CONFIG_LIMIT = 6
LOG_LIMIT = 30


def classify(type_: str) -> str:
    if type_ in {"task", "cancel", "approval_response", "ping", "compact", "clear_session"}:
        return "chat"
    if type_ in MUTATING_TYPES:
        return "mutate"
    return "read"


def allowed(type_: str) -> bool:
    return type_ in PROXIED_TYPES


@dataclass
class Limit:
    limit: int
    window: float = 60.0


class RateLimiter:
    """Per-device sliding window over the last minute.

    A gateway is a long-lived process and a phone can loop on a failed request;
    the limiter is the simplest way to keep one device from turning into a
    denial-of-service against the daemon. It is intentionally in-memory: a
    gateway restart clearing the counters is harmless, and persisting them
    would add a write to every frame.
    """

    def __init__(self, clock=time.monotonic) -> None:
        self._clock = clock
        self._hits: dict[tuple[str, str], deque[float]] = defaultdict(deque)

    def check(self, device_id: str, category: str, limit: Limit) -> None:
        now = self._clock()
        bucket = self._hits[(device_id, category)]
        cutoff = now - limit.window
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= limit.limit:
            retry = max(0.0, limit.window - (now - bucket[0]))
            raise GatewayError(
                ERR_RATE_LIMITED,
                f"too many {category} requests; retry in {retry:.0f}s",
                retryable=True,
                detail={"retry_after": round(retry, 1)},
            )
        bucket.append(now)

    def reset(self, device_id: str | None = None) -> None:
        if device_id is None:
            self._hits.clear()
            return
        for key in [key for key in self._hits if key[0] == device_id]:
            self._hits.pop(key, None)
