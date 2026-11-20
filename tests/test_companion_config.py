"""Tests for companion settings get/set (port of gateway config_api)."""
import json
import logging
import os

import pytest

from emrg.server.companion_config import (
    EDITABLE_KEYS,
    CompanionConfigError,
    apply_companion_patch,
    apply_patch,
    snapshot,
)
from emrg.server.companion_exec import CompanionExecConfig
from emrg.server.companion_listener_policy import CompanionListenerConfig

_CONFIG_TEXT = """# host config — comments must survive a phone edit
[llm]
base_url = "https://api.deepseek.com"
api_key = "sk-testkey1234567890abcdef"
model = "deepseek-chat"
max_tokens = 8192
temperature = 0.7

[update]
enabled = true
delay_minutes = 1440

[client]
theme = "dark"
"""


def _write_config(tmp_path, text=_CONFIG_TEXT):
    path = tmp_path / "config.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_snapshot_reports_presence_never_the_key(tmp_path):
    path = _write_config(tmp_path)
    result = snapshot(config_file=path)
    assert result["config_present"] is True
    engine = result["engine"]
    assert engine["model"] == "deepseek-chat"
    assert engine["base_url"] == "https://api.deepseek.com"
    assert engine["max_tokens"] == 8192
    assert engine["api_key_set"] is True
    assert engine["api_key_hint"] == "…cdef"
    assert engine["update"] == {"enabled": True, "delay_minutes": 1440}
    assert engine["client"] == {"theme": "dark"}
    assert engine["models"] == []
    blob = json.dumps(result)
    assert "sk-testkey1234567890abcdef" not in blob
    # No absolute host paths leave the host (unlike the gateway snapshot).
    assert str(path) not in blob
    assert result["editable"] == sorted(k for k in EDITABLE_KEYS if not k.startswith("companion."))
    assert result["editable_companion"] == sorted(
        k for k in EDITABLE_KEYS if k.startswith("companion."))


def test_snapshot_missing_config_reports_defaults(tmp_path):
    result = snapshot(config_file=tmp_path / "nope.toml")
    assert result["config_present"] is False
    assert result["engine"]["api_key_set"] is False
    assert result["engine"]["api_key_hint"] == ""
    assert result["engine"]["model"] == "gpt-4o-mini"


def test_snapshot_unparseable_config_is_an_error(tmp_path):
    path = _write_config(tmp_path, "[llm\nbroken = ")
    with pytest.raises(CompanionConfigError) as exc:
        snapshot(config_file=path)
    assert exc.value.code == "config_unavailable"


def test_snapshot_placeholder_key_counts_as_unset(tmp_path):
    path = _write_config(tmp_path, _CONFIG_TEXT.replace(
        "sk-testkey1234567890abcdef", "sk-..."))
    result = snapshot(config_file=path)
    assert result["engine"]["api_key_set"] is False
    assert result["engine"]["api_key_hint"] == ""


def test_apply_patch_updates_engine_values_and_preserves_comments(tmp_path):
    path = _write_config(tmp_path)
    result = apply_patch({"llm.model": "gpt-4o", "llm.max_tokens": 4096,
                          "update.delay_minutes": 60},
                         device_id="phone-1", config_file=path)
    assert result["changed"] == {"llm.model": "gpt-4o", "llm.max_tokens": 4096,
                                 "update.delay_minutes": 60}
    assert result["companion_changed"] == {}
    assert result["reloadable"] == ["llm.max_tokens", "update.delay_minutes"]
    assert "llm.model" in result["hints"]
    assert result["restart_required"] == []
    assert result["backup_created"] is True
    text = path.read_text(encoding="utf-8")
    assert "# host config — comments must survive a phone edit" in text
    assert 'model = "gpt-4o"' in text
    assert "max_tokens = 4096" in text
    assert "delay_minutes = 60" in text
    # Untouched lines survive byte-for-byte, including the key itself.
    assert 'api_key = "sk-testkey1234567890abcdef"' in text
    assert 'base_url = "https://api.deepseek.com"' in text
    assert 'theme = "dark"' in text
    # The backup holds the previous revision.
    backups = sorted(path.parent.glob("config.toml.companion-bak-*"))
    assert len(backups) == 1
    assert "deepseek-chat" in backups[0].read_text(encoding="utf-8")


def test_apply_patch_inserts_missing_key_and_section(tmp_path):
    path = _write_config(tmp_path, "[llm]\nmodel = \"m\"\n")
    apply_patch({"llm.temperature": 0.2, "client.theme": "light"},
                device_id="phone-1", config_file=path)
    text = path.read_text(encoding="utf-8")
    assert "temperature = 0.2" in text
    assert "[client]" in text
    assert 'theme = "light"' in text


def test_apply_patch_creates_config_when_missing(tmp_path):
    path = tmp_path / "config.toml"
    result = apply_patch({"llm.model": "m-x"}, device_id="phone-1", config_file=path)
    assert result["changed"] == {"llm.model": "m-x"}
    assert result["backup_created"] is False
    assert 'model = "m-x"' in path.read_text(encoding="utf-8")


def test_apply_patch_rejects_unknown_keys_with_generic_error(tmp_path):
    path = _write_config(tmp_path)
    before = path.read_text(encoding="utf-8")
    for key in ("llm.api_key", "allow_danger_exec", "gateway.allow_danger_exec",
                "max_devices", "gateway.max_devices", "host", "port",
                "public_host", "llm.models", "sandbox.pwsh_path", "evil"):
        with pytest.raises(CompanionConfigError) as exc:
            apply_patch({key: "x"}, device_id="phone-1", config_file=path)
        assert exc.value.code == "invalid_patch"
        # Generic by design: the reply names neither the key nor the allowlist.
        assert str(exc.value) == "settings key not allowed"
    assert path.read_text(encoding="utf-8") == before


def test_apply_patch_rejects_danger_mode_for_companion_exec(tmp_path):
    path = _write_config(tmp_path)
    with pytest.raises(CompanionConfigError) as exc:
        apply_patch({"companion.default_exec_mode": "danger-full-access"},
                    device_id="phone-1", config_file=path)
    assert exc.value.code == "invalid_patch"
    assert path.read_text(encoding="utf-8") == _CONFIG_TEXT


def test_apply_patch_validates_values_and_writes_nothing_on_failure(tmp_path):
    path = _write_config(tmp_path)
    bad = [
        {"llm.max_tokens": "many"},
        {"llm.max_tokens": 0},
        {"llm.max_tokens": 2_000_001},
        {"llm.temperature": 2.5},
        {"llm.temperature": "hot"},
        {"llm.max_tool_rounds": True},
        {"client.theme": "blue"},
        {"llm.base_url": "   "},
        {"llm.base_url": "x" * 3000},
        {"update.delay_minutes": -1},
        {"companion.command_timeout": 0.5},
        {"companion.idle_timeout": 301},
    ]
    for patch in bad:
        with pytest.raises(CompanionConfigError) as exc:
            apply_patch(patch, device_id="phone-1", config_file=path)
        assert exc.value.code == "invalid_patch"
    assert path.read_text(encoding="utf-8") == _CONFIG_TEXT
    with pytest.raises(CompanionConfigError):
        apply_patch({}, device_id="phone-1", config_file=path)
    with pytest.raises(CompanionConfigError):
        apply_patch({"llm.model": "m", "llm.api_key": "sk-x"},
                    device_id="phone-1", config_file=path)
    # One bad key rejects the whole patch, including the good keys in it.
    assert path.read_text(encoding="utf-8") == _CONFIG_TEXT


def test_apply_patch_refuses_unparseable_config(tmp_path):
    path = _write_config(tmp_path, "[llm\nbroken = ")
    with pytest.raises(CompanionConfigError) as exc:
        apply_patch({"llm.model": "m"}, device_id="phone-1", config_file=path)
    assert exc.value.code == "invalid_patch"
    assert path.read_text(encoding="utf-8") == "[llm\nbroken = "


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="this platform has no FIFOs")
def test_apply_patch_refuses_fifo_config_without_opening_it(tmp_path):
    # A FIFO at the config path must be refused, not opened (opening it
    # would block). If the guard regresses, this test hangs — run with a
    # timeout.
    path = tmp_path / "config.toml"
    os.mkfifo(path)
    with pytest.raises(CompanionConfigError) as exc:
        apply_patch({"llm.model": "m"}, device_id="phone-1", config_file=path)
    assert exc.value.code == "config_unavailable"


def test_apply_patch_audit_logs_key_names_never_values(tmp_path, caplog):
    path = _write_config(tmp_path)
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_config"):
        apply_patch({"llm.model": "super-secret-model-name", "llm.max_tokens": 11},
                    device_id="phone-1", config_file=path)
    assert "companion settings allowed" in caplog.text
    assert "settings.set" in caplog.text
    assert "llm.model" in caplog.text and "llm.max_tokens" in caplog.text
    assert "super-secret-model-name" not in caplog.text
    assert "phone-1" in caplog.text
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="emrg.server.companion_config"):
        with pytest.raises(CompanionConfigError):
            apply_patch({"llm.api_key": "sk-x"}, device_id="phone-1", config_file=path)
    assert "companion settings denied" in caplog.text


def test_companion_patch_applies_to_live_listener_configs(tmp_path):
    exec_config = CompanionExecConfig()
    listener_config = CompanionListenerConfig.build("127.0.0.1", 8765)
    new_exec, new_listener = apply_companion_patch(
        exec_config, listener_config,
        {"companion.default_exec_mode": "read-only",
         "companion.command_timeout": 30.0,
         "companion.idle_timeout": 120.0})
    assert new_exec.default_exec_mode == "read-only"
    assert new_exec.command_timeout == 30.0
    assert new_listener.idle_timeout_seconds == 120.0
    # The originals are untouched (frozen dataclasses, replace semantics).
    assert exec_config.default_exec_mode == "workspace-write"
    assert listener_config.idle_timeout_seconds == 60
    # Unknown companion keys are rejected, not silently dropped.
    with pytest.raises(CompanionConfigError):
        apply_companion_patch(exec_config, listener_config,
                              {"companion.allow_danger_exec": True})
    with pytest.raises(CompanionConfigError):
        apply_companion_patch(exec_config, listener_config,
                              {"companion.default_exec_mode": "danger-full-access"})


def test_apply_patch_with_companion_keys_returns_them_for_the_listener(tmp_path):
    path = _write_config(tmp_path)
    result = apply_patch({"companion.command_timeout": 45.0},
                         device_id="phone-1", config_file=path)
    assert result["changed"] == {"companion.command_timeout": 45.0}
    assert result["companion_changed"] == {"companion.command_timeout": 45.0}
    # A companion-only patch does not touch config.toml at all.
    assert path.read_text(encoding="utf-8") == _CONFIG_TEXT
    assert result["backup_created"] is False


def test_editable_keys_exclude_the_danger_and_secret_keys():
    assert "llm.api_key" not in EDITABLE_KEYS
    assert "allow_danger_exec" not in EDITABLE_KEYS
    assert "gateway.allow_danger_exec" not in EDITABLE_KEYS
    assert "max_devices" not in EDITABLE_KEYS
    assert "host" not in EDITABLE_KEYS and "port" not in EDITABLE_KEYS
    assert "companion.default_exec_mode" in EDITABLE_KEYS
    assert "llm.model" in EDITABLE_KEYS and "llm.max_tokens" in EDITABLE_KEYS
    assert "llm.base_url" in EDITABLE_KEYS
