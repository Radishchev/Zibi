"""LiteLLM-backed client. Works with Gemini or Groq today; any other provider by editing models.yaml."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
from typing import Any

from ..config import ModelConfig, model_chain
from .base import LlmResponse, LlmUnavailable, ToolCallRequest

log = logging.getLogger("zibi.llm")

MAX_ATTEMPTS = 4          # per model
MAX_WAIT_SECONDS = 30.0
MAX_BACKOFF_SECONDS = 10.0  # for provider overloads (503): 2s, 4s, 8s
REQUEST_TIMEOUT_SECONDS = 30.0  # one slow call must not hang the chat; override with ZIBI_LLM_TIMEOUT
SHORT_WAIT_SECONDS = 3.0    # when a fallback exists, never sit through a longer rate-limit wait
SLOW_CALL_SECONDS = 10.0
_GEMINI_PREFIXES = ("gemini/", "vertex_ai/")


def retry_after_seconds(message: str) -> float | None:
    """Parses the wait out of a provider's rate-limit message.

    Groq: 'Please try again in 4.25s' (also '1m2.5s', '800ms').  Gemini: 'Please retry in 41.6s' or retryDelay "41s".
    """
    m = re.search(r"(?:try again|retry) in ([0-9hms.]+)", message) or re.search(r'retryDelay\W+([0-9hms.]+)', message)
    if not m:
        return None
    units = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}
    total = sum(float(num) * units[unit] for num, unit in re.findall(r"(\d+(?:\.\d+)?)(ms|h|m|s)", m.group(1)))
    return total or None


def prepare_messages(messages: list[dict[str, Any]], model: str) -> list[dict[str, Any]]:
    """Gemini needs its thought signatures sent back; other providers reject the unknown field, so strip it."""
    if model.startswith(_GEMINI_PREFIXES):
        return messages
    out = []
    for m in messages:
        calls = m.get("tool_calls")
        if calls and any("provider_specific_fields" in c for c in calls):
            m = {**m, "tool_calls": [{k: v for k, v in c.items() if k != "provider_specific_fields"} for c in calls]}
        out.append(m)
    return out


class LiteLLMClient:
    def __init__(self, config: ModelConfig, timeout: float | None = None) -> None:
        self.config = config
        self.timeout = timeout if timeout is not None else float(os.getenv("ZIBI_LLM_TIMEOUT", REQUEST_TIMEOUT_SECONDS))

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str = "orchestrator",
    ) -> LlmResponse:
        """Ask the role's main model; if it is overloaded, rate limited or retired, try the fallbacks in order."""
        models = model_chain(self.config, role)
        problems: list[str] = []
        for i, model in enumerate(models):
            last = i == len(models) - 1
            try:
                # With a fallback available we give up on a struggling model quickly: waiting is slower than switching.
                return await self._chat_with(model, messages, tools, role, patient=last)
            except LlmUnavailable as e:
                problems.append(str(e))
                if last:
                    if len(problems) == 1:
                        raise
                    raise LlmUnavailable(f"{problems[0]} The fallback ({model}) also failed: {problems[-1]}") from e
                log.warning("%s failed (%s). Falling back to %s", model, e, models[i + 1])
        raise AssertionError("unreachable")

    async def _chat_with(
        self,
        model: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str,
        patient: bool = True,
    ) -> LlmResponse:
        import litellm  # imported lazily so tests don't need it installed

        def errors(*names: str) -> tuple[type, ...]:
            return tuple(getattr(litellm, n) for n in names if hasattr(litellm, n))

        transient = errors("APIConnectionError", "ServiceUnavailableError", "InternalServerError")
        timeouts = errors("Timeout")
        auth_errors = errors("AuthenticationError")
        bad_requests = errors("BadRequestError")
        not_found = errors("NotFoundError")

        kwargs: dict[str, Any] = {"model": model, "messages": prepare_messages(messages, model), "timeout": self.timeout}
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"
        if effort := self.config.reasoning_effort.get(role):
            if not model.startswith("nvidia_nim/"):  # NIM models reject reasoning_effort
                kwargs["reasoning_effort"] = effort

        resp = None
        transient_attempts = MAX_ATTEMPTS if patient else 2
        started = time.perf_counter()
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                started = time.perf_counter()
                resp = await litellm.acompletion(**kwargs)
                break
            except litellm.RateLimitError as e:
                wait = retry_after_seconds(str(e)) or 2.0 * attempt
                if attempt == MAX_ATTEMPTS or wait > MAX_WAIT_SECONDS or (not patient and wait > SHORT_WAIT_SECONDS):
                    when = f"about {max(1, round(wait))} seconds" if wait < 3600 else f"about {round(wait / 3600)} hours"
                    raise LlmUnavailable(
                        "The AI provider's rate limit was reached (free tier). "
                        f"Try again in {when}."
                    ) from e
                log.warning("rate limited (attempt %d/%d), waiting %.1fs", attempt, MAX_ATTEMPTS, wait)
                await asyncio.sleep(wait + 0.5)
            except auth_errors as e:
                raise LlmUnavailable("The AI provider rejected the API key. Check the key on the server.") from e
            except not_found as e:
                log.error("model %s was not found: %s", model, str(e)[:500])
                raise LlmUnavailable(
                    f"The model {model} isn't available for your API key (it may have been retired). "
                    "Update it in config/models.yaml; scripts/check_models.py lists what works."
                ) from e
            except bad_requests as e:
                text = str(e)
                if ("tool_use_failed" in text or "tool call validation failed" in text) and attempt < MAX_ATTEMPTS:
                    # the model produced a malformed tool call; sampling is random, so asking again usually works
                    log.warning("model made an invalid tool call (attempt %d/%d): %s", attempt, MAX_ATTEMPTS, text[:300])
                    continue
                log.error("provider rejected the request: %s", text[:1000])
                raise LlmUnavailable(
                    "The AI provider rejected that request. Try rephrasing, and check the server log for details."
                ) from e
            except timeouts as e:
                log.warning("%s took longer than %.0fs (attempt %d)", model, self.timeout, attempt)
                if attempt >= 2:
                    raise LlmUnavailable("The AI provider took too long to answer.") from e
            except transient as e:
                # 503 "overloaded" is common for brand-new models and usually clears within seconds
                if attempt >= transient_attempts:
                    raise LlmUnavailable("The AI provider is overloaded or unreachable right now. Please try again.") from e
                wait = min(2.0 * 2 ** (attempt - 1), MAX_BACKOFF_SECONDS) if patient else 1.0
                log.warning("provider error (%s), retrying in %.0fs (attempt %d/%d)", type(e).__name__, wait, attempt, transient_attempts)
                await asyncio.sleep(wait)
        assert resp is not None

        elapsed = time.perf_counter() - started
        usage = getattr(resp, "usage", None)
        reasoning = getattr(getattr(usage, "completion_tokens_details", None), "reasoning_tokens", None)
        log.log(
            logging.WARNING if elapsed > SLOW_CALL_SECONDS else logging.INFO,
            "llm role=%s model=%s seconds=%.1f prompt_tokens=%s completion_tokens=%s thinking_tokens=%s%s",
            role, model, elapsed,
            getattr(usage, "prompt_tokens", None), getattr(usage, "completion_tokens", None), reasoning,
            "  (slow: try scripts/check_models.py --ping)" if elapsed > SLOW_CALL_SECONDS else "",
        )

        msg = resp.choices[0].message
        calls: list[ToolCallRequest] = []
        for tc in msg.tool_calls or []:
            args: dict[str, Any] = {}
            err: str | None = None
            try:
                parsed = json.loads(tc.function.arguments or "{}")
                if isinstance(parsed, dict):
                    args = parsed
                else:
                    err = "arguments must be a JSON object"
            except json.JSONDecodeError as e:
                err = f"arguments were not valid JSON: {e}"
            extra = getattr(tc, "provider_specific_fields", None)
            calls.append(
                ToolCallRequest(
                    id=tc.id, name=tc.function.name, arguments=args, arguments_error=err,
                    provider_fields=dict(extra) if isinstance(extra, dict) and extra else None,
                )
            )
        return LlmResponse(content=msg.content, tool_calls=calls)
