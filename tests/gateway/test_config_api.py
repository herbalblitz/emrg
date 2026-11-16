"""The phone's config surface never carries a key and never widens itself.

The engine's config file holds a live API key. These tests pin the two halves
that matter: a read reports presence and a tail only, and a write is a typed
merge that preserves the host's comments, validates before renaming, keeps a
backup, and refuses keys a phone has no business editing.
"""

from __future__ import annotations

import json

import pytest

from emrg.gateway import config_api
from emrg.gateway.config import GatewayConfig, GatewayPaths
from emrg.gateway.protocol import ERR_INVALID_ARGUMENT, GatewayError

TEMPLATE = """# the host's own note
[llm]
base_url = "https://api.deepseek.com"
api_key = "sk-real-secret-1234"
model = "deepseek-chat"
temperature = 0.7

[client]
theme = "auto"
"""


@pytest.fixture
def home(tmp_path, monkeypatch):
    config_dir = tmp_path / ".emrg"
    config_dir.mkdir()
    path = config_dir / "config.toml"
    path.write_text(TEMPLATE, encoding="utf-8")
    monkeypatch.setattr("emrg.gateway.config.config_dir", lambda: config_dir)
    monkeypatch.setattr("emrg.gateway.config_api.config_path", lambda: path)
    monkeypatch.setattr(
        "emrg.gateway.config_api.gateway_paths",
        lambda: GatewayPaths.under(config_dir / "gateway"),
    )
    GatewayPaths.under(config_dir / "gateway").ensure()
    return path


def test_a_read_reports_the_key_by_presence_and_tail_only(home):
    snapshot = config_api.snapshot(GatewayConfig())
    text = json.dumps(snapshot)
    assert "sk-real-secret-1234" not in text
    assert snapshot["engine"]["api_key_set"] is True
    assert snapshot["engine"]["api_key_hint"].endswith("1234")
    assert snapshot["engine"]["base_url"] == "https://api.deepseek.com"


def test_a_patch_preserves_comments_and_changes_only_named_keys(home):
    result = config_api.apply_patch(
        {"llm.temperature": 0.2, "llm.api_key": "sk-new-secret-5678", "client.theme": "dark"},
        device_id="d1",
    )
    assert result["changed"]["llm.api_key"] == "***"
    text = home.read_text(encoding="utf-8")
    assert "# the host's own note" in text
    assert "sk-new-secret-5678" in text
    assert "sk-real-secret-1234" not in text
    assert 'temperature = 0.2' in text
    assert 'theme = "dark"' in text
    assert (home.stat().st_mode & 0o777) == 0o600
    assert result["backup"]
    backups = list((home.parent / "gateway" / "backups").glob("config-*.toml"))
    assert backups and "sk-real-secret-1234" in backups[0].read_text(encoding="utf-8")


def test_an_unknown_key_is_refused_before_the_file_is_touched(home):
    before = home.read_bytes()
    with pytest.raises(GatewayError) as raised:
        config_api.apply_patch({"llm.crazy_knob": True}, device_id="d1")
    assert raised.value.code == ERR_INVALID_ARGUMENT
    assert home.read_bytes() == before


def test_a_wrong_type_is_refused_before_the_file_is_touched(home):
    before = home.read_bytes()
    with pytest.raises(GatewayError):
        config_api.apply_patch({"llm.max_tokens": "lots"}, device_id="d1")
    with pytest.raises(GatewayError):
        config_api.apply_patch({"llm.temperature": 9.0}, device_id="d1")
    with pytest.raises(GatewayError):
        config_api.apply_patch({"client.theme": "midnight"}, device_id="d1")
    assert home.read_bytes() == before


def test_gateway_options_are_persisted_and_report_restart(home):
    result = config_api.apply_gateway_patch({"allow_danger_exec": True, "idle_timeout": 60})
    assert result["changed"]["allow_danger_exec"] is True
    saved = json.loads((home.parent / "gateway" / "config.json").read_text(encoding="utf-8"))
    assert saved["allow_danger_exec"] is True
    assert saved["idle_timeout"] == 60
    host = config_api.apply_gateway_patch({"host": "127.0.0.1"})
    assert host["restart_required"] == ["host"]
