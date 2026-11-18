from pathlib import Path

import pytest

from emrg.server.companion_credentials import CredentialRegistry
from emrg.server.companion_pairing import PairingError, PairingTransactions


class FakeClock:
    def __init__(self):
        self.now = 100.0
    def __call__(self):
        return self.now


def make_pairing(tmp_path: Path, clock=None):
    return PairingTransactions(CredentialRegistry(tmp_path / "creds.json"),
                              clock=clock or FakeClock())


def test_pairing_requires_local_approval_and_is_single_use(tmp_path):
    pairing = make_pairing(tmp_path)
    request = pairing.begin(workspace_id="ws", cwd="/workspace",
                            scopes={"chat.send"})
    with pytest.raises(PairingError, match="not approved"):
        pairing.redeem(pairing_id=request["pairing_id"],
                       pairing_secret=request["pairing_secret"], device_id="phone")
    assert pairing.approve(request["pairing_id"], approved=True)
    token = pairing.redeem(pairing_id=request["pairing_id"],
                           pairing_secret=request["pairing_secret"], device_id="phone")
    assert pairing._registry.verify(device_id="phone", credential=token)["cwd"] == "/workspace"
    with pytest.raises(PairingError, match="unavailable"):
        pairing.redeem(pairing_id=request["pairing_id"],
                       pairing_secret=request["pairing_secret"], device_id="phone")


def test_reject_and_cancel_are_terminal(tmp_path):
    pairing = make_pairing(tmp_path)
    request = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    assert pairing.approve(request["pairing_id"], approved=False)
    with pytest.raises(PairingError):
        pairing.redeem(pairing_id=request["pairing_id"],
                       pairing_secret=request["pairing_secret"], device_id="d")
    request2 = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    assert pairing.cancel(request2["pairing_id"])
    assert not pairing.cancel(request2["pairing_id"])


def test_expiry_and_wrong_secret_attempt_limit(tmp_path):
    clock = FakeClock()
    pairing = make_pairing(tmp_path, clock)
    expired = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    clock.now += 180
    assert not pairing.approve(expired["pairing_id"], approved=True)
    with pytest.raises(PairingError, match="unavailable"):
        pairing.redeem(pairing_id=expired["pairing_id"],
                       pairing_secret=expired["pairing_secret"], device_id="expired-phone")

    clock.now = 200
    request = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    pairing.approve(request["pairing_id"], approved=True)
    for _ in range(5):
        with pytest.raises(PairingError, match="invalid"):
            pairing.redeem(pairing_id=request["pairing_id"],
                           pairing_secret="bad", device_id="d")
    with pytest.raises(PairingError, match="unavailable"):
        pairing.redeem(pairing_id=request["pairing_id"],
                       pairing_secret=request["pairing_secret"], device_id="d")


def test_pairing_scope_validation(tmp_path):
    pairing = make_pairing(tmp_path)
    with pytest.raises(PairingError):
        pairing.begin(workspace_id="ws", cwd="/w", scopes={"admin"})
