import pytest
from tests.conftest import FakeBridge
from zibi.agent import Agent
from zibi.context import AgentContext
from zibi.llm.base import LlmResponse, ToolCallRequest
from zibi.llm.fake import FakeLlm
from zibi.profile import ProfileStore
from zibi.tools.cloud import build_default_registry


@pytest.fixture
def store(tmp_path):
    return ProfileStore(tmp_path / "p.db")


def test_remember_fact_and_persistence(store):
    store.remember_fact("device1", "wake_time", "07:00", "routine")
    store.remember_fact("device1", "alarm_style", "heavy sleeper", "preference")
    
    # updating existing key updates in place
    store.remember_fact("device1", "wake_time", "06:30", "routine")

    facts = store.get_profile("device1")
    assert len(facts) == 2
    keys = {f["key"]: f["value"] for f in facts}
    assert keys["wake_time"] == "06:30"
    assert keys["alarm_style"] == "heavy sleeper"

    # device isolation
    assert store.get_profile("device2") == []


def test_timetable_storage_and_overwrite(store):
    tt1 = [
        {"day": "Monday", "start": "09:00", "end": "11:00", "title": "Math"},
        {"day": "Wednesday", "start": "14:00", "end": "16:00", "title": "Physics"},
    ]
    store.set_timetable("device1", tt1)
    retrieved = store.get_timetable("device1")
    assert len(retrieved) == 2
    assert retrieved[0]["title"] == "Math"

    # updating replaces the weekly schedule
    tt2 = [{"day": "Friday", "start": "10:00", "end": "12:00", "title": "Chemistry"}]
    store.set_timetable("device1", tt2)
    retrieved2 = store.get_timetable("device1")
    assert len(retrieved2) == 1
    assert retrieved2[0]["title"] == "Chemistry"


async def test_agent_calls_remember_fact(store, ctx):
    ctx.owner = "device1"
    ctx.profile_store = store
    registry = build_default_registry()
    
    llm = FakeLlm([
        LlmResponse(tool_calls=[ToolCallRequest(
            id="c1",
            name="remember_fact",
            arguments={"key": "alarm_style", "value": "12 alarms", "category": "preference"}
        )]),
        LlmResponse(content="Remembered that you like 12 alarms."),
    ])
    
    history = []
    out = await Agent(llm).run(FakeBridge(), registry, history, "I am a heavy sleeper", ctx)
    assert "Remembered" in out
    
    # Check in-memory profile was updated
    assert any(f["key"] == "alarm_style" and f["value"] == "12 alarms" for f in ctx.profile)
    # Check persisted profile
    persisted = store.get_profile("device1")
    assert any(f["key"] == "alarm_style" for f in persisted)
