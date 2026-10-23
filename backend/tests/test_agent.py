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
    assert names == {"resolve_date", "ask_user", "search_documents", "create_reminder", "send_message"}


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


async def test_ask_user_returns_the_answer_to_the_model(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "create_reminder", title="Lab notebook")]),
        LlmResponse(tool_calls=[call("c2", "ask_user", question="Add it to your calendar too?",
                                     options=["Add to calendar", "No thanks"])]),
        LlmResponse(content="ok"),
    ])
    bridge, history = FakeBridge(answer="No thanks"), []
    await Agent(llm).run(bridge, registry, history, "lab notebook due today 1pm", ctx)
    assert bridge.asks == [("Add it to your calendar too?", ["Add to calendar", "No thanks"])]
    assert "No thanks" in [m for m in history if m["role"] == "tool"][1]["content"]


async def test_ask_user_with_no_answer_is_an_error_not_a_crash(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "ask_user", question="Which one?", options=["a", "b"])]),
        LlmResponse(content="ok, skipping"),
    ])
    history = []
    await Agent(llm).run(FakeBridge(answer=None), registry, history, "x", ctx)
    assert "did not answer" in next(m for m in history if m["role"] == "tool")["content"]


async def test_ask_user_needs_at_least_two_options(registry, ctx):
    llm = FakeLlm([
        LlmResponse(tool_calls=[call("c1", "ask_user", question="Sure?", options=["only one"])]),
        LlmResponse(content="ok"),
    ])
    bridge, history = FakeBridge(), []
    await Agent(llm).run(bridge, registry, history, "x", ctx)
    assert bridge.asks == [] and "invalid arguments" in next(m for m in history if m["role"] == "tool")["content"]


async def test_system_prompt_teaches_brain_dump_handling(registry, ctx):
    llm = FakeLlm([LlmResponse(content="ok")])
    await Agent(llm).run(FakeBridge(), registry, [], "x", ctx)
    system = llm.calls[0]["messages"][0]["content"]
    for tool in ("add_to_list", "add_task", "save_message_draft"):
        assert tool in system
