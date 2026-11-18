import json
from concurrent.futures import ThreadPoolExecutor
import pytest

from emrg.server.companion_auth import CompanionAuthenticator, CompanionAuthenticationError
from emrg.server.companion_credentials import CredentialRegistry


def _frame(device, token, nonce="nonce-value-0001"):
    return json.dumps({
        "type": "auth", "protocol": "1.0", "device_id": device,
        "credential": token, "client_nonce": nonce,
    })


def test_authenticate_returns_only_authorized_binding(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status", "chat.send"})
    result = CompanionAuthenticator(registry).authenticate(_frame("phone", token))
    assert result == {
        "device_id": "phone", "workspace_id": "workspace-a",
        "cwd": "/approved/path", "scopes": frozenset({"status", "chat.send"}),
    }
    assert "credential" not in result and "verifier" not in result


def test_authenticate_rejects_wrong_token_and_revoked_device(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status"})
    auth = CompanionAuthenticator(registry)
    with pytest.raises(CompanionAuthenticationError):
        auth.authenticate(_frame("phone", "wrong-token"))
    assert registry.revoke("phone")
    with pytest.raises(CompanionAuthenticationError):
        auth.authenticate(_frame("phone", token))


def test_authenticate_rejects_malformed_or_unsupported_envelope(tmp_path):
    auth = CompanionAuthenticator(CredentialRegistry(tmp_path / "registry.json"))
    for frame in ("[]", '{"type":"status"}', '{"type":"auth","protocol":"2","device_id":"x","credential":"y","client_nonce":"z"}'):
        with pytest.raises(CompanionAuthenticationError):
            auth.authenticate(frame)


def test_authenticate_fails_closed_when_registry_unavailable(tmp_path):
    path = tmp_path / "registry.json"
    path.write_text("broken", encoding="utf-8")
    auth = CompanionAuthenticator(CredentialRegistry(path))
    with pytest.raises(CompanionAuthenticationError):
        auth.authenticate(_frame("phone", "some-token"))

def test_authenticate_rejects_reused_nonce(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status"})
    auth = CompanionAuthenticator(registry)
    assert auth.authenticate(_frame("phone", token, "nonce-replay-test-0001"))["device_id"] == "phone"
    with pytest.raises(CompanionAuthenticationError):
        auth.authenticate(_frame("phone", token, "nonce-replay-test-0001"))


def test_nonce_cache_expires_and_allows_fresh_nonce_after_ttl(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status"})
    now = [100.0]
    auth = CompanionAuthenticator(registry, nonce_ttl_seconds=5, clock=lambda: now[0])
    assert auth.authenticate(_frame("phone", token, "nonce-expiry-test-0001"))
    now[0] += 6
    assert auth.authenticate(_frame("phone", token, "nonce-expiry-test-0001"))


def test_nonce_cache_fails_closed_when_capacity_reached(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status"})
    auth = CompanionAuthenticator(registry, max_nonce_entries=1)
    assert auth.authenticate(_frame("phone", token, "nonce-cache-test-0001"))
    with pytest.raises(CompanionAuthenticationError):
        auth.authenticate(_frame("phone", token, "nonce-cache-test-0002"))


def test_concurrent_duplicate_auth_nonce_only_succeeds_once(tmp_path):
    registry = CredentialRegistry(tmp_path / "registry.json")
    token = registry.issue(device_id="phone", workspace_id="workspace-a",
                           cwd="/approved/path", scopes={"status"})
    auth = CompanionAuthenticator(registry)
    frame = _frame("phone", token, "nonce-concurrent-test-0001")
    def attempt():
        try:
            auth.authenticate(frame)
            return True
        except CompanionAuthenticationError:
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: attempt(), range(8)))
    assert sum(results) == 1
