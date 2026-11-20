"""Tests for the allow-listed trusted-local Companion control facade."""
import base64

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_local_control import (
    CompanionLocalControl,
    CompanionLocalControlError,
)


def _pending(host):
    tx = host.begin_pairing_v2(
        host_identity="host-tls-fingerprint",
        workspace_id="workspace-1",
        cwd="/safe/work",
        scopes={"status"},
    )
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_text = base64.urlsafe_b64encode(public).rstrip(b"=").decode("ascii")
    nonce = base64.urlsafe_b64encode(b"n" * 32).rstrip(b"=").decode("ascii")
    host.request_pairing_v2(
        pairing_id=tx["pairing_id"],
        device_id="android-device-1",
        public_key=public_text,
        requested_scopes=["status"],
        client_nonce=nonce,
    )
    return tx["pairing_id"]


def test_local_control_lists_only_bounded_non_secret_projection(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pairing_id = _pending(host)
    control = CompanionLocalControl(host)

    result = control.dispatch("pairing.pending.list")
    assert len(result["items"]) == 1
    item = result["items"][0]
    assert item["pairing_id"] == pairing_id
    assert item["device_id"] == "android-device-1"
    assert item["public_key_sha256"]
    assert item["scopes"] == ["status"]
    assert set(item) == {
        "pairing_id", "device_id", "public_key_sha256", "workspace_id",
        "cwd", "scopes", "approved", "approved_scopes", "expires_in",
    }
    assert not ({"public_key", "client_nonce", "challenge", "signature",
                 "credential_token", "pairing_secret"} & set(item))
    assert control.dispatch("status") == {"enabled": True, "pending_count": 1}


def test_local_control_decision_targets_exact_pending_transaction(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pairing_id = _pending(host)
    control = CompanionLocalControl(host)

    assert control.dispatch("pairing.decide", {
        "pairing_id": pairing_id, "approved": True,
    }) == {"decided": True}
    item = control.dispatch("pairing.pending.list")["items"][0]
    assert item["approved"] is True


def test_local_control_cancel_removes_one_pending_transaction(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pairing_id = _pending(host)
    control = CompanionLocalControl(host)

    assert control.dispatch("pairing.cancel", {"pairing_id": pairing_id}) == {
        "cancelled": True,
    }
    assert control.dispatch("pairing.pending.list") == {"items": []}


def test_local_control_fails_closed_when_host_is_disabled(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    control = CompanionLocalControl(host)

    assert control.dispatch("status") == {"enabled": False, "pending_count": 0}
    assert control.dispatch("pairing.pending.list") == {"items": []}
    with pytest.raises(CompanionLocalControlError, match="pairing unavailable"):
        control.dispatch("pairing.decide", {
            "pairing_id": "A" * 43, "approved": True,
        })


@pytest.mark.parametrize("operation,payload", [
    ("pairing.pending.list", {"extra": True}),
    ("pairing.decide", {"pairing_id": "A" * 43, "approved": 1}),
    ("pairing.decide", {"pairing_id": "A" * 42, "approved": True}),
    ("pairing.cancel", {"pairing_id": "A" * 43, "path": "/tmp"}),
    ("companion.call", {"method": "approve"}),
    ("status", {"path": "/tmp"}),
])
def test_local_control_rejects_unknown_operations_and_extra_or_invalid_fields(
    tmp_path, operation, payload
):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    control = CompanionLocalControl(host)

    with pytest.raises(CompanionLocalControlError):
        control.dispatch(operation, payload)


def test_local_control_rejects_non_object_payload(tmp_path):
    control = CompanionLocalControl(CompanionHostService(tmp_path / "registry.json"))
    with pytest.raises(CompanionLocalControlError):
        control.dispatch("pairing.pending.list", [])


def test_local_control_generates_offline_svg_qr_for_valid_v2_descriptor(tmp_path):
    import json
    import time
    import base64

    host = CompanionHostService(tmp_path / "registry.json")
    control = CompanionLocalControl(host)
    descriptor = json.dumps({
        "version": 2,
        "endpoint": "wss://192.168.1.20:18476",
        "fingerprint_sha256": ":".join(["AA"] * 32),
        "expires_at": int(time.time()) + 120,
        "pairing_id": "A" * 43,
    })
    result = control.dispatch("pairing.qr", {"descriptor": descriptor})
    assert result["data_uri"].startswith("data:image/svg+xml;base64,")
    svg = base64.b64decode(result["data_uri"].split(",", 1)[1])
    assert b"<svg" in svg
    assert b"192.168.1.20" not in svg


@pytest.mark.parametrize("descriptor", [
    "",
    "not-json",
    '{"version":2,"endpoint":"ws://127.0.0.1","fingerprint_sha256":"AA","expires_at":9999999999,"pairing_id":"' + "A" * 43 + '"}',
    '{"version":2,"endpoint":"wss://127.0.0.1","fingerprint_sha256":"AA","expires_at":9999999999,"pairing_id":"' + "A" * 43 + '","token":"secret"}',
])
def test_local_control_rejects_invalid_qr_descriptor(tmp_path, descriptor):
    control = CompanionLocalControl(CompanionHostService(tmp_path / "registry.json"))
    with pytest.raises(CompanionLocalControlError):
        control.dispatch("pairing.qr", {"descriptor": descriptor})

# ── Batch 0: subset approval on pairing.decide ─────────────────────────

def test_local_control_decide_approves_subset(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pairing_id = _pending(host)
    control = CompanionLocalControl(host)
    # _pending requests only ["status"]; approve a subset of a wider
    # request by re-requesting is not possible here, so approve the
    # requested scope itself and confirm the projection carries it.
    result = control.dispatch("pairing.decide", {
        "pairing_id": pairing_id, "approved": True, "scopes": ["status"],
    })
    assert result == {"decided": True}
    item = control.dispatch("pairing.pending.list")["items"][0]
    assert item["approved"] is True
    assert item["approved_scopes"] == ["status"]


def test_local_control_decide_rejects_subset_outside_request(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pairing_id = _pending(host)
    control = CompanionLocalControl(host)
    # "chat.send" was never requested by this device.
    result = control.dispatch("pairing.decide", {
        "pairing_id": pairing_id, "approved": True,
        "scopes": ["status", "chat.send"],
    })
    assert result == {"decided": False}
    assert control.dispatch("pairing.pending.list")["items"][0]["approved"] is None


@pytest.mark.parametrize("payload", [
    {"pairing_id": "A" * 43, "approved": True, "scopes": []},
    {"pairing_id": "A" * 43, "approved": True, "scopes": ["nope"]},
    {"pairing_id": "A" * 43, "approved": True, "scopes": "status"},
    {"pairing_id": "A" * 43, "approved": False, "scopes": ["status"]},
    {"pairing_id": "A" * 43, "approved": True, "scopes": ["status", "status"]},
    {"pairing_id": "A" * 43, "approved": True, "extra": 1},
])
def test_local_control_decide_rejects_malformed_subset(payload, tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    control = CompanionLocalControl(host)
    with pytest.raises(CompanionLocalControlError):
        control.dispatch("pairing.decide", payload)
