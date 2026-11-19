import base64

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from emrg.server.companion_credentials import CredentialRegistry
from emrg.server.companion_pairing_v2 import PairingV2Error, PairingV2Transactions


class Clock:
    def __init__(self):
        self.now = 50.0
    def __call__(self):
        return self.now


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _setup(tmp_path):
    clock = Clock()
    registry = CredentialRegistry(tmp_path / "credentials.json")
    pairing = PairingV2Transactions(registry, host_identity="tls-sha256-fingerprint", clock=clock)
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw)
    return pairing, registry, clock, private, _b64(public)


def _request(pairing, public_key):
    transaction = pairing.begin(workspace_id="workspace", cwd="/safe/work",
                                scopes={"status", "sessions.list"})
    device = pairing.request(pairing_id=transaction["pairing_id"],
                             device_id="android-device", public_key=public_key)
    return transaction, device


def _signature(private, device):
    challenge = base64.urlsafe_b64decode(device["challenge"] + "=" * (-len(device["challenge"]) % 4))
    return _b64(private.sign(challenge))


def test_pairing_v2_requires_local_approval_and_binds_exact_device_key(tmp_path):
    pairing, registry, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    signed_payload = base64.urlsafe_b64decode(request["challenge"] + "=" * (-len(request["challenge"]) % 4))
    challenge_fields = __import__("json").loads(signed_payload)
    assert challenge_fields["host_identity"] == "tls-sha256-fingerprint"
    assert isinstance(challenge_fields["expires_at"], int)
    assert challenge_fields["device_id"] == "android-device"
    sig = _signature(private, request)
    with pytest.raises(PairingV2Error, match="not approved"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                        challenge_id=request["challenge_id"], signature=sig)
    pending = pairing.pending_requests()[0]
    assert pending["device_id"] == "android-device"
    assert pending["public_key_sha256"]
    assert "private_key" not in pending
    assert pairing.approve(tx["pairing_id"], approved=True)
    token = pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                           challenge_id=request["challenge_id"], signature=sig)
    identity = registry.verify(device_id="android-device", credential=token)
    assert identity["workspace_id"] == "workspace"
    assert identity["scopes"] == frozenset({"status", "sessions.list"})
    with pytest.raises(PairingV2Error, match="unavailable"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                       challenge_id=request["challenge_id"], signature=sig)


def test_pairing_v2_rejects_wrong_device_and_wrong_signature(tmp_path):
    pairing, _, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    signature = _signature(private, request)
    with pytest.raises(PairingV2Error, match="proof rejected"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="other-device",
                       challenge_id=request["challenge_id"], signature=signature)
    other = Ed25519PrivateKey.generate()
    with pytest.raises(PairingV2Error, match="proof rejected"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                       challenge_id=request["challenge_id"], signature=_signature(other, request))
    assert len(pairing.pending_requests()) == 1


def test_pairing_v2_rejects_second_device_request_and_duplicate_approval(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    tx, _ = _request(pairing, public)
    with pytest.raises(PairingV2Error, match="already received"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="second-device",
                        public_key=public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    assert not pairing.approve(tx["pairing_id"], approved=False)


def test_pairing_v2_expiry_and_cancel_are_terminal(tmp_path):
    pairing, _, clock, _, public = _setup(tmp_path)
    tx, _ = _request(pairing, public)
    clock.now += 180
    assert not pairing.approve(tx["pairing_id"], approved=True)
    assert pairing.pending_requests() == []
    fresh = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    assert pairing.cancel(fresh["pairing_id"])
    assert not pairing.cancel(fresh["pairing_id"])


def test_pairing_v2_rejects_noncanonical_or_wrong_length_keys(tmp_path):
    pairing, _, _, _, _ = _setup(tmp_path)
    tx = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="invalid device key"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android", public_key="not-base64!")
    with pytest.raises(PairingV2Error, match="invalid device key"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android", public_key=_b64(b"short"))
