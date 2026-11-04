import asyncio

import pytest

from zibi.protocol import Envelope, T
from zibi.session import Session
from zibi.tools.base import Risk
from zibi.tools.registry import ToolRegistry


@pytest.mark.asyncio
async def test_device_tool_ignores_reply_with_wrong_message_type():
    sent = []
    sent_event = asyncio.Event()

    async def send(env):
        sent.append(env)
        sent_event.set()

    session = Session(
        send=send,
        session_id="s_test",
        device_id="device_test",
        timezone="UTC",
        registry=ToolRegistry(),
        context={},
        tool_timeout=0.01,
    )

    call = asyncio.create_task(session.call_device_tool("create_reminder", {}, Risk.WRITE))
    await sent_event.wait()
    request = sent[0]

    session.resolve(Envelope(type=T.RESPONSE_CARD, reply_to=request.id, payload={"ok": True}))

    result = await call
    assert result == {"ok": False, "error": "timeout: the phone did not respond"}
