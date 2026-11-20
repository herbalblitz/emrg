"""Bounded log tails for the companion Logs screen.

A port of ``emrg/gateway/logs.py::tail`` (reference branch
``feat/cross-platform``) for the companion WSS listener, which has no
gateway. Two companion differences from the gateway port:

* **No gateway source.** The gateway also served its own ``gateway.log``;
  this branch has no gateway, so the allowlist is the five daemon-side
  application logs under :func:`emrg.config.logs_dir` (the names in
  :mod:`emrg.logfiles`). A client-supplied path is never accepted: the
  ``source`` is one allowlisted name, nothing else.
* **No absolute host paths leave the host.** The gateway returned the log
  file's absolute path in its reply; a companion reply carries no path at
  all, and every returned line is sanitised (:func:`sanitize_log_line`):
  secrets redacted, the workspace root rendered as ``.``, the home
  directory as ``~``, and any other absolute path replaced with ``[path]``.

A tail read never loads the whole file: it seeks into the last window and
drops the first (usually partial) line, the same shape the audit tail uses.
"""
from __future__ import annotations

import json
import logging
import re
from pathlib import Path

from emrg.config import logs_dir
from emrg.server.redaction import redact_string
from emrg.tools.base import non_regular_kind

_LOG = logging.getLogger(__name__)

#: Largest tail window read from disk. 512 KiB is enough for thousands of lines
#: and small enough that a phone cannot ask the host to pull a huge log.
_WINDOW = 512 * 1024

#: Default and maximum lines per tail. The wire validator enforces the same
#: maximum; the handler clamps again so a caller that bypasses the validator
#: (in-process dispatch) gets the same bound.
DEFAULT_LINES = 200
MAX_LINES = 400

#: Longest single returned line, in characters. Log lines are unbounded on
#: disk (one tool call can log a very long line); the reply must stay small.
MAX_LINE_CHARS = 2000

#: Reply budget for the returned lines, in JSON bytes. The wire frame cap is
#: 256 KiB; staying under 200 KiB leaves room for the frame envelope and the
#: other result fields, so a long tail can never kill the connection by
#: overflowing the encoder.
_FRAME_BUDGET_BYTES = 200_000

#: Allowlisted log sources: wire name -> file name under ``logs_dir()``.
#: ``gateway`` is deliberately absent — this branch has no gateway log.
SOURCES: dict[str, str] = {
    "daemon": "emrgd.log",
    "crash": "emrgd-crash.log",
    "exit": "emrgd-exit.log",
    "start": "emrgd-start.err",
    "gui": "emrg-gui.log",
}

#: A Unix absolute path that is not a URL (a ``/`` preceded by a word
#: character, ``:``, ``/``, ``.`` or ``~`` belongs to ``https://host/path``,
#: a date, or the ``~`` home rendering, and is left alone). Applied after
#: the workspace root and the home directory have been rendered as ``.`` /
#: ``~``, so what remains here is a path outside both and must not reach
#: the device.
_ABSOLUTE_UNIX_PATH_RE = re.compile(r"(?<![\w:/.~])/(?:[\w.\-~]+/)+[\w.\-~]*")
#: A Windows absolute path (``C:\...`` / ``C:/...``). The drive letter must
#: not be part of a longer word, or the ``s:`` in ``https:`` would match.
_ABSOLUTE_WINDOWS_PATH_RE = re.compile(r"(?<![A-Za-z])[A-Za-z]:[\\/][^\s\"']*")
#: A ``file://`` URL — its target is a host path.
_FILE_URL_RE = re.compile(r"file://[^\s\"']*")


class CompanionLogsError(ValueError):
    """Safe log-tail refusal with a stable machine-readable ``code``."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def sanitize_log_line(line: str, *, workspace_root: str | Path | None = None) -> str:
    """Redact secrets and absolute host paths from one log line.

    The workspace root renders as ``.`` (the client works in
    workspace-relative paths), the home directory as ``~`` (it reveals no
    username that way), and any other absolute path as ``[path]``. URLs are
    left alone: the absolute-path pattern requires a leading ``/`` that is
    not part of ``://`` or a word, so ``https://host/path`` survives.
    """
    text = redact_string(line)
    if workspace_root is not None:
        root = str(workspace_root)
        if root:
            # The resolved form and the as-given form can differ (a symlinked
            # workspace); render both, resolved first.
            try:
                resolved = str(Path(root).resolve())
            except OSError:
                resolved = root
            for prefix in (resolved, root):
                if prefix and prefix != ".":
                    text = text.replace(prefix, ".")
    try:
        home = str(Path.home())
    except (OSError, RuntimeError):
        home = ""
    if home:
        text = text.replace(home, "~")
    text = _FILE_URL_RE.sub("[path]", text)
    text = _ABSOLUTE_WINDOWS_PATH_RE.sub("[path]", text)
    text = _ABSOLUTE_UNIX_PATH_RE.sub("[path]", text)
    return text


def _fit_budget(items: list, *, budget_bytes: int) -> tuple[list, bool]:
    """Drop oldest items until their JSON fits ``budget_bytes``.

    Returns the (possibly shortened) list and whether anything was dropped.
    """
    truncated = False
    while items and len(json.dumps(items, ensure_ascii=False).encode("utf-8")) > budget_bytes:
        items.pop(0)
        truncated = True
    return items, truncated


def tail(source: str, *, lines: int = DEFAULT_LINES, log_dir: Path | None = None,
         workspace_root: str | Path | None = None) -> dict:
    """Return the last ``lines`` lines of one allowlisted log file.

    ``source`` must be a key of :data:`SOURCES`; anything else — including a
    path — is rejected. A missing or unreadable file answers with an empty
    tail rather than an error, matching the gateway behaviour: the file
    simply does not exist yet on this host.
    """
    if not isinstance(lines, int) or isinstance(lines, bool):
        lines = DEFAULT_LINES
    lines = max(1, min(lines, MAX_LINES))
    name = SOURCES.get(source if isinstance(source, str) else "")
    if name is None:
        raise CompanionLogsError(
            "unknown_log_source",
            f"unknown log source; expected one of {', '.join(sorted(SOURCES))}")
    directory = Path(log_dir) if log_dir is not None else logs_dir()
    path = directory / name
    # An open is not a read: a FIFO / socket / device at a log path would
    # block until a peer appears, so the kind is asked before anything is
    # opened (the tree-wide opener guard enforces this shape).
    try:
        kind = non_regular_kind(path)
    except FileNotFoundError:
        return {"source": source, "lines": [], "truncated": False, "bytes": 0}
    except OSError:
        return {"source": source, "lines": [], "truncated": False, "bytes": 0}
    if kind is not None:
        return {"source": source, "lines": [], "truncated": False, "bytes": 0}
    try:
        size = path.stat().st_size
    except OSError:
        return {"source": source, "lines": [], "truncated": False, "bytes": 0}
    window = min(size, _WINDOW)
    try:
        with path.open("rb") as handle:
            handle.seek(max(0, size - window))
            raw = handle.read()
    except OSError:
        return {"source": source, "lines": [], "truncated": False, "bytes": 0}
    text = raw.decode("utf-8", errors="replace")
    found = text.splitlines()
    truncated = size > window
    if truncated and found:
        found = found[1:]
    found = found[-lines:]
    cleaned = [sanitize_log_line(line, workspace_root=workspace_root)[:MAX_LINE_CHARS]
               for line in found]
    cleaned, budget_truncated = _fit_budget(cleaned, budget_bytes=_FRAME_BUDGET_BYTES)
    return {
        "source": source,
        "lines": cleaned,
        "truncated": truncated or budget_truncated,
        "bytes": size,
    }
