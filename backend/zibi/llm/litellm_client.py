"""LiteLLM-backed client. Works with Gemini or Groq today; any other provider by editing models.yaml."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

from ..config import ModelConfig
from .base import LlmResponse, LlmUnavailable, ToolCallRequest

log = logging.getLogger("zibi.llm")

MAX_ATTEMPTS = 4          # per model
MAX_WAIT_SECONDS = 30.0
MAX_BACKOFF_SECONDS = 10.0  # for provider overloads (503): 2s, 4s, 8s
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
    def __init__(self, config: ModelConfig) -> None:
        self.config = config

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str = "orchestrator",
    ) -> LlmResponse:
        """Ask the role's main model; if it is overloaded, rate limited or retired, try the fallbacks in order."""
        models = [self.config.models[role], *self.config.fallbacks.get(role, [])]
        for i, model in enumerate(models):
            try:
                return await self._chat_with(model, messages, tools, role)
            except LlmUnavailable as e:
                if i == len(models) - 1:
                    raise
                log.warning("%s failed (%s). Falling back to %s", model, e, models[i + 1])
        raise AssertionError("unreachable")

    async def _chat_with(
        self,
        model: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str,
    ) -> LlmResponse:
        import litellm  # imported lazily so tests don't need it installed

        def errors(*names: str) -> tuple[type, ...]:
            return tuple(getattr(litellm, n) for n in names if hasattr(litellm, n))

        transient = errors("APIConnectionError", "ServiceUnavailableError", "InternalServerError", "Timeout")
        auth_errors = errors("AuthenticationError")
        bad_requests = errors("BadRequestError")
        not_found = errors("NotFoundError")

        kwargs: dict[str, Any] = {"model": model, "messages": prepare_messages(messages, model)}
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"
        if effort := self.config.reasoning_effort.get(role):
            kwargs["reasoning_effort"] = effort

        resp = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                resp = await litellm.acompletion(**kwargs)
                break
            except litellm.RateLimitError as e:
                wait = retry_after_seconds(str(e)) or 2.0 * attempt
                if attempt == MAX_ATTEMPTS or wait > MAX_WAIT_SECONDS:
                    raise LlmUnavailable(
                        "The AI provider's rate limit was reached (free tier). "
                        f"Try again in about {max(1, round(wait))} seconds."
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
            except transient as e:
                # 503 "overloaded" is common for brand-new models and usually clears within seconds
                if attempt == MAX_ATTEMPTS:
                    raise LlmUnavailable("The AI provider is overloaded or unreachable right now. Please try again.") from e
                wait = min(2.0 * 2 ** (attempt - 1), MAX_BACKOFF_SECONDS)
                log.warning("provider error (%s), retrying in %.0fs (attempt %d/%d)", type(e).__name__, wait, attempt, MAX_ATTEMPTS)
                await asyncio.sleep(wait)
        assert resp is not None

        usage = getattr(resp, "usage", None)
        if usage is not None:
            log.info(
                "llm role=%s model=%s prompt_tokens=%s completion_tokens=%s",
                role, model, getattr(usage, "prompt_tokens", None), getattr(usage, "completion_tokens", None),
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
