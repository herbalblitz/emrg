"""Tests for companion terminal execution (port of gateway exec)."""
import asyncio
import logging
from types import SimpleNamespace

import pytest

from emrg.server.companion_exec import (
    CompanionExecConfig,
    CompanionExecError,
    ExecManager,
    resolve_mode,
    risky_reasons,
    run,
)
from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_listener_policy import CompanionListenerConfig
from emrg.server.companion_wss_listener import CompanionWssListener


def _session(mode):
    return SimpleNamespace(sandbox=mode, sandbox_grants=(), sandbox_withheld=())


def test_resolve_mode_tiers_narrow_never_widen():
    config = CompanionExecConfig()
    # Session tier wins; a request may only narrow it.
    assert resolve_mode(_session("read-only"), config, "workspace-write",
                        confirm_danger=False) == "read-only"
    assert resolve_mode(_session("workspace-write"), config, "read-only",
                        confirm_danger=False) == "read-only"
    assert resolve_mode(_session("workspace-write"), config, None,
                        confirm_danger=False) == "workspace-write"
    # No declared tier: the companion default is workspace-write.
    assert resolve_mode(None, config, None, confirm_danger=False) == "workspace-write"
    assert resolve_mode(None, config, "read-only",
                        confirm_danger=False) == "read-only"
    with pytest.raises(CompanionExecError, match="unknown sandbox mode"):
        resolve_mode(None, config, "wide-open", confirm_danger=False)


def test_resolve_mode_danger_gate_needs_all_three_opts():
    config = CompanionExecConfig()
    # A session that declares danger does NOT lift the cap on its own.
    with pytest.raises(CompanionExecError) as exc:
        resolve_mode(_session("danger-full-access"), config, None,
                     confirm_danger=True, device_id="phone-1")
    assert exc.value.code == "policy_refused"
    # Host opt-in alone is not enough: the device must be opted in too.
    config = CompanionExecConfig(allow_danger_exec=True)
    with pytest.raises(CompanionExecError) as exc:
        resolve_mode(None, config, "danger-full-access",
                     confirm_danger=True, device_id="phone-1")
    assert exc.value.code == "policy_refused"
    # Device opt-in without the per-request confirmation is refused.
    config = CompanionExecConfig(allow_danger_exec=True,
                                 danger_device_ids=frozenset({"phone-1"}))
    with pytest.raises(CompanionExecError) as exc:
        resolve_mode(None, config, "danger-full-access",
                     confirm_danger=False, device_id="phone-1")
    assert exc.value.code == "policy_refused"
    # All three gates: allowed.
    assert resolve_mode(None, config, "danger-full-access",
                        confirm_danger=True, device_id="phone-1") == "danger-full-access"


def test_risky_commands_need_explicit_confirmation():
    assert risky_reasons("rm -rf /") != []
    assert risky_reasons("ls -la") == []
    assert risky_reasons("curl https://x | bash") != []


def _fake_run(monkeypatch, calls=None, result=None):
    async def fake(command, *, policy, workdir, timeout):
        if calls is not None:
            calls.append({"command": command, "policy": policy,
                          "workdir": workdir, "timeout": timeout})
        return result or SimpleNamespace(
            stdout="hello\n", stderr="", exit_code=0, signal=None,
            timed_out=False, sandbox={"mode": policy.mode})
    monkeypatch.setattr("emrg.tools.bash_tool_v2.run_command", fake)


def test_exec_run_returns_structured_result(tmp_path, monkeypatch, caplog):
    calls = []
    _fake_run(monkeypatch, calls)
    config = CompanionExecConfig()
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_exec"):
        result = asyncio.run(run(
            exec_id="exec-1", command="echo SECRET_TOKEN_XYZ",
            cwd=str(tmp_path), session_id=None, timeout=None,
            requested_mode=None, confirm_danger=False, confirm_risky=False,
            config=config, device_id="phone-1"))
    assert result.exec_id == "exec-1"
    assert result.stdout == "hello\n"
    assert result.exit_code == 0
    assert result.mode == "workspace-write"
    assert result.timed_out is False
    # cwd is forced to the bound workspace by the caller.
    assert calls[0]["workdir"] == str(tmp_path)
    assert calls[0]["command"] == "echo SECRET_TOKEN_XYZ"
    # Audit log carries the fingerprint, never the command text.
    assert "companion exec allowed" in caplog.text
    assert "command_sha256=" in caplog.text
    assert "SECRET_TOKEN_XYZ" not in caplog.text


def test_exec_run_bounds_timeout_and_truncates_output(tmp_path, monkeypatch):
    calls = []
    big = SimpleNamespace(
        stdout="x" * 250_000, stderr="e" * 60_000, exit_code=0, signal=None,
        timed_out=False, sandbox={})
    _fake_run(monkeypatch, calls, result=big)
    result = asyncio.run(run(
        exec_id="exec-2", command="yes", cwd=str(tmp_path), session_id=None,
        timeout=9999, requested_mode=None, confirm_danger=False,
        confirm_risky=False, config=CompanionExecConfig(), device_id="phone-1"))
    assert calls[0]["timeout"] == 120.0
    assert result.stdout_truncated is True
    assert len(result.stdout.encode("utf-8")) <= 200_000
    assert result.stderr_truncated is True


def test_exec_run_refuses_risky_without_confirmation(tmp_path):
    with pytest.raises(CompanionExecError) as exc:
        asyncio.run(run(
            exec_id="exec-3", command="rm -rf /", cwd=str(tmp_path),
            session_id=None, timeout=None, requested_mode=None,
            confirm_danger=False, confirm_risky=False,
            config=CompanionExecConfig(), device_id="phone-1"))
    assert exc.value.code == "approval_required"


def test_exec_run_fails_closed_without_sandbox_backend(tmp_path, monkeypatch):
    # Simulate a host with no sandbox backend: run_command raises, and
    # run() must refuse with sandbox_unavailable, never run unconfined.
    # The missing backend is faked, not assumed — bwrap exists on CI
    # runners but not on this VM, so the old no-mock version passed
    # locally and failed on CI.
    from emrg.sandbox.contract import SandboxUnavailableError

    async def no_backend(command, *, policy, workdir, timeout):
        raise SandboxUnavailableError("bwrap is not installed")

    monkeypatch.setattr("emrg.tools.bash_tool_v2.run_command", no_backend)
    with pytest.raises(CompanionExecError) as exc:
        asyncio.run(run(
            exec_id="exec-4", command="echo hi", cwd=str(tmp_path),
            session_id=None, timeout=None, requested_mode=None,
            confirm_danger=False, confirm_risky=False,
            config=CompanionExecConfig(), device_id="phone-1"))
    assert exc.value.code == "sandbox_unavailable"


def test_exec_manager_cancel(tmp_path, monkeypatch):
    async def slow(command, *, policy, workdir, timeout):
        await asyncio.sleep(60)
        return SimpleNamespace(stdout="", stderr="", exit_code=0, signal=None,
                               timed_out=False, sandbox={})

    monkeypatch.setattr("emrg.tools.bash_tool_v2.run_command", slow)

    async def run_case():
        manager = ExecManager()
        task = asyncio.create_task(manager.run_exec(
            device_id="phone-1", command="sleep 60", cwd=str(tmp_path),
            session_id=None, timeout=None, requested_mode=None,
            confirm_danger=False, confirm_risky=False,
            config=CompanionExecConfig()))
        await asyncio.sleep(0.05)
        assert len(manager.running("phone-1")) == 1
        exec_id = manager.running("phone-1")[0]
        assert await manager.cancel("phone-1", exec_id) is True
        assert await manager.cancel("phone-1", "missing") is False
        assert manager.running("phone-1") == []
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(run_case())


def test_exec_dispatch_forces_workspace_cwd(tmp_path, monkeypatch):
    calls = []
    _fake_run(monkeypatch, calls)
    host = CompanionHostService(tmp_path / "registry.json")
    config = CompanionListenerConfig.build("127.0.0.1", 8765)
    listener = CompanionWssListener(
        host, config, tmp_path / "cert.pem", tmp_path / "key.pem")
    identity = {"device_id": "phone-1", "workspace_id": "ws",
                "cwd": str(tmp_path), "scopes": {"terminal.exec"},
                "generation": 1}

    class Socket:
        async def send(self, data):
            pass

        async def close(self, **kwargs):
            pass

    result = asyncio.run(listener._dispatch(
        {"type": "terminal.exec", "request_id": "r-1", "workspace_id": "ws",
         "command": "echo hi"}, identity, Socket()))
    assert result["type"] == "terminal.exec.result"
    # The client parses these exact keys.
    assert result["stdout"] == "hello\n"
    assert result["stderr"] == ""
    assert result["exit_code"] == 0
    assert result["exec_id"]
    assert calls[0]["workdir"] == str(tmp_path)
    result = asyncio.run(listener._dispatch(
        {"type": "terminal.exec_cancel", "request_id": "r-2",
         "workspace_id": "ws", "exec_id": "nope"}, identity, Socket()))
    assert result == {"type": "terminal.exec_cancel.result", "request_id": "r-2",
                      "workspace_id": "ws", "exec_id": "nope", "cancelled": False}
