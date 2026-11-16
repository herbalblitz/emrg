"""Host command execution for a paired phone.

The gateway does not implement a shell of its own. It resolves the *same*
:class:`~emrg.sandbox.policy.SandboxPolicy` the daemon's shell tool would use for
the session, then calls :func:`emrg.tools.bash_tool_v2.run_command` — the one
execution seam the engine already has. That gives the phone the identical
confinement story as the desktop (Seatbelt, bwrap, Windows ACL runner), the same
process-group kill on timeout, and the same fail-closed behaviour when a
confinement backend is missing.

Two things are the gateway's own:

* **Tier selection.** A session's declared tier wins; a session that never
  declared one gets the gateway's ``default_exec_mode`` (``workspace-write``),
  which is deliberately narrower than the daemon's host-session default. A phone
  cannot widen a session's tier here — that stays a host decision in the
  desktop UI or ``/sandbox``.
* **Danger gate.** ``danger-full-access`` needs both an opt-in in
  ``gateway/config.json`` and an explicit confirmation in the request, so a
  stolen device token is not an unconfined shell.
"""

from __future__ import annotations

import asyncio
import os
import re
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from emrg.config import config_dir
from emrg.gateway.config import (
    MAX_EXEC_ERROR,
    MAX_EXEC_OUTPUT,
    GatewayConfig,
)
from emrg.gateway.protocol import (
    ERR_APPROVAL_REQUIRED,
    ERR_INVALID_ARGUMENT,
    ERR_NOT_FOUND,
    ERR_POLICY_REFUSED,
    ERR_SANDBOX_UNAVAILABLE,
    GatewayError,
)
from emrg.sandbox.contract import SandboxUnavailableError
from emrg.sandbox.policy import DANGER_FULL_ACCESS, SANDBOX_MODES, resolve_policy
from emrg.session import Session, UnsafeSessionId, session_dir


#: Commands a phone must explicitly confirm before the host runs them. The list
#: is about *irreversibility*, not policy: a workspace-write run is already
#: confined, but a target outside the workspace, a bootloader write or a fork
#: bomb is not undone by a sandbox denial that never fired. The daemon's own
#: approval flow covers tool calls the agent makes; this covers text a person
#: typed on a phone, where the keyboard makes a slip easy and the screen makes
#: the consequence hard to see.
RISKY_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("recursive delete of a root/home", re.compile(r"\brm\s+-(?:[a-zA-Z]*[rf][a-zA-Z]*\s+){1,2}(?:/|~|\$HOME|/\*)\s*(?:$|[;&|])")),
    ("filesystem creation", re.compile(r"\bmkfs(?:\.[a-z0-9]+)?\b")),
    ("raw device write", re.compile(r"\bdd\b[^\n]*\bof\s*=\s*/dev/")),
    ("fork bomb", re.compile(r":\s*\(\s*\)\s*\{")),
    ("host power state", re.compile(r"\b(?:shutdown|reboot|poweroff|halt)\b")),
    ("windows disk format", re.compile(r"\b(?:diskpart|format\s+[a-z]:)", re.IGNORECASE)),
    ("recursive permission widening", re.compile(r"\bchmod\s+-R\s+777\s+/\s*(?:$|[;&|])")),
    ("pipe-to-shell install", re.compile(r"\b(?:curl|wget)\b[^\n|]*\|\s*(?:sudo\s+)?(?:ba?sh|sh|zsh)\b")),
    ("git history rewrite on a remote", re.compile(r"\bgit\s+push\b[^\n]*\s(?:--force|-f)\b")),
)


def risky_reasons(command: str) -> list[str]:
    """The irreversible-action labels this command text matches."""
    return [label for label, pattern in RISKY_PATTERNS if pattern.search(command)]


@dataclass(frozen=True)
class ExecResult:
    exec_id: str
    cwd: str
    mode: str
    stdout: str
    stderr: str
    exit_code: int | None
    signal: int | None
    timed_out: bool
    sandbox: dict
    duration_ms: int
    stdout_truncated: bool
    stderr_truncated: bool


class ExecManager:
    """Tracks running host commands so a phone can cancel its own run.

    One entry per ``exec_id``. The ids are random and scoped to one device by
    the caller, so a phone cannot cancel a command another device started.
    """

    def __init__(self) -> None:
        self._tasks: dict[str, asyncio.Task] = {}

    def running(self, device_id: str) -> list[str]:
        prefix = f"{device_id}:"
        return [key.split(":", 1)[1] for key in self._tasks if key.startswith(prefix)]

    async def cancel(self, device_id: str, exec_id: str) -> bool:
        task = self._tasks.get(f"{device_id}:{exec_id}")
        if task is None:
            return False
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass
        return True


def _session_for(session_id: str | None, cwd: str) -> Session | None:
    """Load the session this command belongs to, when it exists.

    ``Session.load`` creates the session directory as a side effect of
    construction (its ``__init__`` mkdirs), so the presence check comes first:
    a command addressed to a deleted session must not silently recreate it.
    """
    if not session_id:
        return None
    root = Path(cwd).expanduser()
    try:
        directory = session_dir(root, session_id)
    except UnsafeSessionId as exc:
        raise GatewayError(ERR_INVALID_ARGUMENT, str(exc)) from exc
    if not directory.is_dir():
        raise GatewayError(ERR_NOT_FOUND, f"session not found: {session_id}")
    try:
        return Session.load(session_id, root)
    except (OSError, ValueError, UnsafeSessionId) as exc:
        raise GatewayError(ERR_NOT_FOUND, f"session cannot be loaded: {exc}") from exc


def resolve_mode(
    session: Session | None,
    config: GatewayConfig,
    requested: str | None,
    *,
    confirm_danger: bool,
) -> str:
    """The tier one phone command runs at.

    The session decides first. A request may *narrow* the tier (never widen it):
    a phone that asks for ``read-only`` inside a ``workspace-write`` session gets
    ``read-only``, while a phone that asks for ``workspace-write`` inside a
    ``read-only`` session keeps ``read-only``. If the session never declared one,
    the request may choose among the safe tiers; ``danger-full-access`` still
    needs the host opt-in and the explicit confirmation.
    """
    if requested is not None and requested not in SANDBOX_MODES:
        raise GatewayError(
            ERR_INVALID_ARGUMENT,
            f"unknown sandbox mode {requested!r} (expected one of {', '.join(SANDBOX_MODES)})",
        )
    declared = session.sandbox if session is not None else None
    base = declared or config.default_exec_mode
    if requested is None:
        mode = base
    elif declared is None:
        mode = requested
    else:
        order = {"read-only": 0, "workspace-write": 1, "danger-full-access": 2}
        mode = requested if order.get(requested, 99) <= order.get(declared, 99) else declared
    if mode == DANGER_FULL_ACCESS:
        if not config.allow_danger_exec:
            raise GatewayError(
                ERR_POLICY_REFUSED,
                "danger-full-access is disabled on this host; set allow_danger_exec in "
                "~/.emrg/gateway/config.json to permit it",
            )
        if not confirm_danger:
            raise GatewayError(
                ERR_POLICY_REFUSED,
                "danger-full-access requires confirm_danger: true in this request",
            )
    return mode


def _truncate(text: str, limit: int) -> tuple[str, bool]:
    """Cut at the limit without splitting a multi-byte character."""
    encoded = text.encode("utf-8", errors="replace")
    if len(encoded) <= limit:
        return text, False
    cut = encoded[:limit].decode("utf-8", errors="ignore")
    return cut, True


async def run(
    *,
    command: str,
    cwd: str,
    session_id: str | None,
    timeout: float,
    requested_mode: str | None,
    confirm_danger: bool,
    confirm_risky: bool,
    config: GatewayConfig,
    device_id: str,
) -> ExecResult:
    """Run one host command and return its structured result."""
    if not isinstance(command, str) or not command.strip():
        raise GatewayError(ERR_INVALID_ARGUMENT, "command must be a non-empty string")
    reasons = risky_reasons(command)
    if reasons and not confirm_risky:
        raise GatewayError(
            ERR_APPROVAL_REQUIRED,
            "this command needs explicit confirmation on the phone: " + ", ".join(reasons),
            detail={"reasons": reasons, "command": command[:400]},
        )
    root = Path(str(cwd or "")).expanduser()
    if not root.is_absolute():
        raise GatewayError(ERR_INVALID_ARGUMENT, "cwd must be an absolute path")
    root = root.resolve()
    if not root.is_dir():
        raise GatewayError(ERR_NOT_FOUND, f"working directory not found: {root}")

    session = _session_for(session_id, str(root))
    mode = resolve_mode(session, config, requested_mode, confirm_danger=confirm_danger)
    policy = resolve_policy(
        mode=mode,
        workspace_root=str(root),
        session_id=session.session_id if session is not None else None,
        extra_grants=session.sandbox_grants if session is not None else (),
        withheld_roots=session.sandbox_withheld if session is not None else (),
    )
    bounded = max(1.0, min(float(timeout), config.effective_command_timeout))
    exec_id = uuid.uuid4().hex[:16]
    started = time.monotonic()

    from emrg.tools.bash_tool_v2 import run_command

    try:
        result = await run_command(
            command,
            policy=policy,
            workdir=str(root),
            timeout=bounded,
        )
    except SandboxUnavailableError as exc:
        raise GatewayError(
            ERR_SANDBOX_UNAVAILABLE,
            f"the {mode} sandbox could not be established, so the command did not run: {exc}",
            retryable=False,
        ) from exc
    except FileNotFoundError as exc:
        # The inner shell is missing on the host (a stripped container, a
        # broken PATH). This is a host install problem, not a policy refusal.
        raise GatewayError(
            ERR_SANDBOX_UNAVAILABLE,
            f"the shell could not be started: {exc}",
        ) from exc
    except OSError as exc:
        raise GatewayError(ERR_INTERNAL, f"the command could not be started: {exc}") from exc
    except asyncio.CancelledError:
        raise
    except Exception as exc:  # pragma: no cover - defensive
        raise GatewayError(ERR_INTERNAL, f"the command failed: {exc}") from exc

    stdout, stdout_cut = _truncate(result.stdout or "", MAX_EXEC_OUTPUT)
    stderr, stderr_cut = _truncate(result.stderr or "", MAX_EXEC_ERROR)
    return ExecResult(
        exec_id=exec_id,
        cwd=str(root),
        mode=mode,
        stdout=stdout,
        stderr=stderr,
        exit_code=result.exit_code,
        signal=result.signal,
        timed_out=result.timed_out,
        sandbox=result.sandbox,
        duration_ms=int((time.monotonic() - started) * 1000),
        stdout_truncated=stdout_cut,
        stderr_truncated=stderr_cut,
    )


def default_cwd() -> str:
    """The cwd a phone command uses when it names none: the host's home."""
    return str(Path(os.path.expanduser("~")))


def protected_gateway_dir() -> str:
    return str(config_dir() / "gateway")
