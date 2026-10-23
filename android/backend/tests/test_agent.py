from tests.conftest import FakeBridge
from zibi.agent import STUCK_MESSAGE, Agent
from zibi.llm.base import LlmResponse, ToolCallRequest
from zibi.llm.fake import FakeLlm


def call(id, name, **args):
    return ToolCallRequest(id=id, name=name, arguments=args)


async def test_single_tool_call_then_answer(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "create_reminder", title="Clip nails", trigger="on_leaving_home")]),
        LlmResponse(content="Done, I'll remind you before you leave."),
    ])
    bridge, history = FakeBridge(), []
    out = await Agent(llm).run(bridge, registry, history, "remind me to clip my nails before going out", ctx)

    assert out.startswith("Done")
    assert bridge.calls == [("create_reminder", {"title": "Clip nails", "trigger": "on_leaving_home"})]
    roles = [m["role"] for m in history]
    assert roles == ["user", "assistant", "tool", "assistant"]
    # the tool schemas (cloud + device) were offered to the model
    names = {t["function"]["name"] for t in llm.calls[0]["tools"]}
    assert names == {"resolve_date", "create_reminder", "send_message"}


async def test_invalid_arguments_are_returned_to_the_model_and_retried(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "create_reminder")]),  # missing required title
        LlmResponse(tool_calls=[call("c2", "create_reminder", title="Clip nails")]),
        LlmResponse(content="Done."),
    ])
    bridge, history = FakeBridge(), []
    await Agent(llm).run(bridge, registry, history, "remind me", ctx)

    assert len(bridge.calls) == 1  # the bad call never reached the phone
    first_tool_msg = next(m for m in history if m["role"] == "tool")
    assert "invalid arguments" in first_tool_msg["content"] and "title" in first_tool_msg["content"]


async def test_unknown_tool_does_not_crash(registry, ctx):
    llm = FakeLlm([LlmResponse(tool_calls=[call("c1", "launch_missiles")]), LlmResponse(content="Can't do that.")])
    bridge = FakeBridge()
    out = await Agent(llm).run(bridge, registry, [], "x", ctx)
    assert out == "Can't do that." and bridge.calls == []


async def test_high_stakes_requires_approval_and_runs_when_approved(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "send_message", to="Sam", text="hi")]),
        LlmResponse(content="Sent."),
    ])
    bridge = FakeBridge(approve=True)
    await Agent(llm).run(bridge, registry, [], "text sam hi", ctx)
    assert len(bridge.confirms) == 1 and bridge.calls[0][0] == "send_message"


async def test_high_stakes_declined_never_executes(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "send_message", to="Sam", text="hi")]),
        LlmResponse(content="Okay, not sending."),
    ])
    bridge, history = FakeBridge(approve=False), []
    await Agent(llm).run(bridge, registry, history, "text sam hi", ctx)
    assert bridge.calls == []
    assert "user_declined" in next(m for m in history if m["role"] == "tool")["content"]


async def test_cloud_tool_resolves_dates_in_code(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "resolve_date", phrase="next friday")]),
        LlmResponse(content="ok"),
    ])
    history = []
    await Agent(llm).run(FakeBridge(), registry, history, "x", ctx)
    # ctx.now is Friday 2026-10-23, so "next friday" is a week later
    assert "2026-10-30" in next(m for m in history if m["role"] == "tool")["content"]


async def test_loop_is_capped(registry, ctx):
    endless = [LlmResponse(tool_calls=[call(f"c{i}", "create_reminder", title="x")]) for i in range(20)]
    bridge = FakeBridge()
    out = await Agent(FakeLlm(endless), max_steps=3).run(bridge, registry, [], "x", ctx)
    assert out == STUCK_MESSAGE and len(bridge.calls) == 3


async def test_failed_turn_leaves_history_clean(registry, ctx):
    llm = FakeLlm([LlmResponse(tool_calls=[call("c1", "create_reminder", title="x")])])  # 2nd call exhausts script
    history = [{"role": "user", "content": "earlier"}]
    try:
        await Agent(llm).run(FakeBridge(), registry, history, "x", ctx)
    except AssertionError:
        pass
    assert history == [{"role": "user", "content": "earlier"}]
