from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from zibi.context import AgentContext
from zibi.tools.base import Risk, ToolSpec
from zibi.tools.cloud import build_default_registry

REMINDER = ToolSpec(
    name="create_reminder",
    description="Create a reminder.",
    parameters={
        "type": "object",
        "properties": {"title": {"type": "string"}, "trigger": {"type": "string", "enum": ["on_leaving_home", "at_time"]}},
        "required": ["title"],
        "additionalProperties": False,
    },
    risk=Risk.WRITE,
)
SEND = ToolSpec(
    name="send_message",
    description="Send a message to a contact.",
    parameters={
        "type": "object",
        "properties": {"to": {"type": "string"}, "text": {"type": "string"}},
        "required": ["to", "text"],
    },
    risk=Risk.HIGH_STAKES,
)


class FakeBridge:
    def __init__(self, approve: bool = True):
        self.approve = approve
        self.calls: list[tuple[str, dict]] = []
        self.confirms: list[str] = []

    async def call_device_tool(self, name, args, risk):
        self.calls.append((name, args))
        return {"ok": True, "result": {"id": 1}}

    async def confirm(self, summary):
        self.confirms.append(summary)
        return self.approve


@pytest.fixture
def registry():
    r = build_default_registry().fork()
    r.set_device_tools([REMINDER, SEND])
    return r


@pytest.fixture
def ctx():
    return AgentContext(now=datetime(2026, 10, 23, 9, 0, tzinfo=ZoneInfo("UTC")), timezone="UTC")
