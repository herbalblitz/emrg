import json

import pytest

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_auth import CompanionAuthenticationError


def _auth_frame(device_id, token):
    return json.dumps({"type": "auth", "protocol": "1.0",
                       "device_id": device_id, "credential": token,
                       "client_nonce": "client-nonce"})


def test_host_service_composes_local_pairing_and_auth(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    pending = host.begin_pairing(workspace_id="workspace-1", cwd="/safe/work",
                                 scopes={"status", "chat.send"})
    listing = host.pending_pairings()
    assert listing == [{
        "pairing_id": pending["pairing_id"], "workspace_id": "workspace-1",
        "cwd": "/safe/work", "scopes": ["chat.send", "status"],
        "approved": None, "expires_in": 180,
    }]
    # Secret/hash never included in local pending-list metadata.
    assert "pairing_secret" not in listing[0]
    assert host.decide_pairing(pending["pairing_id"], approved=True)
    token = host.redeem_pairing(pairing_id=pending["pairing_id"],
                                pairing_secret=pending["pairing_secret"],
                                device_id="android-1")
    authenticated = host.authenticate(_auth_frame("android-1", token))
    assert authenticated["workspace_id"] == "workspace-1"
    assert authenticated["scopes"] == frozenset({"status", "chat.send"})
    assert host.registered_devices()[0]["device_id"] == "android-1"


def test_host_service_reject_and_revoke(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    request = host.begin_pairing(workspace_id="ws", cwd="/work", scopes={"status"})
    assert host.decide_pairing(request["pairing_id"], approved=False)
    with pytest.raises(ValueError):
        host.redeem_pairing(pairing_id=request["pairing_id"],
                             pairing_secret=request["pairing_secret"],
                             device_id="phone")
    # Create a second approved device and ensure revocation blocks authentication.
    second = host.begin_pairing(workspace_id="ws", cwd="/work", scopes={"status"})
    host.decide_pairing(second["pairing_id"], approved=True)
    token = host.redeem_pairing(pairing_id=second["pairing_id"],
                                pairing_secret=second["pairing_secret"],
                                device_id="phone")
    assert host.revoke_device("phone")
    with pytest.raises(CompanionAuthenticationError):
        host.authenticate(_auth_frame("phone", token))
