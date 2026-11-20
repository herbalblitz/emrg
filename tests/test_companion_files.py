"""Tests for companion file write/delete (port of gateway files)."""
import hashlib
import logging
import os

import pytest

from emrg.server.companion_exec import CompanionExecConfig
from emrg.server.companion_files import (
    MAX_EDIT_FILE,
    CompanionFileError,
    delete,
    write,
)
from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_wss_listener import CompanionWssListener


def _config():
    return CompanionExecConfig()


def test_files_write_creates_and_overwrites(tmp_path, caplog):
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_files"):
        result = write("notes/a.txt", "hello\n", root=str(tmp_path),
                       session_id=None, config=_config(), create_parents=True,
                       device_id="phone-1")
    assert result.path == "notes/a.txt"
    assert result.created is True
    assert result.bytes_written == 6
    assert result.sha256 == hashlib.sha256(b"hello\n").hexdigest()
    assert (tmp_path / "notes" / "a.txt").read_text() == "hello\n"
    assert "companion file allowed" in caplog.text
    assert "files.write" in caplog.text
    # Overwrite keeps the path relative and reports created=False.
    result = write("notes/a.txt", "bye\n", root=str(tmp_path),
                   session_id=None, config=_config(), device_id="phone-1")
    assert result.created is False
    assert (tmp_path / "notes" / "a.txt").read_text() == "bye\n"


def test_files_write_conflict_detection(tmp_path):
    write("a.txt", "v1", root=str(tmp_path), session_id=None, config=_config())
    digest = hashlib.sha256(b"v1").hexdigest()
    write("a.txt", "v2", root=str(tmp_path), session_id=None,
          config=_config(), expected_sha256=digest)
    assert (tmp_path / "a.txt").read_text() == "v2"
    with pytest.raises(CompanionFileError) as exc:
        write("a.txt", "v3", root=str(tmp_path), session_id=None,
              config=_config(), expected_sha256=digest)
    assert exc.value.code == "conflict"
    assert (tmp_path / "a.txt").read_text() == "v2"


def test_files_write_rejects_escape_and_symlink_escape(tmp_path):
    with pytest.raises(CompanionFileError) as exc:
        write("../outside.txt", "x", root=str(tmp_path), session_id=None,
              config=_config())
    assert exc.value.code == "policy_refused"
    with pytest.raises(CompanionFileError) as exc:
        write("/etc/evil.txt", "x", root=str(tmp_path), session_id=None,
              config=_config())
    assert exc.value.code == "policy_refused"
    # A symlink inside the workspace pointing outside must not be followed.
    outside = tmp_path.parent / "outside-target.txt"
    outside.write_text("safe")
    os.symlink(str(outside), tmp_path / "link.txt")
    with pytest.raises(CompanionFileError) as exc:
        write("link.txt", "x", root=str(tmp_path), session_id=None,
              config=_config())
    assert exc.value.code == "policy_refused"
    assert outside.read_text() == "safe"
    # A symlink pointing inside the workspace is fine.
    (tmp_path / "real.txt").write_text("real")
    os.symlink("real.txt", tmp_path / "inner-link.txt")
    write("inner-link.txt", "new", root=str(tmp_path), session_id=None,
          config=_config())
    assert (tmp_path / "real.txt").read_text() == "new"


def test_files_write_size_cap_and_missing_parent(tmp_path):
    with pytest.raises(CompanionFileError) as exc:
        write("big.txt", "x" * (MAX_EDIT_FILE + 1), root=str(tmp_path),
              session_id=None, config=_config())
    assert exc.value.code == "invalid_argument"
    with pytest.raises(CompanionFileError) as exc:
        write("no-dir/a.txt", "x", root=str(tmp_path), session_id=None,
              config=_config())
    assert exc.value.code == "not_found"


def test_files_delete_rules(tmp_path, caplog):
    (tmp_path / "a.txt").write_text("x")
    (tmp_path / "dir").mkdir()
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_files"):
        outcome = delete("a.txt", root=str(tmp_path), session_id=None,
                         config=_config(), device_id="phone-1")
    assert outcome == {"path": "a.txt", "deleted": True}
    assert not (tmp_path / "a.txt").exists()
    assert "companion file allowed" in caplog.text
    assert "files.delete" in caplog.text
    with pytest.raises(CompanionFileError) as exc:
        delete("a.txt", root=str(tmp_path), session_id=None, config=_config())
    assert exc.value.code == "not_found"
    with pytest.raises(CompanionFileError) as exc:
        delete("dir", root=str(tmp_path), session_id=None, config=_config())
    assert exc.value.code == "invalid_argument"
    with pytest.raises(CompanionFileError) as exc:
        delete("../outside.txt", root=str(tmp_path), session_id=None,
               config=_config())
    assert exc.value.code == "policy_refused"


def test_files_dispatch_returns_relative_paths(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    config = CompanionListenerConfig.build("127.0.0.1", 8765)
    listener = CompanionWssListener(
        host, config, tmp_path / "cert.pem", tmp_path / "key.pem")
    identity = {"device_id": "phone-1", "workspace_id": "ws",
                "cwd": str(tmp_path), "scopes": {"files.write"},
                "generation": 1}

    class Socket:
        async def send(self, data):
            pass

        async def close(self, **kwargs):
            pass

    import asyncio
    result = asyncio.run(listener._dispatch(
        {"type": "files.write", "request_id": "r-1", "workspace_id": "ws",
         "path": "sub/a.txt", "content": "hi\n", "create_parents": True},
        identity, Socket()))
    assert result["type"] == "files.write.result"
    assert result["path"] == "sub/a.txt"
    assert result["created"] is True
    assert (tmp_path / "sub" / "a.txt").read_text() == "hi\n"
    result = asyncio.run(listener._dispatch(
        {"type": "files.delete", "request_id": "r-2", "workspace_id": "ws",
         "path": "sub/a.txt"}, identity, Socket()))
    assert result == {"type": "files.delete.result", "request_id": "r-2",
                      "workspace_id": "ws", "path": "sub/a.txt",
                      "deleted": True}
