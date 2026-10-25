import sys
import types
from types import SimpleNamespace

import pytest

from zibi.config import ModelConfig
from zibi.llm.base import LlmUnavailable
from zibi.llm.litellm_client import LiteLLMClient, prepare_messages, retry_after_seconds


class RateLimitError(Exception): ...
class APIConnectionError(Exception): ...
class AuthenticationError(Exception): ...
class BadRequestError(Exception): ...
class ServiceUnavailableError(Exception): ...
class NotFoundError(Exception): ...


def reply(content="hi", tool_calls=None):
    msg = SimpleNamespace(content=content, tool_calls=tool_calls)
    return SimpleNamespace(choices=[SimpleNamespace(message=msg)], usage=SimpleNamespace(prompt_tokens=10, completion_tokens=2))


@pytest.fixture
def fake_litellm(monkeypatch):
    queue: list = []
    sleeps: list[float] = []
    sent: list[dict] = []
    mod = types.ModuleType("litellm")
    mod.RateLimitError, mod.APIConnectionError = RateLimitError, APIConnectionError
    mod.AuthenticationError, mod.BadRequestError = AuthenticationError, BadRequestError
    mod.ServiceUnavailableError, mod.NotFoundError = ServiceUnavailableError, NotFoundError

    async def acompletion(**kwargs):
        sent.append(kwargs)
        item = queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    async def fake_sleep(s):
        sleeps.append(s)

    mod.acompletion = acompletion
    monkeypatch.setitem(sys.modules, "litellm", mod)
    monkeypatch.setattr("zibi.llm.litellm_client.asyncio.sleep", fake_sleep)
    return queue, sleeps, sent


def client(model="groq/x", fallbacks=None):
    return LiteLLMClient(ModelConfig(models={"orchestrator": model}, fallbacks={"orchestrator": fallbacks or []}))


def test_retry_after_parsing_for_groq_and_gemini_messages():
    assert retry_after_seconds("Please try again in 4.252499999s. Need more") == pytest.approx(4.2525)
    assert retry_after_seconds("try again in 1m2.5s") == pytest.approx(62.5)
    assert retry_after_seconds("try again in 800ms") == pytest.approx(0.8)
    assert retry_after_seconds("RESOURCE_EXHAUSTED. Please retry in 41.64s.") == pytest.approx(41.64, abs=0.01)
    assert retry_after_seconds('{"retryDelay": "17s"}') == pytest.approx(17)
    assert retry_after_seconds("no hint here") is None


async def test_waits_the_time_the_provider_asks_and_then_succeeds(fake_litellm):
    queue, sleeps, _ = fake_litellm
    queue += [RateLimitError("Rate limit. Please retry in 4.2s."), reply("done")]
    out = await client().chat([{"role": "user", "content": "x"}], [])
    assert out.content == "done"
    assert sleeps == [pytest.approx(4.7)]


async def test_gives_up_with_a_friendly_message(fake_litellm):
    queue, sleeps, _ = fake_litellm
    queue += [RateLimitError("try again in 1s")] * 4
    with pytest.raises(LlmUnavailable, match="rate limit"):
        await client().chat([], [])
    assert len(sleeps) == 3


async def test_a_very_long_wait_is_not_retried(fake_litellm):
    queue, sleeps, _ = fake_litellm
    queue += [RateLimitError("try again in 3m20s")]
    with pytest.raises(LlmUnavailable, match="200 seconds"):
        await client().chat([], [])
    assert sleeps == []


async def test_bad_key_and_connection_errors_are_user_friendly(fake_litellm):
    queue, _, _ = fake_litellm
    queue += [AuthenticationError("401")]
    with pytest.raises(LlmUnavailable, match="API key"):
        await client().chat([], [])
    queue += [APIConnectionError("boom")] * 4
    with pytest.raises(LlmUnavailable, match="overloaded or unreachable"):
        await client().chat([], [])


async def test_a_malformed_tool_call_is_retried_then_succeeds(fake_litellm):
    # the real failure from the log: Groq rejected the model's tool call with a 400 "tool_use_failed"
    queue, sleeps, sent = fake_litellm
    queue += [BadRequestError('{"code":"tool_use_failed","message":"Tool call validation failed"}'), reply("fine")]
    out = await client().chat([], [])
    assert out.content == "fine" and len(sent) == 2


async def test_a_persistently_malformed_tool_call_becomes_a_friendly_error(fake_litellm):
    queue, _, _ = fake_litellm
    queue += [BadRequestError("tool_use_failed")] * 4
    with pytest.raises(LlmUnavailable, match="rejected"):
        await client().chat([], [])


async def test_other_bad_requests_fail_fast_without_retrying(fake_litellm):
    queue, _, sent = fake_litellm
    queue += [BadRequestError("Function call is missing a thought_signature")]
    with pytest.raises(LlmUnavailable, match="rejected"):
        await client("gemini/gemini-2.5-flash").chat([], [])
    assert len(sent) == 1


async def test_unparseable_tool_arguments_are_flagged_not_fatal(fake_litellm):
    queue, _, _ = fake_litellm
    tc = SimpleNamespace(id="c1", function=SimpleNamespace(name="t", arguments="{not json"))
    queue += [reply(None, [tc])]
    out = await client().chat([], [])
    assert out.tool_calls[0].arguments_error and out.tool_calls[0].arguments == {}


async def test_gemini_thought_signatures_are_captured(fake_litellm):
    queue, _, _ = fake_litellm
    tc = SimpleNamespace(id="c1", function=SimpleNamespace(name="t", arguments="{}"),
                         provider_specific_fields={"thought_signature": "abc"})
    queue += [reply(None, [tc])]
    out = await client("gemini/gemini-2.5-flash").chat([], [])
    assert out.tool_calls[0].provider_fields == {"thought_signature": "abc"}


def test_signatures_are_sent_to_gemini_but_stripped_for_other_providers():
    history = [{"role": "assistant", "content": None, "tool_calls": [
        {"id": "c1", "type": "function", "function": {"name": "t", "arguments": "{}"},
         "provider_specific_fields": {"thought_signature": "abc"}}]}]
    kept = prepare_messages(history, "gemini/gemini-2.5-flash")
    assert kept[0]["tool_calls"][0]["provider_specific_fields"] == {"thought_signature": "abc"}
    stripped = prepare_messages(history, "groq/openai/gpt-oss-120b")
    assert "provider_specific_fields" not in stripped[0]["tool_calls"][0]
    assert "provider_specific_fields" in history[0]["tool_calls"][0]  # the stored history is not modified


async def test_an_overloaded_provider_is_retried_with_growing_waits(fake_litellm):
    # the real failure from the log: Gemini answered 503 (ServiceUnavailableError), then worked on a retry
    queue, sleeps, sent = fake_litellm
    queue += [ServiceUnavailableError("503 overloaded"), ServiceUnavailableError("503 overloaded"), reply("fine")]
    out = await client("gemini/gemini-3.8-flash").chat([], [])
    assert out.content == "fine" and len(sent) == 3
    assert sleeps == [2.0, 4.0]


async def test_overload_that_never_clears_ends_with_a_friendly_error_and_waits_2_4_8(fake_litellm):
    queue, sleeps, _ = fake_litellm
    queue += [ServiceUnavailableError("503")] * 4
    with pytest.raises(LlmUnavailable, match="overloaded"):
        await client("gemini/gemini-3.8-flash").chat([], [])
    assert sleeps == [2.0, 4.0, 8.0]


async def test_falls_back_to_the_next_model_when_the_main_one_stays_overloaded(fake_litellm):
    queue, _, sent = fake_litellm
    queue += [ServiceUnavailableError("503")] * 4 + [reply("from groq")]
    out = await client("gemini/gemini-3.8-flash", ["groq/openai/gpt-oss-120b"]).chat([], [])
    assert out.content == "from groq"
    assert [c["model"] for c in sent] == ["gemini/gemini-3.8-flash"] * 4 + ["groq/openai/gpt-oss-120b"]


async def test_a_retired_model_name_gives_a_clear_message_or_uses_the_fallback(fake_litellm):
    queue, _, sent = fake_litellm
    queue += [NotFoundError("models/gemini-2.5-flash is no longer available to new users")]
    with pytest.raises(LlmUnavailable, match=r"gemini-2.5-flash isn't available.*models.yaml"):
        await client("gemini/gemini-2.5-flash").chat([], [])
    queue += [NotFoundError("gone"), reply("fallback answered")]
    out = await client("gemini/gemini-2.5-flash", ["groq/x"]).chat([], [])
    assert out.content == "fallback answered" and sent[-1]["model"] == "groq/x"


async def test_rate_limit_on_the_main_model_also_falls_back(fake_litellm):
    queue, _, sent = fake_litellm
    queue += [RateLimitError("Please retry in 120s (daily quota)"), reply("ok")]
    out = await client("gemini/gemini-3.8-flash", ["groq/x"]).chat([], [])
    assert out.content == "ok" and sent[-1]["model"] == "groq/x"


async def test_when_every_model_fails_the_last_error_is_reported(fake_litellm):
    queue, _, _ = fake_litellm
    queue += [NotFoundError("gone"), AuthenticationError("bad key")]
    with pytest.raises(LlmUnavailable, match="API key"):
        await client("gemini/a", ["groq/b"]).chat([], [])


async def test_signatures_are_stripped_when_falling_back_to_another_provider(fake_litellm):
    queue, _, sent = fake_litellm
    history = [{"role": "assistant", "content": None, "tool_calls": [
        {"id": "c1", "type": "function", "function": {"name": "t", "arguments": "{}"},
         "provider_specific_fields": {"thought_signature": "abc"}}]}]
    queue += [ServiceUnavailableError("503")] * 4 + [reply("ok")]
    await client("gemini/gemini-3.8-flash", ["groq/x"]).chat(history, [])
    assert "provider_specific_fields" in sent[0]["messages"][0]["tool_calls"][0]
    assert "provider_specific_fields" not in sent[-1]["messages"][0]["tool_calls"][0]
