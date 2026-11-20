"""Tests for the companion audit tail (port of gateway audit)."""
import logging
import os

import pytest

from emrg.server.companion_audit import (
    MAX_LIMIT,
    _parse_audit_line,
    _redact,
    tail,
)

_DAEMON_TS = "2026-11-20 16:09:15"
_PREFIX = f"{_DAEMON_TS} [INFO] [-] [-] emrg.server.daemon: "


def _audit_log(*lines):
    return "\n".join(lines) + "\n"


def test_tail_parses_companion_audit_lines(tmp_path):
    log = tmp_path / "emrgd.log"
    log.write_text(_audit_log(
        f"{_PREFIX}companion local control allowed: operation='listener.start' session='s-1' pairing='p-1'",
        "2026-11-20 16:09:16 [INFO] [-] [-] emrg.server.daemon: unrelated daemon noise",
        f"{_PREFIX}companion session allowed: operation='sessions.delete' session='s-2' device='phone-1'",
        f"{_PREFIX}companion batch-b allowed: operation='models.set' target='m-a' device='phone-1'",
        f"{_PREFIX}companion devices denied: operation='devices.revoke' device='phone-1' target='phone-1'",
        "Traceback (most recent call last):",
        "  File \"x.py\", line 1",
    ), encoding="utf-8")
    result = tail(log_dir=tmp_path)
    assert result["truncated"] is False
    entries = result["entries"]
    assert len(entries) == 4
    assert entries[0] == {
        "ts": _DAEMON_TS, "device_id": "",
        "action": "companion local control listener.start",
        "decision": "allow",
        "detail": "operation='listener.start' session='s-1' pairing='p-1'",
    }
    assert entries[1]["action"] == "companion session sessions.delete"
    assert entries[1]["device_id"] == "phone-1"
    assert entries[1]["decision"] == "allow"
    assert entries[2]["action"] == "companion batch-b models.set"
    assert entries[3]["action"] == "companion devices devices.revoke"
    assert entries[3]["decision"] == "deny"


def test_tail_missing_file_is_empty_not_error(tmp_path):
    assert tail(log_dir=tmp_path) == {"entries": [], "truncated": False}


def test_tail_limit_cap_is_400(tmp_path):
    log = tmp_path / "emrgd.log"
    lines = [
        f"{_PREFIX}companion session allowed: operation='sessions.list' session='s-{i}' device='d'"
        for i in range(500)
    ]
    log.write_text(_audit_log(*lines), encoding="utf-8")
    result = tail(limit=1000, log_dir=tmp_path)
    assert len(result["entries"]) == MAX_LIMIT == 400
    # Oldest first within the window: the first kept entry is s-100.
    assert "s-100" in result["entries"][0]["detail"]
    assert "s-499" in result["entries"][-1]["detail"]


def test_tail_never_leaks_payloads_or_paths(tmp_path):
    workspace = tmp_path / "ws"
    log = tmp_path / "emrgd.log"
    secret = "sk-" + "A1b2C3d4E5f6G7h8"
    log.write_text(_audit_log(
        f"{_PREFIX}companion exec allowed: operation='terminal.exec' session='s' device='d' command_sha256='abc123' note='key {secret}'",
        f"{_PREFIX}companion file allowed: operation='files.write' session='s' device='d' path='{workspace}/notes/a.txt'",
    ), encoding="utf-8")
    result = tail(log_dir=tmp_path, workspace_root=workspace)
    assert secret not in str(result)
    assert str(workspace) not in str(result)
    assert "./notes/a.txt" in result["entries"][1]["detail"]


def test_parse_audit_line_skips_non_audit_lines():
    assert _parse_audit_line("2026-11-20 16:09:15 [INFO] [-] [-] emrg.server.daemon: hello") is None
    assert _parse_audit_line("") is None
    # A marker line without a timestamp still surfaces (ts empty).
    entry = _parse_audit_line("companion session allowed: operation='x' device='d'")
    assert entry is not None
    assert entry["ts"] == ""
    assert entry["action"] == "companion session x"
    assert entry["decision"] == "allow"


def test_redact_is_the_gateway_port():
    # Both the sk- pattern and the api_key= pattern collapse the value.
    assert _redact("api_key=sk-" + "A1b2C3d4E5f6G7h8") == "***"
    assert _redact({"k": "x" * 5000}) == {"k": "x" * 4000}
    assert _redact([1, "ok", None]) == [1, "ok", None]
    assert _redact(7) == 7


def test_tail_audit_logs_companion_control_actions(tmp_path, caplog):
    # The audit source is the daemon log itself: a companion control line
    # written by the daemon is what this handler must surface.
    log = tmp_path / "emrgd.log"
    log.write_text(_audit_log(
        f"{_PREFIX}companion local control denied: operation='listener.start' session='s' pairing='p' error='companion disabled'",
    ), encoding="utf-8")
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_audit"):
        result = tail(log_dir=tmp_path)
    assert len(result["entries"]) == 1
    assert result["entries"][0]["decision"] == "deny"
    assert result["entries"][0]["action"] == "companion local control listener.start"


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="this platform has no FIFOs")
def test_tail_refuses_fifo_without_opening_it(tmp_path):
    # Same guard as the logs tail: a FIFO at the log path is refused, not
    # opened. If the guard regresses, this test hangs — run with a timeout.
    os.mkfifo(tmp_path / "emrgd.log")
    assert tail(log_dir=tmp_path) == {"entries": [], "truncated": False}
