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
    frame={"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hello","session_id":None}
    result=validate_client_frame(json.dumps(frame),authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")
    assert result=={"type":"chat.send","request_id":"r","workspace_id":"w","session_id":None,"prompt":"hello"}

@pytest.mark.parametrize("session_id", ["", "x" * 129, "\ud800", 42, []])
def test_chat_rejects_invalid_session_identifiers(session_id):
    frame={"type":"chat.send","request_id":"r","workspace_id":"w",
           "prompt":"hello","session_id":session_id}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame),authenticated=True,
                              scopes=frozenset({"chat.send"}),workspace_id="w")


@pytest.mark.parametrize("field,value", [("cwd","C:/other"),("tools",["pwsh"]),("sandbox","danger-full-access"),("provider","remote")])
def test_chat_rejects_privilege_or_unrecognized_fields(field,value):
    frame={"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hello","session_id":None,field:value}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame),authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")

def test_chat_denies_missing_scope_or_wrong_workspace():
    frame='{"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hi"}'
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
               "pairing_id": enc(32), "device_id": "android-1",
               "public_key": enc(32)}
    assert validate_pairing_v2_frame(json.dumps(request)) == request
    redeem = {"type": "pairing.redeem", "protocol": "2.0",
              "pairing_id": enc(32), "device_id": "android-1",
              "challenge_id": enc(16), "signature": enc(64)}
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
             "public_key": base64.urlsafe_b64encode(bytes(32)).rstrip(b"=").decode()}
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


def test_v2_pairing_frames_remain_rejected_by_v1_pre_auth_gate():
    from emrg.server.companion_protocol import validate_pairing_v2_frame
    frame = {"type": "pairing.request", "protocol": "2.0",
             "pairing_id": "A" * 43, "device_id": "android-1",
             "public_key": "B" * 43}
    with pytest.raises(CompanionProtocolError):
        validate_client_frame(json.dumps(frame))
    assert validate_pairing_v2_frame(json.dumps(frame))["type"] == "pairing.request"
