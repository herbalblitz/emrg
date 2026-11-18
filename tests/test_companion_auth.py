import json
import pytest

from emrg.server.companion_auth import CompanionAuthenticator, CompanionAuthenticationError
from emrg.server.companion_credentials import CredentialRegistry


def _frame(device, token):
    return json.dumps({
        "type": "auth", "protocol": "1.0", "device_id": device,
        "credential": token, "client_nonce": "nonce-value",
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
