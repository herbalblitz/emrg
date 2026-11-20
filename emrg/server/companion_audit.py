"""Audit tail for the companion Audit screen.

A port of ``emrg/gateway/audit.py`` (reference branch
``feat/cross-platform``) for the companion WSS listener, which has no
gateway — with one structural deviation the port has to own, because the
source file is different:

**There is no dedicated audit log file in this branch.** The gateway kept
its own append-only JSONL audit log (``gateway_paths().audit``) and tailed
that. Here the daemon's audit trail is its own log: every companion
control action — allowed and denied — is written as a ``logger.info`` /
``logger.warning`` line into ``logs_dir()/emrgd.log`` (see
``emrg/server/daemon.py`` ``companion_local_control``, and the
``companion <area> allowed/denied`` lines the companion modules emit).
This handler tails **that same file** and parses those lines back into
structured entries, so the Audit screen shows exactly the actions the
daemon recorded, including companion control actions.

Consequences of the deviation:

* Entries are parsed from free-text log lines, not JSON. A line that
  carries no companion audit marker is skipped; a marker line that fails
  field extraction still surfaces with the fields that could be read, so
  a format change degrades to a coarser entry rather than a silent gap.
* Only the live ``emrgd.log`` is tailed, not its rotation siblings
  (``emrgd.log.1`` …), matching the gateway tail's live-file-only read.
* Audit entries never contain payload contents: the daemon's audit lines
  carry operation + session/pairing/device ids only, and every string here
  passes through :func:`_redact` (the gateway's port, twice-applied: once
  at write, once here) plus the log-line path sanitiser.
"""
from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import Any

from emrg.config import logs_dir
from emrg.server.companion_logs import _fit_budget, sanitize_log_line
from emrg.server.redaction import redact_string
from emrg.tools.base import non_regular_kind

_LOG = logging.getLogger(__name__)

#: Largest tail window read from disk (same as the logs tail).
_WINDOW = 512 * 1024

#: Default and maximum entries per tail. The wire validator enforces the
#: same maximum; the handler clamps again for in-process callers.
DEFAULT_LIMIT = 200
MAX_LIMIT = 400

#: Reply budget for the returned entries, in JSON bytes (see companion_logs).
_FRAME_BUDGET_BYTES = 200_000

#: Longest ``detail`` string returned per entry.
MAX_DETAIL_CHARS = 400

#: ``2026-11-20 16:09:15`` at the start of a daemon log line (the
#: ``_TaskColumnFormatter`` asctime).
_TS_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")

#: ``companion <area> allowed:`` / ``companion <area> denied:`` — the one
#: marker every companion audit line carries, wherever it was written.
_AUDIT_MARKER_RE = re.compile(r"companion\s+([\w\- ]+?)\s+(allowed|denied):")

#: ``key='value'`` / ``key="value"`` / ``key=value`` pairs after the marker.
_KV_RE = re.compile(r"(\w+)=('([^']*)'|\"([^\"]*)\"|(\S+))")


class CompanionAuditError(ValueError):
    """Safe audit-tail refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _redact(value: Any) -> Any:
    """Port of gateway ``audit.py::_redact``: redact every string, bound sizes.

    Secrets never enter the audit reply: every string is passed through the
    engine's own :func:`emrg.server.redaction.redact_string` before it is
    returned, and containers are capped so one entry cannot blow the frame.
    """
    if isinstance(value, str):
        return redact_string(value)[:4000]
    if isinstance(value, dict):
        return {redact_string(str(k))[:80]: _redact(v) for k, v in list(value.items())[:64]}
    if isinstance(value, (list, tuple)):
        return [_redact(item) for item in list(value)[:64]]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return redact_string(str(value))[:4000]


def _parse_audit_line(line: str, *, workspace_root: str | Path | None = None) -> dict | None:
    """Parse one daemon log line into an audit entry, or None if not one.

    Entry shape mirrors the gateway's JSONL records (``ts, device_id,
    action, decision, detail``) so the Audit screen renders the same rows.
    """
    marker = _AUDIT_MARKER_RE.search(line)
    if marker is None:
        return None
    area, verdict = marker.group(1).strip(), marker.group(2)
    rest = line[marker.end():]
    fields: dict[str, str] = {}
    for match in _KV_RE.finditer(rest):
        key = match.group(1)
        value = match.group(3) if match.group(3) is not None else (
            match.group(4) if match.group(4) is not None else match.group(5))
        fields.setdefault(key, value or "")
    operation = fields.get("operation", "")
    action = f"companion {area} {operation}".strip() if operation else f"companion {area}"
    timestamp = _TS_RE.match(line)
    detail = sanitize_log_line(rest.strip(), workspace_root=workspace_root)
    return {
        "ts": timestamp.group(1) if timestamp else "",
        "device_id": _redact(fields.get("device", "")),
        "action": _redact(action),
        "decision": "allow" if verdict == "allowed" else "deny",
        "detail": _redact(detail)[:MAX_DETAIL_CHARS],
    }


def tail(limit: int = DEFAULT_LIMIT, *, path: Path | None = None,
         log_dir: Path | None = None,
         workspace_root: str | Path | None = None) -> dict:
    """Return the last ``limit`` companion audit entries, oldest first.

    The source is the daemon log the daemon itself writes its companion
    audit lines to — ``logs_dir()/emrgd.log`` — unless ``path`` names a
    different file (tests). A missing or unreadable file answers with an
    empty tail rather than an error.
    """
    if not isinstance(limit, int) or isinstance(limit, bool):
        limit = DEFAULT_LIMIT
    limit = max(1, min(limit, MAX_LIMIT))
    target = Path(path) if path is not None else (
        Path(log_dir) if log_dir is not None else logs_dir()) / "emrgd.log"
    # Same kind check as the logs tail: a FIFO at the log path must never
    # be opened (see companion_logs.tail).
    try:
        kind = non_regular_kind(target)
    except FileNotFoundError:
        return {"entries": [], "truncated": False}
    except OSError:
        return {"entries": [], "truncated": False}
    if kind is not None:
        return {"entries": [], "truncated": False}
    try:
        size = target.stat().st_size
    except OSError:
        return {"entries": [], "truncated": False}
    window = min(size, _WINDOW)
    try:
        with target.open("rb") as handle:
            handle.seek(max(0, size - window))
            raw = handle.read()
    except OSError:
        return {"entries": [], "truncated": False}
    text = raw.decode("utf-8", errors="replace")
    found = text.splitlines()
    truncated = size > window
    if truncated and found:
        found = found[1:]
    entries: list[dict] = []
    for line in found:
        entry = _parse_audit_line(line, workspace_root=workspace_root)
        if entry is not None:
            entries.append(entry)
    entries = entries[-limit:]
    entries, budget_truncated = _fit_budget(entries, budget_bytes=_FRAME_BUDGET_BYTES)
    return {"entries": entries, "truncated": truncated or budget_truncated}
