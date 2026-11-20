"""Host command execution for a remote companion device.

A port of ``emrg/gateway/exec.py`` (reference branch ``feat/cross-platform``)
for the companion WSS listener, which has no gateway. The execution seam is
the same: :func:`emrg.tools.bash_tool_v2.run_command` under the same
:class:`~emrg.sandbox.policy.SandboxPolicy` the daemon's shell tool would
use for the session, so the phone gets the identical confinement story, the
same process-group kill on timeout, and the same fail-closed behaviour when
a confinement backend is missing.

Two companion differences from the gateway port:

* **Danger cap.** A remote companion is capped at ``workspace-write``
  unless the host explicitly opts a device into danger: ``danger-full-access``
  requires all three of host opt-in (``allow_danger_exec``), the device id
  in ``danger_device_ids``, and per-request ``confirm_danger``. A session
  that declares ``danger-full-access`` does **not** lift the cap on its own.
* **cwd is forced.** The working directory is always the device's bound
  workspace (the identity cwd); no client-supplied cwd is accepted.

Audit logging follows the daemon's ``companion_local_control`` rule:
operation + session/device id + command SHA-256 prefix, never full command
text (commands may contain secrets).
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from emrg.sandbox.contract import SandboxUnavailableError
from emrg.sandbox.policy import DANGER_FULL_ACCESS, SANDBOX_MODES, resolve_policy
from emrg.session import Session, UnsafeSessionId, session_dir

_LOG = logging.getLogger(__name__)

#: Longest single host command the terminal accepts (ported gateway value).
DEFAULT_COMMAND_TIMEOUT = 120.0
MAX_COMMAND_TIMEOUT = 600.0
#: How many bytes of a command's stdout/stderr are returned to the device.
MAX_EXEC_OUTPUT = 200_000
MAX_EXEC_ERROR = 50_000


class CompanionExecError(ValueError):
    """Safe exec refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


#: Commands a device must explicitly confirm before the host runs them
#: (ported verbatim from gateway exec.py — about irreversibility, not policy).
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


def _command_fingerprint(command: str) -> str:
    """First 16 hex chars of the command SHA-256 — audit identity, not content."""
    return hashlib.sha256(command.encode("utf-8")).hexdigest()[:16]


@dataclass(frozen=True)
class CompanionExecConfig:
    """Host-side exec policy for remote companion devices.

    ``allow_danger_exec`` is the host opt-in; ``danger_device_ids`` is the
    per-device opt-in. Both default to deny. There is no config file yet —
    the listener owns one default instance, and the host UI wires these
    fields in a later batch (settings.manage).
    """

    allow_danger_exec: bool = False
    danger_device_ids: frozenset[str] = frozenset()
    default_exec_mode: str = "workspace-write"
    command_timeout: float = DEFAULT_COMMAND_TIMEOUT

    @property
    def effective_command_timeout(self) -> float:
        return max(1.0, min(float(self.command_timeout), MAX_COMMAND_TIMEOUT))


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
    """Tracks running host commands so a device can cancel its own run.

    One entry per ``exec_id``. The ids are random and scoped to one device
    by the caller, so a device cannot cancel a command another device
    started. All audit logging for start/cancel happens in :func:`run` and
    here, with the command fingerprint — never the command text.
    """

    def __init__(self) -> None:
        self._tasks: dict[str, asyncio.Task] = {}

    def running(self, device_id: str) -> list[str]:
        prefix = f"{device_id}:"
        return [key.split(":", 1)[1] for key in self._tasks if key.startswith(prefix)]

    async def run_exec(self, *, device_id: str, command: str, cwd: str,
                       session_id: str | None, timeout: float | None,
                       requested_mode: str | None, confirm_danger: bool,
                       confirm_risky: bool,
                       config: CompanionExecConfig) -> ExecResult:
        """Run one command as a tracked task, so it can be cancelled.

        Parameters are explicit (no ``**kwargs`` forwarding): the
        repo's script-decode guard refuses opaque splats into a call
        named ``run``, and explicit parameters keep the call readable.
        """
        exec_id = uuid.uuid4().hex[:16]
        task = asyncio.create_task(run(
            exec_id=exec_id, device_id=device_id, command=command, cwd=cwd,
            session_id=session_id, timeout=timeout,
            requested_mode=requested_mode, confirm_danger=confirm_danger,
            confirm_risky=confirm_risky, config=config))
        self._tasks[f"{device_id}:{exec_id}"] = task
        try:
            return await task
        finally:
            self._tasks.pop(f"{device_id}:{exec_id}", None)

    async def cancel(self, device_id: str, exec_id: str) -> bool:
        task = self._tasks.get(f"{device_id}:{exec_id}")
        if task is None:
            return False
        _LOG.info("companion exec allowed: operation=%r session=%r device=%r",
                  "terminal.exec_cancel", exec_id, device_id)
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass
        return True


def _session_for(session_id: str | None, cwd: str) -> Session | None:
    """Load the session this command belongs to, when it exists.

    ``Session.load`` creates the session directory as a side effect of
    construction, so the presence check comes first: a command addressed
    to a deleted session must not silently recreate it.
    """
    if not session_id:
        return None
    root = Path(cwd).expanduser()
    try:
        directory = session_dir(root, session_id)
    except UnsafeSessionId as exc:
        raise CompanionExecError("invalid_argument", str(exc)) from exc
    if not directory.is_dir():
        raise CompanionExecError("not_found", f"session not found: {session_id}")
    try:
        return Session.load(session_id, root)
    except (OSError, ValueError, UnsafeSessionId) as exc:
        raise CompanionExecError("not_found", f"session cannot be loaded: {exc}") from exc


def resolve_mode(
    session: Session | None,
    config: CompanionExecConfig,
    requested: str | None,
    *,
    confirm_danger: bool,
    device_id: str = "",
) -> str:
    """The tier one companion command runs at.

    The session decides first. A request may *narrow* the tier (never widen
    it). If the session never declared one, the request may choose among the
    safe tiers. ``danger-full-access`` additionally requires the host
    opt-in, the device opt-in, and the explicit per-request confirmation —
    and a session that declares danger does not satisfy any of those three.
    """
    if requested is not None and requested not in SANDBOX_MODES:
        raise CompanionExecError(
            "invalid_argument",
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
            raise CompanionExecError(
                "policy_refused",
                "danger-full-access is disabled on this host; the host must "
                "opt in before a companion device may use it",
            )
        if device_id not in config.danger_device_ids:
            raise CompanionExecError(
                "policy_refused",
                "danger-full-access is not enabled for this device; the host "
                "must opt this device in",
            )
        if not confirm_danger:
            raise CompanionExecError(
                "policy_refused",
                "danger-full-access requires confirm_danger: true in this request",
            )
    # Companion cap: a session tier of danger-full-access must not leak
    # through when the device was never opted in — resolve_mode above
    # already refuses that combination, so reaching here at danger means
    # all three gates passed.
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
    exec_id: str,
    command: str,
    cwd: str,
    session_id: str | None,
    timeout: float | None,
    requested_mode: str | None,
    confirm_danger: bool,
    confirm_risky: bool,
    config: CompanionExecConfig,
    device_id: str,
) -> ExecResult:
    """Run one host command in the bound workspace and return its result.

    ``cwd`` is the device's bound workspace root — forced by the caller,
    never taken from the client frame. ``timeout`` is bounded by the host
    config. Raises :class:`CompanionExecError` (with ``.code``) on refusal.
    """
    if not isinstance(command, str) or not command.strip():
        raise CompanionExecError("invalid_argument", "command must be a non-empty string")
    reasons = risky_reasons(command)
    if reasons and not confirm_risky:
        raise CompanionExecError(
            "approval_required",
            "this command needs explicit confirmation on the device: " + ", ".join(reasons),
        )
    root = Path(str(cwd or "")).expanduser()
    if not root.is_absolute():
        raise CompanionExecError("invalid_argument", "cwd must be an absolute path")
    root = root.resolve()
    if not root.is_dir():
        raise CompanionExecError("not_found", "working directory not found")
    fingerprint = _command_fingerprint(command)
    _LOG.info(
        "companion exec allowed: operation=%r session=%r device=%r command_sha256=%s",
        "terminal.exec", session_id, device_id, fingerprint)

    session = _session_for(session_id, str(root))
    mode = resolve_mode(session, config, requested_mode,
                        confirm_danger=confirm_danger, device_id=device_id)
    policy = resolve_policy(
        mode=mode,
        workspace_root=str(root),
        session_id=session.session_id if session is not None else None,
        extra_grants=session.sandbox_grants if session is not None else (),
        withheld_roots=session.sandbox_withheld if session is not None else (),
    )
    bounded = max(1.0, min(float(timeout) if timeout is not None else config.effective_command_timeout,
                           config.effective_command_timeout))
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
        raise CompanionExecError(
            "sandbox_unavailable",
            f"the {mode} sandbox could not be established, so the command did not run: {exc}",
        ) from exc
    except FileNotFoundError as exc:
        raise CompanionExecError(
            "sandbox_unavailable",
            f"the shell could not be started: {exc}",
        ) from exc
    except OSError as exc:
        raise CompanionExecError("internal_error", f"the command could not be started: {exc}") from exc
    except asyncio.CancelledError:
        _LOG.info(
            "companion exec cancelled: operation=%r session=%r device=%r command_sha256=%s",
            "terminal.exec", session_id, device_id, fingerprint)
        raise
    except Exception as exc:  # pragma: no cover - defensive
        raise CompanionExecError("internal_error", f"the command failed: {exc}") from exc

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
