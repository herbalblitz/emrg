"""Host command execution: tier selection, the danger gate, and real runs.

The dangerous part of a phone terminal is not the shell — the engine's sandbox
already owns that — it is the *widen*: a request that asks for a broader tier
than the session declared. These tests pin the narrowing rule and the two
guards around ``danger-full-access``, then run one harmless command through the
real :func:`emrg.tools.bash_tool_v2.run_command` seam.
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from emrg.gateway.config import GatewayConfig
from emrg.gateway.exec import resolve_mode, risky_reasons, run
from emrg.gateway.protocol import (
    ERR_APPROVAL_REQUIRED,
    ERR_INVALID_ARGUMENT,
    ERR_POLICY_REFUSED,
    ERR_SANDBOX_UNAVAILABLE,
    GatewayError,
)


def test_a_session_tier_cannot_be_widened_by_a_request():
    session = SimpleNamespace(sandbox="read-only")
    cfg = GatewayConfig(default_exec_mode="workspace-write")
    assert resolve_mode(session, cfg, "workspace-write", confirm_danger=False) == "read-only"
    assert resolve_mode(session, cfg, "read-only", confirm_danger=False) == "read-only"


def test_a_session_tier_can_be_narrowed_by_a_request():
    session = SimpleNamespace(sandbox="workspace-write")
    cfg = GatewayConfig()
    assert resolve_mode(session, cfg, "read-only", confirm_danger=False) == "read-only"


def test_a_session_without_a_tier_uses_the_gateway_default():
    cfg = GatewayConfig(default_exec_mode="workspace-write")
    assert resolve_mode(None, cfg, None, confirm_danger=False) == "workspace-write"
    assert resolve_mode(None, cfg, "read-only", confirm_danger=False) == "read-only"


def test_danger_full_access_needs_both_the_host_opt_in_and_confirmation():
    cfg = GatewayConfig(allow_danger_exec=False)
    with pytest.raises(GatewayError) as raised:
        resolve_mode(None, cfg, "danger-full-access", confirm_danger=True)
    assert raised.value.code == ERR_POLICY_REFUSED

    cfg = GatewayConfig(allow_danger_exec=True)
    with pytest.raises(GatewayError) as raised:
        resolve_mode(None, cfg, "danger-full-access", confirm_danger=False)
    assert raised.value.code == ERR_POLICY_REFUSED
    assert resolve_mode(None, cfg, "danger-full-access", confirm_danger=True) == "danger-full-access"


def test_an_unknown_tier_is_refused():
    with pytest.raises(GatewayError) as raised:
        resolve_mode(None, GatewayConfig(), "root", confirm_danger=False)
    assert raised.value.code == ERR_INVALID_ARGUMENT


def test_irreversible_commands_are_named():
    reasons = risky_reasons("rm -rf / && mkfs.ext4 /dev/sda1")
    assert any("recursive delete" in reason for reason in reasons)
    assert any("filesystem creation" in reason for reason in reasons)
    assert risky_reasons("echo hello") == []


def test_an_irreversible_command_requires_confirmation_before_it_runs(tmp_path):
    import asyncio

    cfg = GatewayConfig(allow_danger_exec=True, default_exec_mode="danger-full-access")
    with pytest.raises(GatewayError) as raised:
        asyncio.run(run(
            command="shutdown -h now",
            cwd=str(tmp_path),
            session_id=None,
            timeout=5,
            requested_mode=None,
            confirm_danger=True,
            confirm_risky=False,
            config=cfg,
            device_id="d1",
        ))
    assert raised.value.code == ERR_APPROVAL_REQUIRED


def test_a_plain_command_runs_through_the_engine_seam(tmp_path):
    import asyncio

    cfg = GatewayConfig(allow_danger_exec=True, default_exec_mode="danger-full-access")
    result = asyncio.run(run(
        command="printf 'hello from the host'",
        cwd=str(tmp_path),
        session_id=None,
        timeout=10,
        requested_mode=None,
        confirm_danger=True,
        confirm_risky=False,
        config=cfg,
        device_id="d1",
    ))
    assert result.exit_code == 0
    assert result.stdout == "hello from the host"
    assert result.timed_out is False
    assert result.sandbox["mode"] == "danger-full-access"


def test_a_confined_run_fails_closed_when_the_backend_is_missing(tmp_path):
    """No bwrap/Seatbelt/ACL runner on this host means no run, not an unconfined one.

    The gateway must surface the engine's :class:`SandboxUnavailableError` as a
    typed refusal. On a host that does have the backend, the command runs; the
    test asserts the one property that holds either way: nothing was executed
    unconfined.
    """
    import asyncio
    import os

    if os.name == "nt":
        pytest.skip("the workspace-write backend is exercised by the Windows runner")
    cfg = GatewayConfig(default_exec_mode="workspace-write")
    try:
        result = asyncio.run(run(
            command="printf ok",
            cwd=str(tmp_path),
            session_id=None,
            timeout=10,
            requested_mode="workspace-write",
            confirm_danger=False,
            confirm_risky=False,
            config=cfg,
            device_id="d1",
        ))
    except GatewayError as exc:
        assert exc.code == ERR_SANDBOX_UNAVAILABLE
        return
    assert result.sandbox.get("enforcement"), "a confined run must name its enforcement"
