import json
import pytest
from emrg.server.companion_protocol import CompanionProtocolError, MAX_FRAME_BYTES, MAX_PROMPT_BYTES, encode_server_frame, validate_client_frame

def _auth(**overrides):
    frame={"type":"auth","protocol":"1.0","device_id":"device-1","credential":"secret","client_nonce":"nonce"}
    frame.update(overrides)
    return json.dumps(frame)

def test_pre_auth_accepts_auth_envelope_only():
    assert validate_client_frame(_auth())["device_id"]=="device-1"

@pytest.mark.parametrize("frame", ['{"type":"status","request_id":"x"}','{"type":"auth","protocol":"9.0","device_id":"d","credential":"c","client_nonce":"n"}','{"type":"auth","protocol":"1.0","device_id":"","credential":"c","client_nonce":"n"}','[]','not json'])
def test_pre_auth_rejects_invalid_envelopes(frame):
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame)

def test_status_requires_request_id_after_auth():
    assert validate_client_frame('{"type":"status","request_id":"r"}',authenticated=True)["type"]=="status"
    with pytest.raises(CompanionProtocolError): validate_client_frame('{"type":"status"}',authenticated=True)

def test_session_list_is_scope_and_workspace_bound():
    frame='{"type":"sessions.list","request_id":"r","workspace_id":"w"}'
    assert validate_client_frame(frame,authenticated=True,scopes=frozenset({"sessions.list"}),workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,workspace_id="w")
    with pytest.raises(CompanionProtocolError): validate_client_frame(frame,authenticated=True,scopes=frozenset({"sessions.list"}),workspace_id="other")

def test_chat_send_normalizes_and_drops_untrusted_privilege_fields():
    frame={"type":"chat.send","request_id":"r","workspace_id":"w","prompt":"hello","session_id":None,"cwd":"C:/other","tools":["pwsh"],"sandbox":"danger-full-access"}
    result=validate_client_frame(json.dumps(frame),authenticated=True,scopes=frozenset({"chat.send"}),workspace_id="w")
    assert result=={"type":"chat.send","request_id":"r","workspace_id":"w","session_id":None,"prompt":"hello"}
    assert "cwd" not in result and "tools" not in result and "sandbox" not in result

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
