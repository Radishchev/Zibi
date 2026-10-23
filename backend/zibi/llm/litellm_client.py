"""LiteLLM-backed client. Works with Groq today; any other provider by editing models.yaml."""
from __future__ import annotations

import json
from typing import Any

from ..config import ModelConfig
from .base import LlmResponse, ToolCallRequest


class LiteLLMClient:
    def __init__(self, config: ModelConfig) -> None:
        self.config = config

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str = "orchestrator",
    ) -> LlmResponse:
        import litellm  # imported lazily so tests don't need it installed

        kwargs: dict[str, Any] = {"model": self.config.models[role], "messages": messages}
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"
        if effort := self.config.reasoning_effort.get(role):
            kwargs["reasoning_effort"] = effort

        resp = await litellm.acompletion(**kwargs)
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
            calls.append(
                ToolCallRequest(id=tc.id, name=tc.function.name, arguments=args, arguments_error=err)
            )
        return LlmResponse(content=msg.content, tool_calls=calls)
