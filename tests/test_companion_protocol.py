import json
import pytest
from emrg.server.companion_protocol import CompanionProtocolError, MAX_FRAME_BYTES, MAX_PROMPT_BYTES, encode_server_frame, validate_client_frame

def _auth(**overrides):
    frame={"type":"auth","protocol":"1.0","device_id":"device-1","credential":"secret","client_nonce":"nonce-value-0001"}
    frame.update(overrides)
    return json.dumps(frame)

def test_pre_auth_accepts_auth_envelope_only():
    assert validate_client_frame(_auth())["device_id"]=="device-1"

@pytest.mark.parametrize("frame", ['{"type":"status","request_id":"x"}','{"type":"auth","protocol":"9.0","device_id":"d","credential":"c","client_nonce":"n"}','{"type":"auth","protocol":"1.0","device_id":"","credential":"c","client_nonce":"n"}','[]','not json'])
def test_pre_auth_rejects_invalid_envelopes(frame):
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame)

def test_pre_auth_rejects_unknown_fields():
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(_auth(extra="ignored"))

def test_duplicate_json_fields_rejected():
    with pytest.raises(CompanionProtocolError):
        validate_client_frame('{"type":"auth","type":"status","protocol":"1.0","device_id":"d","credential":"c","client_nonce":"n"}')

def test_invalid_unicode_is_rejected_without_leaking_encoding_error():
    with pytest.raises(CompanionProtocolError):
        validate_client_frame("\ud800")
    with pytest.raises(CompanionProtocolError):
        validate_client_frame('{"type":"auth","protocol":"1.0","device_id":"\ud800","credential":"c","client_nonce":"n"}')

def test_status_requires_request_id_after_auth():
    assert validate_client_frame('{"type":"status","request_id":"r"}',authenticated=True,scopes=frozenset({"status"}))["type"]=="status"
    with pytest.raises(CompanionProtocolError): validate_client_frame('{"type":"status"}',authenticated=True,scopes=frozenset({"status"}))
    with pytest.raises(CompanionProtocolError): validate_client_frame('{"type":"status","request_id":"r"}',authenticated=True)

def test_status_rejects_unexpected_fields():
    with pytest.raises(CompanionProtocolError):
        validate_client_frame('{"type":"status","request_id":"r","workspace_id":"w"}',authenticated=True,scopes=frozenset({"status"}))

def test_session_list_is_scope_and_workspace_bound():
    frame='{"type":"sessions.list","request_id":"r","workspace_id":"w"}'
    assert validate_client_frame(frame,authenticated=True,scopes=frozenset({"sessions.list"}),workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,scopes=frozenset({"sessions.list"}),workspace_id="other")

def test_chat_send_normalizes_allowed_fields():
    frame={"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hello","session_id":"s-1"}
    result=validate_client_frame(json.dumps(frame),authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")
    assert result=={"type":"chat.send","request_id":"r","workspace_id":"w","session_id":"s-1","prompt":"hello"}

def test_chat_send_requires_session_id():
    # The client always carries a session id (active local id or route
    # remote id); a turn without one has nowhere to stream to.
    for session_id in (None, "", 42):
        frame={"type":"chat.send","request_id":"r","workspace_id":"w",
               "prompt":"hello","session_id":session_id}
        with pytest.raises(CompanionProtocolError):
            validate_client_frame(json.dumps(frame),authenticated=True,
                                  scopes=frozenset({"chat.send"}),workspace_id="w")

@pytest.mark.parametrize("session_id", ["", "x" * 129, "\ud800", 42, []])
def test_chat_rejects_invalid_session_identifiers(session_id):
    frame={"type":"chat.send","request_id":"r","workspace_id":"w",
           "prompt":"hello","session_id":session_id}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame),authenticated=True,
                              scopes=frozenset({"chat.send"}),workspace_id="w")


@pytest.mark.parametrize("field,value", [("cwd","C:/other"),("tools",["pwsh"]),("sandbox","danger-full-access"),("provider","remote")])
def test_chat_rejects_privilege_or_unrecognized_fields(field,value):
    frame={"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hello","session_id":"s-1",field:value}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame),authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")

def test_chat_denies_missing_scope_or_wrong_workspace():
    frame='{"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hi","session_id":"s-1"}'
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="wrong")

def test_prompt_and_frame_size_limits():
    prompt="x"*(MAX_PROMPT_BYTES+1)
    frame=json.dumps({"type":"chat.send","request_id":"r","workspace_id":"w","prompt":prompt})
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(" "*(MAX_FRAME_BYTES+1),authenticated=True)

def test_server_encoder_limits_and_requires_object():
    assert json.loads(encode_server_frame({"type":"ok"}))=={"type":"ok"}
    with pytest.raises(CompanionProtocolError): encode_server_frame([])

def test_pre_auth_rejects_short_client_nonce():
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(_auth(client_nonce="too-short"))



def test_session_list_rejects_client_supplied_path_or_extra_fields():
    base = {"type": "sessions.list", "request_id": "r",
            "workspace_id": "workspace-approved"}
    for extra in ({"cwd": "/other"}, {"path": "/other"}, {"tools": ["read"]}):
        with pytest.raises(CompanionProtocolError):
            validate_client_frame(
                json.dumps({**base, **extra}), authenticated=True,
                scopes=frozenset({"sessions.list"}),
                workspace_id="workspace-approved")


def test_pairing_v2_request_and_redeem_frames_have_strict_shapes():
    import base64
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    enc = lambda size: base64.urlsafe_b64encode(bytes(size)).rstrip(b"=").decode()
    request = {"type": "pairing.request", "protocol": "2.0",
               "pairing_id": enc(32), "device_id": "android-1", "key_algorithm": "P-256",
               "public_key": enc(91), "requested_scopes": ["status"], "client_nonce": enc(32)}
    assert validate_pairing_v2_frame(json.dumps(request)) == request
    redeem = {"type": "pairing.redeem", "protocol": "2.0",
              "pairing_id": enc(32), "device_id": "android-1",
              "challenge_id": enc(16), "signature": enc(70)}
    assert validate_pairing_v2_frame(json.dumps(redeem)) == redeem


@pytest.mark.parametrize("mutate", [
    {"protocol": "1.0"},
    {"extra": "ignored"},
    {"pairing_id": "short"},
    {"public_key": "!" * 43},
    {"device_id": ""},
])
def test_pairing_v2_request_rejects_malformed_or_unknown_fields(mutate):
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    import base64
    frame = {"type": "pairing.request", "protocol": "2.0",
             "pairing_id": base64.urlsafe_b64encode(bytes(32)).rstrip(b"=").decode(),
             "device_id": "android-1",
             "key_algorithm": "P-256", "public_key": base64.urlsafe_b64encode(bytes(91)).rstrip(b"=").decode(), "requested_scopes": ["status"], "client_nonce": base64.urlsafe_b64encode(bytes(32)).rstrip(b"=").decode()}
    frame.update(mutate)
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame(json.dumps(frame))


def test_pairing_v2_redeem_rejects_missing_fields_duplicate_keys_and_wrong_type():
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame('{"type":"pairing.redeem","protocol":"2.0"}')
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame('{"type":"pairing.request","type":"pairing.redeem"}')
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame('{"type":"auth","protocol":"2.0"}')


def test_pairing_v2_rejects_duplicate_keys_after_json_escape_decoding():
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    payload = '{"type":"pairing.request","\\u0074ype":"pairing.redeem","protocol":"2.0"}'
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame(payload)


def test_v2_pairing_frames_remain_rejected_by_v1_pre_auth_gate():
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    import base64
    frame = {"type": "pairing.request", "protocol": "2.0",
             "pairing_id": base64.urlsafe_b64encode(bytes(32)).rstrip(b"=").decode(),
             "device_id": "android-1", "key_algorithm": "P-256",
             "public_key": base64.urlsafe_b64encode(bytes(91)).rstrip(b"=").decode(), "requested_scopes": ["status"], "client_nonce": base64.urlsafe_b64encode(bytes(32)).rstrip(b"=").decode()}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame))
    assert validate_pairing_v2_frame(json.dumps(frame))["type"] == "pairing.request"

@pytest.mark.parametrize("device_id", [
    "android device", "android/forged", "x" * 129,
])
def test_pairing_v2_rejects_noncanonical_device_ids(device_id):
    import base64
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    enc = lambda size: base64.urlsafe_b64encode(bytes(size)).rstrip(b"=").decode()
    frame = {"type": "pairing.request", "protocol": "2.0",
             "pairing_id": enc(32), "device_id": device_id,
             "key_algorithm": "P-256", "public_key": enc(91), "requested_scopes": ["status"], "client_nonce": enc(32)}
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame(json.dumps(frame))


@pytest.mark.parametrize("device_id", ["android-1", "device_2", "host.phone:3"])
def test_pairing_v2_accepts_supported_device_id_characters(device_id):
    import base64
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    enc = lambda size: base64.urlsafe_b64encode(bytes(size)).rstrip(b"=").decode()
    frame = {"type": "pairing.request", "protocol": "2.0",
             "pairing_id": enc(32), "device_id": device_id,
             "key_algorithm": "P-256", "public_key": enc(91), "requested_scopes": ["status"], "client_nonce": enc(32)}
    assert validate_pairing_v2_frame(json.dumps(frame))["device_id"] == device_id


def test_protocol_accepts_utf8_encoded_byte_frames():
    assert validate_client_frame(_auth().encode("utf-8"))["type"] == "auth"


@pytest.mark.parametrize("encoding", ["utf-16", "utf-32"])
def test_protocol_rejects_non_utf8_encoded_byte_frames(encoding):
    from emrg.server.companion_protocol import validate_pairing_v2_frame

    with pytest.raises(CompanionProtocolError):
        validate_client_frame(_auth().encode(encoding))

    import base64
    encode = lambda size: base64.urlsafe_b64encode(bytes(size)).rstrip(b"=").decode()
    pairing_request = {
        "type": "pairing.request", "protocol": "2.0",
        "pairing_id": encode(32), "device_id": "android-1",
        "key_algorithm": "P-256", "public_key": encode(91),
        "requested_scopes": ["status"], "client_nonce": encode(32),
    }
    with pytest.raises(CompanionProtocolError):
        validate_pairing_v2_frame(json.dumps(pairing_request).encode(encoding))

# ── Batch 0 + Batch A: 20-scope registry and new frame shapes ──────────

def test_scope_registry_has_exactly_twenty_scopes():
    from emrg.server.companion_scopes import (
        ALLOWED_SCOPES, MAX_SCOPES, SCOPE_OPERATIONS, scope_for_frame)
    assert len(ALLOWED_SCOPES) == 20
    assert MAX_SCOPES == 20
    assert set(SCOPE_OPERATIONS) == ALLOWED_SCOPES
    assert scope_for_frame("sessions.rename") == "sessions.manage"
    assert scope_for_frame("chat.approve") == "chat.send"
    assert scope_for_frame("tools.monitor") is None  # no request frame
    assert scope_for_frame("nope") is None
    # Batch B: the registry must match the frozen frame -> scope -> daemon
    # op list exactly; a drift here is a scope that gates the wrong frames.
    assert scope_for_frame("memory.list") == "memory.read"
    assert scope_for_frame("memory.read") == "memory.read"
    assert scope_for_frame("models.list") == "models.manage"
    assert scope_for_frame("models.set") == "models.manage"
    assert scope_for_frame("rant.list") == "rant.submit"
    assert scope_for_frame("rant.submit") == "rant.submit"
    assert scope_for_frame("evolution.summary") == "evolution.read"
    for kind in ("tasks.list", "tasks.trigger", "tasks.create", "tasks.update",
                 "tasks.delete", "tasks.templates", "tasks.template_create",
                 "tasks.template_update", "tasks.template_delete"):
        assert scope_for_frame(kind) == "tasks.manage"
    for kind in ("skills.list", "skills.available", "skills.install", "skills.update"):
        assert scope_for_frame(kind) == "skills.manage"
    assert SCOPE_OPERATIONS["memory.read"] == frozenset({"memory.list", "memory.read"})
    assert SCOPE_OPERATIONS["models.manage"] == frozenset({"models.list", "models.set"})
    assert SCOPE_OPERATIONS["rant.submit"] == frozenset({"rant.list", "rant.submit"})
    assert SCOPE_OPERATIONS["evolution.read"] == frozenset({"evolution.summary"})
    assert SCOPE_OPERATIONS["tasks.manage"] == frozenset({
        "tasks.list", "tasks.trigger", "tasks.create", "tasks.update",
        "tasks.delete", "tasks.templates", "tasks.template_create",
        "tasks.template_update", "tasks.template_delete"})
    assert SCOPE_OPERATIONS["skills.manage"] == frozenset({
        "skills.list", "skills.available", "skills.install", "skills.update"})


def test_pairing_v2_accepts_up_to_twenty_scopes_and_rejects_unknown():
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    from emrg.server.companion_scopes import ALLOWED_SCOPES
    import base64
    enc = lambda size: base64.urlsafe_b64encode(bytes(size)).rstrip(b"=").decode()
    base = {"type": "pairing.request", "protocol": "2.0",
            "pairing_id": enc(32), "device_id": "android-1",
            "key_algorithm": "P-256", "public_key": enc(91),
            "requested_scopes": sorted(ALLOWED_SCOPES), "client_nonce": enc(32)}
    assert validate_pairing_v2_frame(json.dumps(base))["requested_scopes"] == sorted(ALLOWED_SCOPES)
    for bad in (["status"] * 21, ["status", "nope"], ["status", "status"],
                sorted(ALLOWED_SCOPES) + ["status"]):
        with pytest.raises(CompanionProtocolError):
            validate_pairing_v2_frame(json.dumps({**base, "requested_scopes": bad}))


def _authed(frame, scope):
    return validate_client_frame(
        json.dumps(frame), authenticated=True,
        scopes=frozenset({scope}), workspace_id="w")


def test_sessions_manage_frames_are_scope_and_workspace_bound():
    good = {"type": "sessions.rename", "request_id": "r", "workspace_id": "w",
            "session_id": "s-1", "title": "New"}
    assert _authed(good, "sessions.manage")["title"] == "New"
    with pytest.raises(CompanionProtocolError):
        _authed(good, "sessions.list")
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(good), authenticated=True,
                              scopes=frozenset({"sessions.manage"}),
                              workspace_id="other")
    for kind, extra in (
            ("sessions.resume", {}), ("sessions.delete", {}),
            ("sessions.clear", {}), ("sessions.compact", {}),
            ("sessions.resolve_cwd", {})):
        frame = {"type": kind, "request_id": "r", "workspace_id": "w",
                 "session_id": "s-1", **extra}
        assert _authed(frame, "sessions.manage")["type"] == kind
    # rewind needs a record index; history defaults its limit server-side.
    assert _authed({"type": "sessions.rewind", "request_id": "r",
                    "workspace_id": "w", "session_id": "s-1", "record_index": 3},
                   "sessions.manage")["record_index"] == 3
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "sessions.rewind", "request_id": "r",
                 "workspace_id": "w", "session_id": "s-1", "record_index": -1},
                "sessions.manage")
    history = _authed({"type": "sessions.history", "request_id": "r",
                       "workspace_id": "w", "session_id": "s-1"},
                      "sessions.manage")
    assert "limit" not in history  # defaulted server-side at dispatch
    assert _authed({"type": "sessions.history", "request_id": "r",
                    "workspace_id": "w", "session_id": "s-1", "limit": 50},
                   "sessions.manage")["limit"] == 50


def test_chat_cancel_and_approve_shapes():
    # chat.cancel: session_id optional (cancels every subscribed turn).
    assert _authed({"type": "chat.cancel", "request_id": "r",
                    "workspace_id": "w"}, "chat.send")["type"] == "chat.cancel"
    assert _authed({"type": "chat.cancel", "request_id": "r",
                    "workspace_id": "w", "session_id": "s-1"},
                   "chat.send")["session_id"] == "s-1"
    # chat.approve: approval_request_id + approved; session_id optional.
    frame = {"type": "chat.approve", "request_id": "r", "workspace_id": "w",
             "approval_request_id": "appr-1", "approved": True,
             "session_id": "s-1"}
    assert _authed(frame, "chat.send")["approval_request_id"] == "appr-1"
    with pytest.raises(CompanionProtocolError):
        _authed({**frame, "approved": "yes"}, "chat.send")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "chat.approve", "request_id": "r",
                 "workspace_id": "w", "approved": True}, "chat.send")


def test_files_and_sandbox_frames_are_scope_bound():
    assert _authed({"type": "files.list", "request_id": "r",
                    "workspace_id": "w", "path": "src"}, "files.read")["path"] == "src"
    assert _authed({"type": "files.read", "request_id": "r",
                    "workspace_id": "w", "path": "a.txt", "start_line": 2,
                    "line_limit": 10}, "files.read")["line_limit"] == 10
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "files.list", "request_id": "r",
                 "workspace_id": "w", "path": "src"}, "files.write")
    assert _authed({"type": "sandbox.set", "request_id": "r",
                    "workspace_id": "w", "session_id": "s-1",
                    "mode": "read-only"}, "sandbox.manage")["mode"] == "read-only"
    assert _authed({"type": "sandbox.roots", "request_id": "r",
                    "workspace_id": "w", "session_id": "s-1", "op": "list"},
                   "sandbox.manage")["op"] == "list"
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "sandbox.roots", "request_id": "r",
                 "workspace_id": "w", "session_id": "s-1", "op": "add"},
                "sandbox.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "sandbox.roots", "request_id": "r",
                 "workspace_id": "w", "session_id": "s-1", "op": "widen"},
                "sandbox.manage")


def test_terminal_and_files_write_frames_are_scope_bound():
    exec_frame = {"type": "terminal.exec", "request_id": "r",
                  "workspace_id": "w", "command": "ls"}
    assert _authed(exec_frame, "terminal.exec")["command"] == "ls"
    with pytest.raises(CompanionProtocolError):
        _authed({**exec_frame, "cwd": "/etc"}, "terminal.exec")
    assert _authed({"type": "terminal.exec_cancel", "request_id": "r",
                    "workspace_id": "w", "exec_id": "abc"}, "terminal.exec")
    assert _authed({"type": "files.write", "request_id": "r",
                    "workspace_id": "w", "path": "a.txt", "content": "hi"},
                   "files.write")["content"] == "hi"
    assert _authed({"type": "files.delete", "request_id": "r",
                    "workspace_id": "w", "path": "a.txt"}, "files.write")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "files.write", "request_id": "r",
                 "workspace_id": "w", "path": "a.txt", "content": "hi"},
                "files.read")


@pytest.mark.parametrize("kind,scope,extra", [
    ("memory.list", "memory.read", {}),
    ("memory.read", "memory.read", {"memory_id": "m-1"}),
    ("models.list", "models.manage", {}),
    ("models.set", "models.manage", {"model": "x"}),
    ("rant.list", "rant.submit", {}),
    ("rant.submit", "rant.submit", {"message": "hi"}),
    ("evolution.summary", "evolution.read", {}),
    ("tasks.list", "tasks.manage", {}),
    ("tasks.trigger", "tasks.manage", {"name": "t"}),
    ("tasks.create", "tasks.manage", {"task": {}}),
    ("tasks.update", "tasks.manage", {"name": "t", "task": {}}),
    ("tasks.delete", "tasks.manage", {"name": "t"}),
    ("tasks.templates", "tasks.manage", {}),
    ("tasks.template_create", "tasks.manage", {"name": "tpl", "template": {}}),
    ("tasks.template_update", "tasks.manage", {"name": "tpl", "template": {}}),
    ("tasks.template_delete", "tasks.manage", {"name": "tpl"}),
    ("skills.list", "skills.manage", {}),
    ("skills.available", "skills.manage", {}),
    ("skills.install", "skills.manage", {"name": "s"}),
    ("skills.update", "skills.manage", {}),
])
def test_batch_b_frames_validate(kind, scope, extra):
    # Batch B: frozen contract shapes + scopes; dispatch landed in Batch B.
    frame = {"type": kind, "request_id": "r", "workspace_id": "w", **extra}
    assert _authed(frame, scope)["type"] == kind
    with pytest.raises(CompanionProtocolError):
        _authed(frame, "status")


@pytest.mark.parametrize("kind,scope,extra", [
    ("hosts.list", "hosts.list", {}),
    ("logs.tail", "logs.read", {}),
    ("audit.tail", "audit.read", {}),
    ("devices.list", "devices.manage", {}),
    ("devices.revoke", "devices.manage", {"device_id": "d-1"}),
    ("settings.get", "settings.manage", {}),
    ("settings.set", "settings.manage", {"patch": {}}),
])
def test_batch_c_frames_validate_but_are_not_dispatched_yet(kind, scope, extra):
    # Frozen contract: shapes + scopes agreed now; dispatch lands later.
    frame = {"type": kind, "request_id": "r", "workspace_id": "w", **extra}
    assert _authed(frame, scope)["type"] == kind
    with pytest.raises(CompanionProtocolError):
        _authed(frame, "status")


def test_batch_b_frames_are_scope_workspace_and_field_bound():
    # Wrong scope rejected for every Batch B scope.
    cases = [
        ({"type": "memory.list", "request_id": "r", "workspace_id": "w"}, "models.manage"),
        ({"type": "models.set", "request_id": "r", "workspace_id": "w", "model": "x"}, "memory.read"),
        ({"type": "rant.submit", "request_id": "r", "workspace_id": "w", "message": "hi"}, "evolution.read"),
        ({"type": "evolution.summary", "request_id": "r", "workspace_id": "w"}, "tasks.manage"),
        ({"type": "tasks.list", "request_id": "r", "workspace_id": "w"}, "skills.manage"),
        ({"type": "skills.install", "request_id": "r", "workspace_id": "w", "name": "s"}, "tasks.manage"),
    ]
    for frame, wrong_scope in cases:
        with pytest.raises(CompanionProtocolError):
            _authed(frame, wrong_scope)
    # Workspace mismatch rejected even with the right scope.
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(
            json.dumps({"type": "tasks.list", "request_id": "r", "workspace_id": "other"}),
            authenticated=True, scopes=frozenset({"tasks.manage"}), workspace_id="w")
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(
            json.dumps({"type": "memory.read", "request_id": "r", "workspace_id": "other",
                        "memory_id": "m-1"}),
            authenticated=True, scopes=frozenset({"memory.read"}), workspace_id="w")
    # Unknown fields rejected — cwd must never come from the client, and no
    # frame may smuggle an extra field past the allowlist.
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "memory.list", "request_id": "r", "workspace_id": "w",
                 "cwd": "/etc"}, "memory.read")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.create", "request_id": "r", "workspace_id": "w",
                 "task": {}, "shutdown": True}, "tasks.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "models.set", "request_id": "r", "workspace_id": "w",
                 "model": "x", "api_key": "secret"}, "models.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "skills.install", "request_id": "r", "workspace_id": "w",
                 "name": "s", "force": True}, "skills.manage")


def test_batch_b_frames_require_their_fields():
    # Required fields: missing or wrong-typed values are rejected.
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "memory.read", "request_id": "r", "workspace_id": "w"}, "memory.read")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "models.set", "request_id": "r", "workspace_id": "w"}, "models.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "rant.submit", "request_id": "r", "workspace_id": "w"}, "rant.submit")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.trigger", "request_id": "r", "workspace_id": "w"}, "tasks.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.create", "request_id": "r", "workspace_id": "w"}, "tasks.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.update", "request_id": "r", "workspace_id": "w",
                 "task": {}}, "tasks.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.template_create", "request_id": "r", "workspace_id": "w",
                 "name": "tpl"}, "tasks.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "skills.install", "request_id": "r", "workspace_id": "w"}, "skills.manage")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "tasks.create", "request_id": "r", "workspace_id": "w",
                 "task": "not-a-dict"}, "tasks.manage")
    # evolution.summary limit is bounded 1..20, matching the daemon clamp.
    assert _authed({"type": "evolution.summary", "request_id": "r",
                    "workspace_id": "w", "limit": 20}, "evolution.read")["limit"] == 20
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "evolution.summary", "request_id": "r",
                 "workspace_id": "w", "limit": 0}, "evolution.read")
    with pytest.raises(CompanionProtocolError):
        _authed({"type": "evolution.summary", "request_id": "r",
                 "workspace_id": "w", "limit": 21}, "evolution.read")


@pytest.mark.parametrize("kind", [
    "shutdown", "init_auto_evolve",
    "github_connect", "github_connect_web", "github_disconnect", "github_status",
])
def test_host_lifecycle_and_github_frames_are_never_forwarded(kind):
    # No scope grants these: the validator rejects them outright, so the
    # listener can never dispatch them however a client asks.
    frame = {"type": kind, "request_id": "r", "workspace_id": "w"}
    for scopes in ({"status"}, {"tasks.manage"}, {"settings.manage"}, set()):
        with pytest.raises(CompanionProtocolError):
            validate_client_frame(json.dumps(frame), authenticated=True,
                                  scopes=frozenset(scopes), workspace_id="w")
