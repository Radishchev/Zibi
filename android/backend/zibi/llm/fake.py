"""Scripted LLM for tests: returns pre-written responses in order and records what it was sent."""
from __future__ import annotations

from typing import Any

from .base import LlmResponse


class FakeLlm:
    def __init__(self, script: list[LlmResponse]) -> None:
        self.script = list(script)
        self.calls: list[dict[str, Any]] = []

    async def chat(self, messages, tools, role="orchestrator") -> LlmResponse:
        self.calls.append({"messages": [dict(m) for m in messages], "tools": tools, "role": role})
        if not self.script:
            raise AssertionError("FakeLlm script exhausted")
        return self.script.pop(0)
