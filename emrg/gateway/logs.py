"""Bounded log tails for the phone's logs screen.

Only files the gateway or the engine owns are readable here; arbitrary paths go
through ``gw_read_file`` and its own bounds, so this handler is not a second
file-read API. A tail read never loads the whole file: it seeks into the last
window and drops the first (usually partial) line, the same shape the audit tail
uses.

The daemon already redacts credentials at the log sites that can carry them
(``emrg.server.redaction``), so the text is returned as written. The audit log is
redacted twice — once at write, once here — because it carries command text.
"""

from __future__ import annotations

from pathlib import Path

from emrg.config import logs_dir
from emrg.gateway.audit import AuditLog
from emrg.gateway.config import gateway_paths
from emrg.gateway.protocol import ERR_NOT_FOUND, GatewayError
from emrg.server.redaction import redact_string

#: Largest tail window read from disk. 512 KiB is enough for thousands of lines
#: and small enough that a phone cannot ask the gateway to pull a huge log.
_WINDOW = 512 * 1024

SOURCES = {
    "daemon": "emrgd.log",
    "crash": "emrgd-crash.log",
    "exit": "emrgd-exit.log",
    "start": "emrgd-start.err",
    "gui": "emrg-gui.log",
    "gateway": "gateway.log",
}


def tail(which: str, *, lines: int = 200, config=None) -> dict:
    """Return the last ``lines`` lines of one known log file."""
    del config
    if not isinstance(lines, int) or isinstance(lines, bool):
        lines = 200
    lines = max(1, min(lines, 2000))
    if which == "audit":
        audit = AuditLog(gateway_paths().audit)
        result = audit.tail(lines)
        return {
            "source": "audit",
            "entries": result["entries"],
            "truncated": result["truncated"],
            "path": str(audit.path),
        }
    name = SOURCES.get(str(which))
    if name is None:
        raise GatewayError(
            ERR_NOT_FOUND,
            f"unknown log source {which!r}; expected one of {', '.join(sorted(SOURCES))} or audit",
        )
    path = (gateway_paths().root / name) if which == "gateway" else (logs_dir() / name)
    return _tail_file(path, lines, source=str(which))


def _tail_file(path: Path, lines: int, *, source: str) -> dict:
    try:
        size = path.stat().st_size
    except OSError:
        return {"source": source, "path": str(path), "lines": [], "truncated": False}
    window = min(size, _WINDOW)
    try:
        with path.open("rb") as handle:
            handle.seek(max(0, size - window))
            raw = handle.read()
    except OSError:
        return {"source": source, "path": str(path), "lines": [], "truncated": False}
    text = raw.decode("utf-8", errors="replace")
    found = text.splitlines()
    truncated = size > window
    if truncated and found:
        found = found[1:]
    found = found[-lines:]
    return {
        "source": source,
        "path": str(path),
        "lines": [redact_string(line) for line in found],
        "truncated": truncated or len(found) < lines,
        "bytes": size,
    }
