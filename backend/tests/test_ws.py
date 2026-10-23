import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from tests.conftest import REMINDER, SEND
from zibi.config import Settings
from zibi.llm.base import LlmResponse, ToolCallRequest
from zibi.llm.fake import FakeLlm
from zibi.server import create_app

HELLO = {
    "type": "hello",
    "payload": {
        "device_id": "pixel-test",
        "timezone": "Asia/Kolkata",
        "tools": [REMINDER.model_dump(mode="json"), SEND.model_dump(mode="json")],
        "context": {"timetable": [{"day": "Friday", "start": "10:00", "end": "12:00", "title": "Data Science"}]},
    },
}


def test_full_roundtrip_with_device_tool():
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(id="c1", name="create_reminder", arguments={"title": "Clip nails"})]),
        LlmResponse(content="Reminder set."),
    ])
    app = create_app(llm=llm, settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(HELLO)
        ack = ws.receive_json()
        assert ack["type"] == "hello.ack" and "create_reminder" in ack["payload"]["tools"]

        ws.send_json({"type": "input.text", "payload": {"text": "remind me to clip my nails"}})
        tool_call = ws.receive_json()
        assert tool_call["type"] == "tool.call"
        assert tool_call["payload"]["tool"] == "create_reminder"

        ws.send_json({"type": "tool.result", "reply_to": tool_call["id"], "payload": {"ok": True, "result": {"reminder_id": 7}}})
        card = ws.receive_json()
        assert card["type"] == "response.card" and card["payload"] == {"card": "message", "text": "Reminder set."}

    # the timetable from hello reached the model's system prompt
    assert "Data Science" in llm.calls[0]["messages"][0]["content"]


def test_high_stakes_goes_through_confirm_card():
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(id="c1", name="send_message", arguments={"to": "Sam", "text": "hi"})]),
        LlmResponse(content="Okay, not sending."),
    ])
    app = create_app(llm=llm, settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(HELLO)
        ws.receive_json()
        ws.send_json({"type": "input.text", "payload": {"text": "text sam hi"}})
        card = ws.receive_json()
        assert card["payload"]["card"] == "confirm"
        ws.send_json({"type": "ui.action", "reply_to": card["id"], "payload": {"action_id": "deny"}})
        final = ws.receive_json()  # no tool.call arrives: the declined action never reached the phone
        assert final["type"] == "response.card" and final["payload"]["card"] == "message"


def test_first_message_must_be_hello():
    app = create_app(llm=FakeLlm([]), settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "input.text", "payload": {"text": "hi"}})
        assert ws.receive_json()["payload"]["code"] == "expected_hello"


def test_token_is_enforced():
    app = create_app(llm=FakeLlm([]), settings=Settings(dev_token="secret"))
    with TestClient(app) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws?token=wrong"):
                pass
        with client.websocket_connect("/ws?token=secret") as ws:
            ws.send_json(HELLO)
            assert ws.receive_json()["type"] == "hello.ack"


def test_bad_device_tool_schema_is_rejected_not_fatal():
    app = create_app(llm=FakeLlm([]), settings=Settings(dev_token=None))
    bad = {"name": "broken", "description": "x", "parameters": {"type": "nonsense"}}
    hello = {"type": "hello", "payload": {"device_id": "d", "tools": [bad]}}
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(hello)
        assert ws.receive_json()["payload"]["rejected_tools"] == ["broken"]


ASK_CALL = LlmResponse(tool_calls=[ToolCallRequest(
    id="c1", name="ask_user",
    arguments={"question": "Add to calendar too?", "options": ["Add to calendar", "No thanks"]})])


def test_ask_card_tap_answers_the_model():
    llm = FakeLlm([ASK_CALL, LlmResponse(content="Added.")])
    app = create_app(llm=llm, settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(HELLO); ws.receive_json()
        ws.send_json({"type": "input.text", "payload": {"text": "lab notebook due 1pm"}})
        card = ws.receive_json()
        assert card["payload"]["card"] == "ask"
        assert [o["label"] for o in card["payload"]["options"]] == ["Add to calendar", "No thanks"]
        ws.send_json({"type": "ui.action", "reply_to": card["id"], "payload": {"action_id": "Add to calendar"}})
        assert ws.receive_json()["payload"]["text"] == "Added."
    tool_msg = [m for m in llm.calls[1]["messages"] if m["role"] == "tool"][0]
    assert "Add to calendar" in tool_msg["content"]


def test_typing_instead_of_tapping_answers_the_ask():
    llm = FakeLlm([ASK_CALL, LlmResponse(content="Got it.")])
    app = create_app(llm=llm, settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(HELLO); ws.receive_json()
        ws.send_json({"type": "input.text", "payload": {"text": "lab notebook due 1pm"}})
        assert ws.receive_json()["payload"]["card"] == "ask"
        ws.send_json({"type": "input.text", "payload": {"text": "yes but remind me 30 min before"}})
        assert ws.receive_json()["payload"]["text"] == "Got it."  # no second turn was started
    tool_msg = [m for m in llm.calls[1]["messages"] if m["role"] == "tool"][0]
    assert "remind me 30 min before" in tool_msg["content"]


def test_typing_while_a_confirm_is_pending_declines_it_and_starts_a_new_turn():
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(id="c1", name="send_message", arguments={"to": "Sam", "text": "hi"})]),
        LlmResponse(content="Okay, not sending."),
        LlmResponse(content="Sure, new topic."),
    ])
    app = create_app(llm=llm, settings=Settings(dev_token=None))
    with TestClient(app) as client, client.websocket_connect("/ws") as ws:
        ws.send_json(HELLO); ws.receive_json()
        ws.send_json({"type": "input.text", "payload": {"text": "text sam hi"}})
        assert ws.receive_json()["payload"]["card"] == "confirm"
        ws.send_json({"type": "input.text", "payload": {"text": "actually never mind"}})
        assert ws.receive_json()["payload"]["text"] == "Okay, not sending."
        assert ws.receive_json()["payload"]["text"] == "Sure, new topic."
