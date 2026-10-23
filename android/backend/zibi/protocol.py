"""Wire protocol between the Android app and the backend. See /protocol/PROTOCOL.md."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field

from .tools.base import ToolSpec

PROTOCOL_VERSION = 1


def new_id(prefix: str = "msg") -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class T:
    """Message type names."""

    HELLO = "hello"
    HELLO_ACK = "hello.ack"
    INPUT_TEXT = "input.text"
    CONTEXT_UPDATE = "context.update"
    TOOL_CALL = "tool.call"
    TOOL_RESULT = "tool.result"
    TOOL_ERROR = "tool.error"
    UI_ACTION = "ui.action"
    RESPONSE_CARD = "response.card"
    ERROR = "error"


class Envelope(BaseModel):
    v: int = PROTOCOL_VERSION
    id: str = Field(default_factory=new_id)
    type: str
    session: str | None = None
    reply_to: str | None = None
    ts: str = Field(default_factory=now_iso)
    payload: dict[str, Any] = Field(default_factory=dict)


class HelloPayload(BaseModel):
    device_id: str
    timezone: str = "UTC"
    tools: list[ToolSpec] = Field(default_factory=list)  # the phone's tool manifest
    context: dict[str, Any] = Field(default_factory=dict)  # timetable, preferences, ...


# --- ZibiResponse cards (server -> phone, inside a response.card payload) ---

def message_card(text: str) -> dict[str, Any]:
    return {"card": "message", "text": text}


def confirm_card(summary: str) -> dict[str, Any]:
    return {
        "card": "confirm",
        "summary": summary,
        "actions": [
            {"id": "approve", "label": "Yes"},
            {"id": "deny", "label": "No"},
        ],
    }
