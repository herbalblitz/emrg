"""Single canonical registry for the remote companion scope allowlist.

This is the one place the 20 companion scopes live. ``companion_protocol``,
``companion_pairing_v2`` and ``companion_runtime`` all import from here so the
allowlist cannot drift across hard-coded copies again (it used to live in
three places, capped at 3 scopes).

Scope rules: one scope per screen unless two screens are views over the
identical operation set (Editor shares ``files.read`` + ``files.write``).
No wildcard scope exists anywhere. ``tools.monitor`` has no request frame:
it gates receiving tool-activity stream frames on the chat subscription.
"""
from __future__ import annotations

#: Maximum number of scopes one pairing may carry (== len(ALLOWED_SCOPES)).
MAX_SCOPES = 20

#: The 20 granted scopes. The 3 original scopes are unchanged.
ALLOWED_SCOPES: frozenset[str] = frozenset({
    "status",
    "sessions.list",
    "sessions.manage",
    "chat.send",
    "tools.monitor",
    "files.read",
    "files.write",
    "terminal.exec",
    "sandbox.manage",
    "hosts.list",
    "memory.read",
    "models.manage",
    "rant.submit",
    "evolution.read",
    "tasks.manage",
    "skills.manage",
    "logs.read",
    "audit.read",
    "devices.manage",
    "settings.manage",
})

#: Companion wire frame type -> the scope that gates it. Batch B/C frames are
#: listed here so both sides agree on the frozen wire contract now; their
#: server dispatch lands in later batches (the listener answers
#: ``not_implemented`` until then).
SCOPE_OPERATIONS: dict[str, frozenset[str]] = {
    "status": frozenset({"status"}),
    "sessions.list": frozenset({"sessions.list"}),
    "sessions.manage": frozenset({
        "sessions.resume", "sessions.rename", "sessions.delete",
        "sessions.clear", "sessions.compact", "sessions.rewind",
        "sessions.history", "sessions.resolve_cwd",
    }),
    "chat.send": frozenset({"chat.send", "chat.cancel", "chat.approve"}),
    # No request frame: gates receiving tool-activity stream frames.
    "tools.monitor": frozenset(),
    "files.read": frozenset({"files.list", "files.read"}),
    "files.write": frozenset({"files.write", "files.delete"}),
    "terminal.exec": frozenset({"terminal.exec", "terminal.exec_cancel"}),
    "sandbox.manage": frozenset({"sandbox.set", "sandbox.roots"}),
    "hosts.list": frozenset({"hosts.list"}),
    "memory.read": frozenset({"memory.list", "memory.read"}),
    "models.manage": frozenset({"models.list", "models.set"}),
    "rant.submit": frozenset({"rant.list", "rant.submit"}),
    "evolution.read": frozenset({"evolution.summary"}),
    "tasks.manage": frozenset({
        "tasks.list", "tasks.trigger", "tasks.create", "tasks.update",
        "tasks.delete", "tasks.templates", "tasks.template_create",
        "tasks.template_update", "tasks.template_delete",
    }),
    "skills.manage": frozenset({
        "skills.list", "skills.available", "skills.install", "skills.update",
    }),
    "logs.read": frozenset({"logs.tail"}),
    "audit.read": frozenset({"audit.tail"}),
    "devices.manage": frozenset({"devices.list", "devices.revoke"}),
    "settings.manage": frozenset({"settings.get", "settings.set"}),
}

#: Daemon stream frame types that describe tool activity. A companion
#: subscriber receives these only when its granted scopes include
#: ``tools.monitor``; every other turn frame reaches every subscriber of
#: the session. (``function`` is the tool-call record the live stream emits
#: alongside ``tool_start``/``tool_result``/``tool_end``.)
TOOL_ACTIVITY_TYPES: frozenset[str] = frozenset({
    "tool_start", "tool_result", "tool_end", "function",
})


def scope_for_frame(frame_type: str) -> str | None:
    """Return the scope gating one companion wire frame type, or None."""
    for scope, operations in SCOPE_OPERATIONS.items():
        if frame_type in operations:
            return scope
    return None
