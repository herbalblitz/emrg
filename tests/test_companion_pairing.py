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


def test_local_decisions_reject_malformed_pairing_ids_and_non_boolean_choice(tmp_path):
    pairing = make_pairing(tmp_path)
    request = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    assert not pairing.approve([], approved=True)
    assert not pairing.approve("x" * 129, approved=True)
    assert not pairing.approve(request["pairing_id"], approved="yes")
    assert not pairing.cancel([])
    assert not pairing.cancel("x" * 129)
    assert pairing.pending_requests()[0]["approved"] is None
    assert pairing.approve(request["pairing_id"], approved=True)
    assert not pairing.approve(request["pairing_id"], approved=False)


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


@pytest.mark.parametrize("workspace_id,cwd,scopes", [
    ("w" * 129, "/w", {"status"}),
    ("ws", "/w" * 2049, {"status"}),
    ("\ud800", "/w", {"status"}),
    ("ws", "/w", "status"),
    ("ws", "/w", {1}),
])
def test_pairing_rejects_unbounded_or_malformed_inputs(tmp_path, workspace_id, cwd, scopes):
    pairing = make_pairing(tmp_path)
    with pytest.raises(PairingError, match="invalid pairing request"):
        pairing.begin(workspace_id=workspace_id, cwd=cwd, scopes=scopes)


def test_pairing_redeem_rejects_malformed_identity_text(tmp_path):
    pairing = make_pairing(tmp_path)
    with pytest.raises(PairingError, match="invalid device"):
        pairing.redeem(pairing_id="id", pairing_secret="secret", device_id="\ud800")
    with pytest.raises(PairingError, match="pairing secret invalid"):
        pairing.redeem(pairing_id="id", pairing_secret="s" * 513, device_id="phone")
    with pytest.raises(PairingError, match="pairing unavailable"):
        pairing.redeem(pairing_id="i" * 129, pairing_secret="secret", device_id="phone")


def test_pending_pairings_are_bounded_and_expiry_frees_capacity(tmp_path):
    clock = FakeClock()
    pairing = make_pairing(tmp_path, clock)
    requests = [pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
                for _ in range(32)]
    with pytest.raises(PairingError, match="capacity"):
        pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    clock.now += 180
    # Expired transactions are purged before enforcing the cap.
    replacement = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    assert replacement["pairing_id"] not in {r["pairing_id"] for r in requests}


def test_cancel_all_invalidates_every_pending_transaction(tmp_path):
    pairing = make_pairing(tmp_path)
    requests = [pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
                for _ in range(3)]
    assert pairing.cancel_all() == 3
    assert pairing.cancel_all() == 0
    assert pairing.pending_requests() == []
    for request in requests:
        with pytest.raises(PairingError, match="unavailable"):
            pairing.redeem(pairing_id=request["pairing_id"],
                           pairing_secret=request["pairing_secret"], device_id="d")


def test_pairing_id_has_256_bits_of_random_entropy(tmp_path):
    pairing = make_pairing(tmp_path)
    request = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    # 32 random bytes encode to 43 unpadded URL-safe base64 characters.
    assert len(request["pairing_id"]) == 43


def test_concurrent_redemption_issues_exactly_one_credential(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    pairing = make_pairing(tmp_path)
    request = pairing.begin(workspace_id="ws", cwd="/w", scopes={"status"})
    assert pairing.approve(request["pairing_id"], approved=True)
    workers = 8
    barrier = Barrier(workers)

    def redeem_once():
        barrier.wait()
        try:
            return pairing.redeem(
                pairing_id=request["pairing_id"],
                pairing_secret=request["pairing_secret"],
                device_id="single-device")
        except PairingError:
            return None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda _: redeem_once(), range(workers)))

    issued = [value for value in results if value is not None]
    assert len(issued) == 1
    identity = pairing._registry.verify(device_id="single-device", credential=issued[0])
    assert identity["scopes"] == frozenset({"status"})
    with pytest.raises(PairingError, match="unavailable"):
        pairing.redeem(pairing_id=request["pairing_id"],
                       pairing_secret=request["pairing_secret"], device_id="replay")
