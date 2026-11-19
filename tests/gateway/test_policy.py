"""The proxy allow-list is measured against the daemon's own dispatch table.

A hand-written list of request types drifts the moment the engine adds one, and
the drift is silent in the dangerous direction: a new frame either becomes
reachable without a decision (if the gateway forwards everything) or the phone
loses a feature nobody notices. This test reads both sides: every daemon
dispatch type must be either proxied or deliberately blocked, and every proxied
name must exist on the daemon side.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from emrg.gateway.policy import (
    BLOCKED_TYPES,
    PROXIED_TYPES,
    RateLimiter,
    allowed,
    classify,
)
from emrg.gateway.protocol import ERR_RATE_LIMITED, GatewayError

DAEMON = Path(__file__).resolve().parents[2] / "emrg" / "server" / "daemon.py"


def _daemon_types() -> set[str]:
    text = DAEMON.read_text(encoding="utf-8", errors="replace")
    # `auth` is handled before dispatch, and the two host-lifecycle names are
    # in the same branch chain; the regex reads the dispatch shape, which is
    # what the gateway's list has to answer to.
    found = set(re.findall(r'msg_type\s*==\s*"([a-z_]+)"', text))
    # `_handle_client` intercepts task/cancel/approval_response with
    # `data.get("type") == ...` before the dispatch chain; both shapes are part
    # of the daemon's request vocabulary.
    found.update(re.findall(r'data\.get\("type"\)\s*==\s*"([a-z_]+)"', text))
    found.update({"auth"})
    return found


def test_the_proxy_list_covers_every_daemon_request_type():
    daemon = _daemon_types()
    known = PROXIED_TYPES | BLOCKED_TYPES
    unknown = sorted(daemon - known)
    assert not unknown, (
        f"the daemon dispatches {unknown} but the gateway neither proxies nor blocks them. "
        "Decide which one it is in emrg/gateway/policy.py; a silent addition must not "
        "become a phone feature by accident."
    )


def test_every_proxied_name_exists_on_the_daemon_side():
    daemon = _daemon_types()
    # Frames the gateway answers itself are not daemon names.
    gateway_only = {"gw_auth", "gw_attach", "gw_ping", "gw_exec", "gw_exec_cancel",
                    "gw_read_file", "gw_write_file", "gw_config_get", "gw_config_set",
                    "gw_logs_tail", "gw_devices", "gw_revoke_device", "gw_audit_tail"}
    phantom = sorted(PROXIED_TYPES - daemon - gateway_only)
    assert not phantom, f"PROXIED_TYPES names frames the daemon does not dispatch: {phantom}"


def test_host_lifecycle_actions_are_never_reachable():
    for name in ("shutdown", "init_auto_evolve"):
        assert name not in PROXIED_TYPES
        assert not allowed(name)
    assert "shutdown" in BLOCKED_TYPES
    assert "init_auto_evolve" in BLOCKED_TYPES


def test_read_mutate_and_chat_are_classified_for_limits():
    assert classify("list_sessions") == "read"
    assert classify("task") == "chat"
    assert classify("delete_session") == "mutate"
    assert classify("set_sandbox") == "mutate"


def test_the_limiter_refuses_after_the_window_budget_and_recovers():
    now = [0.0]
    limiter = RateLimiter(clock=lambda: now[0])
    from emrg.gateway.policy import Limit

    limit = Limit(2, window=10.0)
    limiter.check("d1", "read", limit)
    limiter.check("d1", "read", limit)
    with pytest.raises(GatewayError) as raised:
        limiter.check("d1", "read", limit)
    assert raised.value.code == ERR_RATE_LIMITED
    assert raised.value.retryable is True
    now[0] += 10.1
    limiter.check("d1", "read", limit)


def test_one_device_cannot_spend_another_devices_budget():
    from emrg.gateway.policy import Limit

    limiter = RateLimiter(clock=lambda: 1.0)
    limit = Limit(1, window=60.0)
    limiter.check("d1", "exec", limit)
    limiter.check("d2", "exec", limit)
