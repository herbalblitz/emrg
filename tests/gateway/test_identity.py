"""Pairing codes and device tokens.

The properties pinned here are the ones a lost phone depends on: a code is
one-shot, guessing it five times destroys it, an expired code is refused with
a distinct code, the device file carries no usable token, and revoking a device
removes its hash.
"""

from __future__ import annotations

import json

import pytest

from emrg.gateway.identity import (
    DEFAULT_SCOPES,
    DeviceStore,
    PairingError,
    _token_hash,
)


def _store(tmp_path, *, clock=None, max_devices=8):
    kwargs = {"max_devices": max_devices}
    if clock is not None:
        kwargs["clock"] = clock
    return DeviceStore(tmp_path / "devices.json", tmp_path / "pairing.json", **kwargs)


def test_a_pairing_code_creates_one_device_and_the_token_is_not_stored(tmp_path):
    store = _store(tmp_path)
    issued = store.issue_pairing_code()
    device, token = store.pair(issued["code"], "Pixel 9")
    assert device.name == "Pixel 9"
    assert device.scopes == list(DEFAULT_SCOPES)
    assert token.startswith("emrgd_")
    payload = json.loads((tmp_path / "devices.json").read_text(encoding="utf-8"))
    stored = json.dumps(payload)
    assert token not in stored, "the plaintext token must never reach devices.json"
    assert payload["devices"][0]["token_hash"] == _token_hash(token)
    assert store.authenticate(token).id == device.id
    assert store.authenticate(token + "x") is None


def test_a_pairing_code_is_one_shot(tmp_path):
    store = _store(tmp_path)
    issued = store.issue_pairing_code()
    store.pair(issued["code"], "first")
    with pytest.raises(PairingError) as raised:
        store.pair(issued["code"], "second")
    assert raised.value.code == "no_pairing_code"
    assert len(store.devices()) == 1


def test_five_wrong_codes_destroy_the_code(tmp_path):
    store = _store(tmp_path)
    issued = store.issue_pairing_code()
    for _ in range(4):
        with pytest.raises(PairingError) as raised:
            store.pair("00000000" if issued["code"] != "00000000" else "11111111", "phone")
        assert raised.value.code == "pairing_rejected"
    with pytest.raises(PairingError) as raised:
        store.pair("99999999", "phone")
    assert raised.value.code == "pairing_locked"
    with pytest.raises(PairingError) as raised:
        store.pair(issued["code"], "phone")
    assert raised.value.code == "no_pairing_code"


def test_an_expired_code_is_refused_and_removed(tmp_path):
    now = [1_000.0]
    store = _store(tmp_path, clock=lambda: now[0])
    issued = store.issue_pairing_code(ttl_seconds=30)
    now[0] += 31
    with pytest.raises(PairingError) as raised:
        store.pair(issued["code"], "phone")
    assert raised.value.code == "pairing_expired"
    assert store.pairing_code_state() is None


def test_the_device_limit_is_enforced_before_a_token_is_minted(tmp_path):
    store = _store(tmp_path, max_devices=1)
    one = store.issue_pairing_code()
    store.pair(one["code"], "one")
    two = store.issue_pairing_code()
    with pytest.raises(PairingError) as raised:
        store.pair(two["code"], "two")
    assert raised.value.code == "device_limit"
    assert len(store.devices()) == 1


def test_revoking_removes_the_token_and_frees_the_slot(tmp_path):
    store = _store(tmp_path, max_devices=1)
    issued = store.issue_pairing_code()
    device, token = store.pair(issued["code"], "phone")
    assert store.revoke(device.id) is True
    assert store.authenticate(token) is None
    assert store.revoke(device.id) is False
    again = store.issue_pairing_code()
    store.pair(again["code"], "replacement")
    assert len(store.devices()) == 1


def test_an_unknown_scope_cannot_be_granted(tmp_path):
    store = _store(tmp_path)
    issued = store.issue_pairing_code()
    device, _ = store.pair(issued["code"], "phone", scopes=["chat", "root-everything"])
    assert device.scopes == ["chat"], "only a known scope is stored"
