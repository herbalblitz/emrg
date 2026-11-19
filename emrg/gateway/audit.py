"""Append-only audit log for phone-originated actions.

The daemon already logs what the agent does. This log answers a different
question: *which paired device asked for what, and was it allowed?* Every
mutating proxy frame, every host command, every config write and every device
change is recorded with the device id and a decision.

Secrets never enter the file: every string is passed through the engine's own
:func:`emrg.server.redaction.redact_string` before the JSON line is written.
Command text is additionally truncated, because a command can be long and the
audit's subject is the act, not the transcript.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Any

from emrg.server.redaction import redact_string


class AuditLog:
    def __init__(self, path: Path, *, max_bytes: int = 10 * 1024 * 1024, backups: int = 3) -> None:
        self.path = path
        self.max_bytes = max(64 * 1024, int(max_bytes))
        self.backups = max(1, int(backups))
        self._lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.path.parent, 0o700)
        except OSError:
            pass

    def append(
        self,
        action: str,
        *,
        decision: str = "allow",
        device_id: str = "",
        device_name: str = "",
        detail: Any = None,
    ) -> None:
        record = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "device_id": device_id,
            "device_name": device_name,
            "action": str(action)[:160],
            "decision": str(decision)[:32],
        }
        if detail is not None:
            record["detail"] = _redact(detail)
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        with self._lock:
            self._rotate_if_needed(len(line.encode("utf-8")))
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            try:
                os.write(fd, line.encode("utf-8"))
            finally:
                os.close(fd)

    def _rotate_if_needed(self, incoming: int) -> None:
        try:
            size = self.path.stat().st_size
        except OSError:
            return
        if size + incoming <= self.max_bytes:
            return
        for index in range(self.backups, 0, -1):
            older = self.path.with_name(f"{self.path.name}.{index}")
            newer = self.path.with_name(f"{self.path.name}.{index - 1}")
            if index == 1:
                try:
                    os.replace(self.path, older)
                except OSError:
                    pass
                continue
            if newer.exists():
                try:
                    os.replace(newer, older)
                except OSError:
                    pass

    def tail(self, limit: int = 200) -> dict:
        """The last ``limit`` records, oldest first, without loading a huge file."""
        limit = max(1, min(int(limit), 2000))
        try:
            size = self.path.stat().st_size
        except OSError:
            return {"entries": [], "truncated": False}
        window = min(size, 512 * 1024)
        try:
            with self.path.open("rb") as handle:
                handle.seek(max(0, size - window))
                raw = handle.read().decode("utf-8", errors="replace")
        except OSError:
            return {"entries": [], "truncated": False}
        lines = [line for line in raw.splitlines() if line.strip()]
        truncated = size > window
        if truncated and lines:
            # The first line of a tail read is usually a partial JSON object.
            lines = lines[1:]
        entries: list[dict] = []
        for line in lines[-limit:]:
            try:
                value = json.loads(line)
            except ValueError:
                continue
            if isinstance(value, dict):
                entries.append(value)
        return {"entries": entries, "truncated": truncated}


def _redact(value: Any) -> Any:
    if isinstance(value, str):
        return redact_string(value)[:4000]
    if isinstance(value, dict):
        return {redact_string(str(k))[:80]: _redact(v) for k, v in list(value.items())[:64]}
    if isinstance(value, (list, tuple)):
        return [_redact(item) for item in list(value)[:64]]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return redact_string(str(value))[:4000]
