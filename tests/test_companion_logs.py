"""Tests for companion log tails (port of gateway logs)."""
import json
import os
from pathlib import Path

import pytest

from emrg.server.companion_logs import (
    MAX_LINES,
    SOURCES,
    CompanionLogsError,
    sanitize_log_line,
    tail,
)


def _write_log(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_tail_rejects_unknown_source(tmp_path):
    with pytest.raises(CompanionLogsError) as exc:
        tail("gateway", log_dir=tmp_path)
    assert exc.value.code == "unknown_log_source"
    with pytest.raises(CompanionLogsError) as exc:
        tail("/etc/passwd", log_dir=tmp_path)
    assert exc.value.code == "unknown_log_source"
    with pytest.raises(CompanionLogsError) as exc:
        tail("../emrgd.log", log_dir=tmp_path)
    assert exc.value.code == "unknown_log_source"


def test_tail_missing_file_is_empty_not_error(tmp_path):
    result = tail("daemon", log_dir=tmp_path)
    assert result == {"source": "daemon", "lines": [], "truncated": False, "bytes": 0}


def test_tail_returns_last_lines_default_200(tmp_path):
    log = tmp_path / "emrgd.log"
    _write_log(log, "\n".join(f"line {i}" for i in range(250)) + "\n")
    result = tail("daemon", log_dir=tmp_path)
    assert len(result["lines"]) == 200
    assert result["lines"][0] == "line 50"
    assert result["lines"][-1] == "line 249"
    assert result["truncated"] is False
    assert result["bytes"] > 0
    assert result["source"] == "daemon"


def test_tail_line_cap_is_400(tmp_path):
    log = tmp_path / "emrgd.log"
    _write_log(log, "\n".join(f"line {i}" for i in range(500)) + "\n")
    result = tail("daemon", lines=1000, log_dir=tmp_path)
    assert len(result["lines"]) == MAX_LINES == 400
    assert result["lines"][0] == "line 100"
    result = tail("daemon", lines=3, log_dir=tmp_path)
    assert result["lines"] == ["line 497", "line 498", "line 499"]


def test_tail_never_returns_absolute_host_paths(tmp_path):
    workspace = tmp_path / "ws"
    log = tmp_path / "emrgd.log"
    secret = "sk-" + "A1b2C3d4E5f6G7h8"  # 18 chars: matches the sk- pattern
    _write_log(log, "\n".join([
        f"opened {workspace}/proj/file.py",
        f"home file {Path.home()}/.emrg/config.toml",
        "system file /var/log/syslog line",
        "windows path C:\\Users\\someone\\secret.txt here",
        "docs at https://example.com/a/b stay intact",
        f"leaked key api_key={secret} here",
        f"file url file://{workspace}/proj/x.py here",
    ]) + "\n")
    result = tail("daemon", log_dir=tmp_path, workspace_root=workspace)
    lines = result["lines"]
    assert lines[0] == "opened ./proj/file.py"
    assert lines[1] == "home file ~/.emrg/config.toml"
    assert lines[2] == "system file [path] line"
    assert lines[3] == "windows path [path] here"
    assert lines[4] == "docs at https://example.com/a/b stay intact"
    assert secret not in lines[5]
    assert "api_key=***" in lines[5] or "***" in lines[5]
    assert lines[6] == "file url [path] here"
    # No raw absolute host path survives in any line.
    assert str(workspace) not in json.dumps(lines)
    assert str(Path.home()) not in json.dumps(lines)


def test_sanitize_log_line_keeps_relative_paths_and_dates(tmp_path):
    line = sanitize_log_line("2026-11-20 16:09:15 relative/a/b.py 11/20/2026 ok",
                             workspace_root=tmp_path)
    assert line == "2026-11-20 16:09:15 relative/a/b.py 11/20/2026 ok"


def test_tail_truncates_huge_window_and_long_lines(tmp_path):
    log = tmp_path / "emrgd.log"
    big = "x" * 600_000
    _write_log(log, big + "\n" + "short\n")
    result = tail("daemon", log_dir=tmp_path)
    assert result["truncated"] is True
    # The window starts mid-line: the partial first line is dropped.
    assert result["lines"] == ["short"]
    log2 = tmp_path / "emrgd.log"
    _write_log(log2, "y" * 3000 + "\n")
    result = tail("daemon", log_dir=tmp_path)
    assert len(result["lines"][0]) == 2000


def test_tail_reply_stays_within_frame_budget(tmp_path):
    log = tmp_path / "emrgd.log"
    _write_log(log, "\n".join("z" * 1900 for _ in range(400)) + "\n")
    result = tail("daemon", lines=400, log_dir=tmp_path)
    assert len(json.dumps(result["lines"]).encode("utf-8")) <= 200_000
    assert result["truncated"] is True


def test_tail_sources_are_the_daemon_logs_only():
    assert set(SOURCES) == {"daemon", "crash", "exit", "start", "gui"}
    assert "gateway" not in SOURCES


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="this platform has no FIFOs")
def test_tail_refuses_fifo_without_opening_it(tmp_path):
    # An open is not a read: a FIFO at the log path must be refused, not
    # opened (opening it would block until a peer appears). If the guard
    # regresses, this test hangs instead of failing — run it with a timeout.
    os.mkfifo(tmp_path / "emrgd.log")
    result = tail("daemon", log_dir=tmp_path)
    assert result == {"source": "daemon", "lines": [], "truncated": False, "bytes": 0}
