import base64

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

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
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo)
    return pairing, registry, clock, private, _b64(public)


def _request(pairing, public_key):
    transaction = pairing.begin(workspace_id="workspace", cwd="/safe/work",
                                scopes={"status", "sessions.list"})
    device = pairing.request(pairing_id=transaction["pairing_id"],
                             device_id="android-device", public_key=public_key,
                             requested_scopes=["status", "sessions.list"], client_nonce=_b64(b"n" * 32))
    return transaction, device


def _signature(private, device):
    challenge = base64.urlsafe_b64decode(device["challenge"] + "=" * (-len(device["challenge"]) % 4))
    return _b64(private.sign(challenge, ec.ECDSA(hashes.SHA256())))


def test_pairing_v2_requires_local_approval_and_binds_exact_device_key(tmp_path):
    pairing, registry, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    signed_payload = base64.urlsafe_b64decode(request["challenge"] + "=" * (-len(request["challenge"]) % 4))
    challenge_fields = __import__("json").loads(signed_payload)
    assert challenge_fields["host_identity"] == "tls-sha256-fingerprint"
    assert isinstance(challenge_fields["expires_at"], int)
    assert challenge_fields["device_id"] == "android-device"
    assert challenge_fields["client_nonce"] == _b64(b"n" * 32)
    assert challenge_fields["scopes"] == ["sessions.list", "status"]
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
    # A lost first response is recoverable: the exact signed transaction
    # returns the same credential without rotating or issuing another one.
    assert pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                          challenge_id=request["challenge_id"], signature=sig) == token
    assert pairing.pending_requests() == []
    assert len(registry.list_devices()) == 1


def test_pairing_v2_rejects_wrong_device_and_wrong_signature(tmp_path):
    pairing, _, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    signature = _signature(private, request)
    with pytest.raises(PairingV2Error, match="proof rejected"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="other-device",
                       challenge_id=request["challenge_id"], signature=signature)
    other = ec.generate_private_key(ec.SECP256R1())
    with pytest.raises(PairingV2Error, match="proof rejected"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                       challenge_id=request["challenge_id"], signature=_signature(other, request))
    assert len(pairing.pending_requests()) == 1


def test_pairing_v2_exact_request_retry_returns_original_challenge(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    tx, original = _request(pairing, public)

    # Simulate the challenge response being lost and the device retransmitting
    # the byte-for-byte same logical request with the same nonce.
    retry = pairing.request(
        pairing_id=tx["pairing_id"], device_id="android-device",
        public_key=public, requested_scopes=["status", "sessions.list"],
        client_nonce=_b64(b"n" * 32),
    )
    assert retry == original

    # The same retry remains valid if host-local approval happened before
    # the client received the first response.
    assert pairing.approve(tx["pairing_id"], approved=True)
    assert pairing.request(
        pairing_id=tx["pairing_id"], device_id="android-device",
        public_key=public, requested_scopes=["status", "sessions.list"],
        client_nonce=_b64(b"n" * 32),
    ) == original

    # Idempotency is exact-request only; it cannot replace the device identity,
    # requested scope set, public key, or nonce.
    with pytest.raises(PairingV2Error, match="already received"):
        pairing.request(
            pairing_id=tx["pairing_id"], device_id="other-device",
            public_key=public, requested_scopes=["status"],
            client_nonce=_b64(b"z" * 32),
        )


def test_pairing_v2_rejects_second_device_request_and_duplicate_approval(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    tx, _ = _request(pairing, public)
    with pytest.raises(PairingV2Error, match="already received"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="second-device",
                        public_key=public, requested_scopes=["status"], client_nonce=_b64(b"n" * 32))
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
        pairing.request(pairing_id=tx["pairing_id"], device_id="android", public_key="not-base64!", requested_scopes=["status"], client_nonce=_b64(b"n" * 32))
    with pytest.raises(PairingV2Error, match="invalid device key"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android", public_key=_b64(b"short"), requested_scopes=["status"], client_nonce=_b64(b"n" * 32))


def test_pairing_v2_concurrent_redemption_issues_exactly_one_credential(tmp_path):
    from concurrent.futures import ThreadPoolExecutor

    pairing, registry, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    signature = _signature(private, request)
    assert pairing.approve(tx["pairing_id"], approved=True)

    def redeem():
        try:
            return pairing.redeem(
                pairing_id=tx["pairing_id"], device_id="android-device",
                challenge_id=request["challenge_id"], signature=signature)
        except PairingV2Error:
            return None

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: redeem(), range(8)))

    issued = [token for token in results if token is not None]
    # Concurrent duplicate redeems all receive one identical token.
    assert len(issued) == 8
    assert len(set(issued)) == 1
    identity = registry.verify(device_id="android-device", credential=issued[0])
    assert identity is not None
    assert identity["workspace_id"] == "workspace"
    assert identity["scopes"] == frozenset({"status", "sessions.list"})
    assert len(pairing.pending_requests()) == 0


def test_pairing_v2_pending_capacity_is_bounded_and_cancel_all_clears(tmp_path):
    pairing, _, _, _, _ = _setup(tmp_path)
    for _ in range(32):
        pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="capacity"):
        pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    assert pairing.cancel_all() == 32
    assert pairing.cancel_all() == 0
    fresh = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    assert fresh["pairing_id"]


def test_pairing_v2_rejects_padded_or_noncanonical_base64url(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    tx = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="invalid device key"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android-device",
                        public_key=public + "=", requested_scopes=["status"], client_nonce=_b64(b"n" * 32))
    with pytest.raises(PairingV2Error, match="pairing unavailable"):
        pairing.request(pairing_id=tx["pairing_id"] + "=", device_id="android-device",
                        public_key=public, requested_scopes=["status"], client_nonce=_b64(b"n" * 32))
    with pytest.raises(PairingV2Error, match="invalid device"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android device",
                        public_key=public, requested_scopes=["status"], client_nonce=_b64(b"n" * 32))
    with pytest.raises(PairingV2Error, match="invalid device"):
        pairing.request(pairing_id=tx["pairing_id"], device_id="android\nforged",
                        public_key=public, requested_scopes=["status"], client_nonce=_b64(b"n" * 32))


def test_pairing_v2_rejects_padded_challenge_and_signature(tmp_path):
    pairing, _, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    signature = _signature(private, request)
    with pytest.raises(PairingV2Error, match="invalid challenge"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                        challenge_id=request["challenge_id"] + "=",
                        signature=signature)
    with pytest.raises(PairingV2Error, match="invalid proof"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                        challenge_id=request["challenge_id"], signature=signature + "=")

def test_pairing_v2_cancel_rejects_noncanonical_identifier(tmp_path):
    pairing, _, _, _, _ = _setup(tmp_path)
    tx = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    pairing_id = tx["pairing_id"]

    assert pairing.cancel(pairing_id + "=") is False
    assert pairing.cancel(pairing_id + " ") is False
    assert len(pairing.pending_requests()) == 0
    assert pairing.cancel(pairing_id) is True
    assert len(pairing.pending_requests()) == 0

def test_pairing_v2_consumes_approved_transaction_if_credential_issuance_fails(tmp_path, monkeypatch):
    pairing, registry, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    signature = _signature(private, request)

    def fail_issue(**kwargs):
        raise OSError("credential storage unavailable")

    monkeypatch.setattr(registry, "issue", fail_issue)
    with pytest.raises(OSError, match="storage unavailable"):
        pairing.redeem(pairing_id=tx["pairing_id"], device_id="android-device",
                        challenge_id=request["challenge_id"], signature=signature)

    # A failed write leaves the approved transaction retryable. No token
    # has been issued, and the same exact proof can safely be retried.
    assert len(pairing.pending_requests()) == 1
    monkeypatch.undo()
    token = pairing.redeem(
        pairing_id=tx["pairing_id"], device_id="android-device",
        challenge_id=request["challenge_id"], signature=signature)
    assert registry.verify(device_id="android-device", credential=token) is not None
    assert pairing.redeem(
        pairing_id=tx["pairing_id"], device_id="android-device",
        challenge_id=request["challenge_id"], signature=signature) == token

@pytest.mark.parametrize("unsafe", ["workspace\nforged", "workspace\u202Eforged"])
def test_pairing_v2_rejects_display_control_characters_in_workspace(tmp_path, unsafe):
    pairing, _, _, _, _ = _setup(tmp_path)
    with pytest.raises(PairingV2Error, match="invalid pairing request"):
        pairing.begin(workspace_id=unsafe, cwd="/safe/work", scopes={"status"})


@pytest.mark.parametrize("unsafe_cwd", ["/safe\n/forged", "/safe/\u202Eforged"])
def test_pairing_v2_rejects_display_control_characters_in_cwd(tmp_path, unsafe_cwd):
    pairing, _, _, _, _ = _setup(tmp_path)
    with pytest.raises(PairingV2Error, match="invalid pairing request"):
        pairing.begin(workspace_id="workspace", cwd=unsafe_cwd, scopes={"status"})


@pytest.mark.parametrize("unsafe_identity", ["host\nforged", "host\u202Eforged"])
def test_pairing_v2_rejects_display_control_characters_in_host_identity(
    tmp_path, unsafe_identity
):
    registry = CredentialRegistry(tmp_path / "credentials.json")
    with pytest.raises(PairingV2Error, match="invalid host identity"):
        PairingV2Transactions(registry, host_identity=unsafe_identity)

def test_pairing_v2_cancel_and_redeem_race_has_one_terminal_winner(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier

    pairing, registry, _, private, public = _setup(tmp_path)
    tx, request = _request(pairing, public)
    assert pairing.approve(tx["pairing_id"], approved=True)
    signature = _signature(private, request)
    start = Barrier(2)

    def cancel():
        start.wait(timeout=3)
        return pairing.cancel(tx["pairing_id"])

    def redeem():
        start.wait(timeout=3)
        try:
            return pairing.redeem(
                pairing_id=tx["pairing_id"], device_id="android-device",
                challenge_id=request["challenge_id"], signature=signature)
        except PairingV2Error:
            return None

    with ThreadPoolExecutor(max_workers=2) as pool:
        cancel_future = pool.submit(cancel)
        redeem_future = pool.submit(redeem)
        cancelled = cancel_future.result(timeout=5)
        token = redeem_future.result(timeout=5)

    # The transaction lock makes cancellation and redemption mutually exclusive:
    # either cancellation wins and no credential exists, or redemption wins and
    # the cancellation sees an already-consumed transaction.
    assert cancelled is (token is None)
    assert pairing.pending_requests() == []
    if token is None:
        assert registry.list_devices() == []
    else:
        identity = registry.verify(device_id="android-device", credential=token)
        assert identity is not None




def test_pairing_v2_rejects_scope_escalation_and_invalid_client_nonce(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    tx = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="requested scopes exceed"):
        pairing.request(
            pairing_id=tx["pairing_id"], device_id="android-device",
            public_key=public, requested_scopes=["status", "chat.send"],
            client_nonce=_b64(b"n" * 32),
        )
    with pytest.raises(PairingV2Error, match="client nonce"):
        pairing.request(
            pairing_id=tx["pairing_id"], device_id="android-device",
            public_key=public, requested_scopes=["status"],
            client_nonce=_b64(b"short"),
        )
    assert pairing.pending_requests() == []



def test_pairing_v2_rejects_reused_client_nonce_across_transactions(tmp_path):
    pairing, _, _, _, public = _setup(tmp_path)
    nonce = _b64(b"unique-client-nonce-32-bytes!!")
    first = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    pairing.request(
        pairing_id=first["pairing_id"], device_id="android-one",
        public_key=public, requested_scopes=["status"], client_nonce=nonce,
    )
    second = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="nonce replay"):
        pairing.request(
            pairing_id=second["pairing_id"], device_id="android-two",
            public_key=public, requested_scopes=["status"], client_nonce=nonce,
        )



def test_pairing_v2_nonce_cache_pressure_fails_closed_without_eviction(tmp_path):
    pairing, _, clock, _, public = _setup(tmp_path)
    pairing._seen_client_nonces.update({
        _b64(index.to_bytes(4, "big") + b"x" * 28): clock.now
        for index in range(4096)
    })
    original = set(pairing._seen_client_nonces)
    tx = pairing.begin(workspace_id="workspace", cwd="/safe/work", scopes={"status"})
    with pytest.raises(PairingV2Error, match="replay cache is full"):
        pairing.request(
            pairing_id=tx["pairing_id"], device_id="android-device",
            public_key=public, requested_scopes=["status"],
            client_nonce=_b64(b"fresh-client-nonce-32-bytes-long!"),
        )
    assert set(pairing._seen_client_nonces) == original
    assert pairing.pending_requests() == []
