import json
from pathlib import Path

import pytest

from emrg.server.companion_credentials import CredentialRegistry, CredentialRegistryError


def test_issue_verify_and_metadata_is_workspace_bound(tmp_path: Path):
    registry = CredentialRegistry(tmp_path / "credentials.json")
    token = registry.issue(device_id="phone", workspace_id="ws-a", cwd="/work/a",
                           scopes={"chat.send", "sessions.list"})
    verified = registry.verify(device_id="phone", credential=token)
    assert verified == {
        "device_id": "phone", "workspace_id": "ws-a", "cwd": "/work/a",
        "scopes": frozenset({"chat.send", "sessions.list"}),
    }
    assert registry.verify(device_id="phone", credential="wrong") is None
    raw = json.loads((tmp_path / "credentials.json").read_text())
    assert token not in (tmp_path / "credentials.json").read_text()
    assert "verifier" in raw["devices"]["phone"]


def test_reissue_rotates_token_and_updates_scope(tmp_path: Path):
    registry = CredentialRegistry(tmp_path / "credentials.json")
    first = registry.issue(device_id="phone", workspace_id="ws", cwd="/work",
                           scopes={"chat.send"})
    second = registry.issue(device_id="phone", workspace_id="ws2", cwd="/other",
                            scopes={"status"})
    assert first != second
    assert registry.verify(device_id="phone", credential=first) is None
    assert registry.verify(device_id="phone", credential=second)["workspace_id"] == "ws2"


def test_revoke_and_safe_device_listing(tmp_path: Path):
    registry = CredentialRegistry(tmp_path / "credentials.json")
    token = registry.issue(device_id="tablet", workspace_id="ws", cwd="/work",
                           scopes={"status"})
    assert registry.revoke("tablet")
    assert registry.verify(device_id="tablet", credential=token) is None
    listed = registry.list_devices()
    assert listed == [{"device_id": "tablet", "workspace_id": "ws",
                       "scopes": ["status"], "revoked": True}]
    assert "verifier" not in listed[0]


@pytest.mark.parametrize("scopes", [set(), {"admin"}, {"chat.send", "admin"}])
def test_rejects_unsupported_scopes(tmp_path: Path, scopes: set[str]):
    with pytest.raises(CredentialRegistryError):
        CredentialRegistry(tmp_path / "credentials.json").issue(
            device_id="d", workspace_id="w", cwd="/work", scopes=scopes
        )


def test_registry_write_failure_does_not_return_token(tmp_path: Path, monkeypatch):
    registry = CredentialRegistry(tmp_path / "credentials.json")

    def fail_replace(*args):
        raise OSError("simulated")

    monkeypatch.setattr("emrg.server.companion_credentials.os.replace", fail_replace)
    with pytest.raises(CredentialRegistryError):
        registry.issue(device_id="d", workspace_id="w", cwd="/work", scopes={"status"})
    assert not (tmp_path / "credentials.json").exists()
