import json

import pytest

from emrg.server.companion_host_service import CompanionHostService
from emrg.server.companion_auth import CompanionAuthenticationError


def _auth_frame(device_id, token):
    return json.dumps({"type": "auth", "protocol": "1.0",
                       "device_id": device_id, "credential": token,
                       "client_nonce": "client-nonce-0001"})


def test_companion_is_disabled_by_default_and_gates_operations(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    assert host.enabled is False
    assert host.pending_pairings() == []
    with pytest.raises(ValueError, match="disabled"):
        host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})
    with pytest.raises(CompanionAuthenticationError):
        host.authenticate("{}")
    host.enable()
    assert host.enabled is True


def test_disable_invalidates_pending_and_blocks_redeem(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pending = host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})
    assert host.disable() == 1
    assert host.enabled is False
    assert host.pending_pairings() == []
    assert host.disable() == 0
    with pytest.raises(ValueError, match="disabled"):
        host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})
    with pytest.raises(ValueError, match="unavailable"):
        host.pairings.redeem(pairing_id=pending["pairing_id"],
                             pairing_secret=pending["pairing_secret"], device_id="d")


def test_host_service_composes_local_pairing_and_auth(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pending = host.begin_pairing(workspace_id="workspace-1", cwd="/safe/work",
                                 scopes={"status", "chat.send"})
    listing = host.pending_pairings()
    assert listing == [{
        "pairing_id": pending["pairing_id"], "workspace_id": "workspace-1",
        "cwd": "/safe/work", "scopes": ["chat.send", "status"],
        "approved": None, "expires_in": 180,
    }]
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
    host.enable()
    request = host.begin_pairing(workspace_id="ws", cwd="/work", scopes={"status"})
    assert host.decide_pairing(request["pairing_id"], approved=False)
    with pytest.raises(ValueError):
        host.redeem_pairing(pairing_id=request["pairing_id"],
                             pairing_secret=request["pairing_secret"],
                             device_id="phone")
    second = host.begin_pairing(workspace_id="ws", cwd="/work", scopes={"status"})
    host.decide_pairing(second["pairing_id"], approved=True)
    token = host.redeem_pairing(pairing_id=second["pairing_id"],
                                pairing_secret=second["pairing_secret"],
                                device_id="phone")
    assert host.revoke_device("phone")
    with pytest.raises(CompanionAuthenticationError):
        host.authenticate(_auth_frame("phone", token))


def test_host_service_lifecycle_hook_invalidates_pending_requests(tmp_path):
    host = CompanionHostService(tmp_path / "registry.json")
    host.enable()
    pending = [host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})
               for _ in range(2)]
    assert host.invalidate_pending_pairings() == 2
    assert host.invalidate_pending_pairings() == 0
    assert host.pending_pairings() == []
    for item in pending:
        with pytest.raises(ValueError, match="unavailable"):
            host.redeem_pairing(pairing_id=item["pairing_id"],
                                pairing_secret=item["pairing_secret"], device_id="d")


def test_host_service_pairing_v2_is_device_bound_and_disabled_cancels(tmp_path):
    import base64
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    host = CompanionHostService(tmp_path / "registry-v2.json")
    host.enable()
    tx = host.begin_pairing_v2(
        host_identity="tls-fingerprint", workspace_id="ws",
        cwd="/safe/work", scopes={"status"})
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw)
    public_text = base64.urlsafe_b64encode(public).rstrip(b"=").decode("ascii")
    challenge = host.request_pairing_v2(
        pairing_id=tx["pairing_id"], device_id="android-v2",
        public_key=public_text)
    pending = host.pending_pairings_v2()
    assert pending[0]["device_id"] == "android-v2"
    assert pending[0]["public_key_sha256"]
    assert host.decide_pairing_v2(tx["pairing_id"], approved=True)
    payload = base64.urlsafe_b64decode(
        challenge["challenge"] + "=" * (-len(challenge["challenge"]) % 4))
    signature = base64.urlsafe_b64encode(private.sign(payload)).rstrip(b"=").decode("ascii")
    token = host.redeem_pairing_v2(
        pairing_id=tx["pairing_id"], device_id="android-v2",
        challenge_id=challenge["challenge_id"], signature=signature)
    assert host.registry.verify(device_id="android-v2", credential=token)["scopes"] == frozenset({"status"})

    pending2 = host.begin_pairing_v2(
        host_identity="tls-fingerprint", workspace_id="ws",
        cwd="/safe/work", scopes={"status"})
    assert host.disable() == 1
    assert host.pending_pairings_v2() == []
    with pytest.raises(ValueError, match="disabled"):
        host.request_pairing_v2(pairing_id=pending2["pairing_id"],
                                 device_id="other", public_key=public_text)


def test_host_service_can_cancel_one_v2_pairing_from_local_control_plane(tmp_path):
    import base64
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    host = CompanionHostService(tmp_path / "registry-v2-cancel.json")
    host.enable()
    tx = host.begin_pairing_v2(
        host_identity="tls-fingerprint", workspace_id="ws",
        cwd="/safe/work", scopes={"status"})
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw)
    public_text = base64.urlsafe_b64encode(public).rstrip(b"=").decode("ascii")
    host.request_pairing_v2(
        pairing_id=tx["pairing_id"], device_id="android-v2",
        public_key=public_text)

    assert len(host.pending_pairings_v2()) == 1
    assert host.cancel_pairing_v2(tx["pairing_id"]) is True
    assert host.cancel_pairing_v2(tx["pairing_id"]) is False
    assert host.pending_pairings_v2() == []
    assert host.decide_pairing_v2(tx["pairing_id"], approved=True) is False
    with pytest.raises(ValueError, match="unavailable"):
        host.redeem_pairing_v2(
            pairing_id=tx["pairing_id"], device_id="android-v2",
            challenge_id="A" * 22, signature="A" * 86)


def test_host_service_v2_cancel_is_safe_before_pairing_initialization(tmp_path):
    host = CompanionHostService(tmp_path / "registry-v2-empty.json")
    assert host.cancel_pairing_v2("invalid") is False


def test_disable_serializes_with_concurrent_pairing_creation(tmp_path, monkeypatch):
    import threading

    host = CompanionHostService(tmp_path / "registry-race.json")
    host.enable()
    entered = threading.Event()
    release = threading.Event()
    original_begin = host.pairings.begin
    created = []

    def blocked_begin(**kwargs):
        entered.set()
        assert release.wait(timeout=3)
        return original_begin(**kwargs)

    monkeypatch.setattr(host.pairings, "begin", blocked_begin)

    creator = threading.Thread(target=lambda: created.append(
        host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})))
    creator.start()
    assert entered.wait(timeout=3)

    disabled = threading.Event()

    def disable_host():
        host.disable()
        disabled.set()

    disabler = threading.Thread(target=disable_host)
    disabler.start()
    # disable must wait for the in-flight creation, then clear its transaction.
    assert not disabled.wait(timeout=0.05)
    release.set()
    creator.join(timeout=3)
    disabler.join(timeout=3)

    assert not creator.is_alive()
    assert not disabler.is_alive()
    assert disabled.is_set()
    assert len(created) == 1
    assert host.enabled is False
    assert host.pending_pairings() == []

def test_revoke_serializes_with_concurrent_authentication(tmp_path, monkeypatch):
    import threading

    host = CompanionHostService(tmp_path / "registry-revoke-race.json")
    host.enable()
    request = host.begin_pairing(workspace_id="ws", cwd="/w", scopes={"status"})
    assert host.decide_pairing(request["pairing_id"], approved=True)
    token = host.redeem_pairing(
        pairing_id=request["pairing_id"],
        pairing_secret=request["pairing_secret"],
        device_id="phone")

    entered = threading.Event()
    release = threading.Event()
    revoked = []
    auth_results = []
    original_revoke = host.registry.revoke

    def blocked_revoke(device_id):
        entered.set()
        assert release.wait(timeout=3)
        return original_revoke(device_id)

    monkeypatch.setattr(host.registry, "revoke", blocked_revoke)
    revoker = threading.Thread(target=lambda: revoked.append(host.revoke_device("phone")))
    revoker.start()
    assert entered.wait(timeout=3)

    auth_done = threading.Event()

    def authenticate():
        try:
            auth_results.append(host.authenticate(_auth_frame("phone", token)))
        except CompanionAuthenticationError:
            auth_results.append("rejected")
        finally:
            auth_done.set()

    authenticator = threading.Thread(target=authenticate)
    authenticator.start()
    assert not auth_done.wait(timeout=0.05)
    release.set()
    revoker.join(timeout=3)
    authenticator.join(timeout=3)

    assert not revoker.is_alive()
    assert not authenticator.is_alive()
    assert revoked == [True]
    assert auth_results == ["rejected"]

