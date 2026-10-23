"""Provider-agnostic LLM interface. The agent only depends on this."""
from __future__ import annotations

from typing import Any, Protocol

from pydantic import BaseModel, Field


class ToolCallRequest(BaseModel):
    id: str
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    arguments_error: str | None = None  # set when the model produced unparseable arguments


class LlmResponse(BaseModel):
    content: str | None = None
    tool_calls: list[ToolCallRequest] = Field(default_factory=list)


class LlmClient(Protocol):
    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        role: str = "orchestrator",
    ) -> LlmResponse: ...
