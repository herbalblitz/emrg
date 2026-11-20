"""Tests for companion device list/revoke (host-service wrapper)."""
import json
import logging

import pytest

from emrg.server.companion_auth import CompanionAuthenticationError
from emrg.server.companion_devices import (
    CompanionDevicesError,
    list_devices,
    revoke_device,
)
from emrg.server.companion_host_service import CompanionHostService


def _auth_frame(device_id, token):
    return json.dumps({"type": "auth", "protocol": "1.0",
                       "device_id": device_id, "credential": token,
                       "client_nonce": "client-nonce-0001"})


def _register(host, device_id, workspace_id="ws"):
    pending = host.begin_pairing(workspace_id=workspace_id, cwd="/work",
                                 scopes={"status"})
    assert host.decide_pairing(pending["pairing_id"], approved=True)
    return host.redeem_pairing(pairing_id=pending["pairing_id"],
                               pairing_secret=pending["pairing_secret"],
                               device_id=device_id)


def _host_with_devices(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    _register(host, "phone-1")
    _register(host, "phone-2")
    return host


def test_list_devices_returns_safe_metadata_only(tmp_path):
    host = _host_with_devices(tmp_path)
    devices = list_devices(host)
    assert [d["device_id"] for d in devices] == ["phone-1", "phone-2"]
    for device in devices:
        assert set(device) == {"device_id", "workspace_id", "scopes", "revoked"}
        assert device["revoked"] is False
    blob = json.dumps(devices)
    assert "verifier" not in blob and "salt" not in blob and "token" not in blob


def test_revoke_other_device_is_audit_logged(tmp_path, caplog):
    host = _host_with_devices(tmp_path)
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_devices"):
        outcome = revoke_device(host, device_id="phone-2", current_device_id="phone-1")
    assert outcome == {"device_id": "phone-2", "revoked": True}
    assert "companion devices allowed" in caplog.text
    assert "devices.revoke" in caplog.text
    assert "phone-1" in caplog.text and "phone-2" in caplog.text
    devices = {d["device_id"]: d for d in list_devices(host)}
    assert devices["phone-2"]["revoked"] is True
    assert devices["phone-1"]["revoked"] is False


def test_self_revocation_is_blocked_and_audit_logged(tmp_path, caplog):
    host = _host_with_devices(tmp_path)
    token = _register(host, "phone-9")
    identity = host.authenticate(_auth_frame("phone-9", token))
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_devices"):
        with pytest.raises(CompanionDevicesError) as exc:
            revoke_device(host, device_id="phone-9", current_device_id="phone-9")
    assert exc.value.code == "cannot_revoke_self"
    assert "companion devices denied" in caplog.text
    # The connection is not killed silently: the device stays registered
    # and unrevoked, so the listener keeps answering it.
    devices = {d["device_id"]: d for d in list_devices(host)}
    assert devices["phone-9"]["revoked"] is False
    assert host.is_identity_current(identity)


def test_revoke_unknown_device_is_an_error(tmp_path):
    host = _host_with_devices(tmp_path)
    with pytest.raises(CompanionDevicesError) as exc:
        revoke_device(host, device_id="ghost", current_device_id="phone-1")
    assert exc.value.code == "device_not_found"


def test_revoke_rejects_empty_device_id(tmp_path):
    host = _host_with_devices(tmp_path)
    with pytest.raises(CompanionDevicesError) as exc:
        revoke_device(host, device_id="", current_device_id="phone-1")
    assert exc.value.code == "invalid_device"


def test_revoked_device_cannot_authenticate_after_remote_revoke(tmp_path):
    host = _host_with_devices(tmp_path)
    token = _register(host, "phone-3")
    assert revoke_device(host, device_id="phone-3", current_device_id="phone-1")
    with pytest.raises(CompanionAuthenticationError):
        host.authenticate(_auth_frame("phone-3", token))
