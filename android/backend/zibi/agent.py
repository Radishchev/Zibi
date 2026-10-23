"""The agent loop: LLM -> tool calls -> results -> LLM ... until it answers in plain text."""
from __future__ import annotations

import json
import logging
from typing import Any, Protocol

from jsonschema import Draft202012Validator

from .context import AgentContext, build_system_prompt
from .llm.base import LlmClient, ToolCallRequest
from .policy import requires_confirmation
from .tools.base import Risk
from .tools.registry import ToolRegistry

log = logging.getLogger("zibi.agent")

STUCK_MESSAGE = "Sorry, I got stuck on that one. Could you try rephrasing it?"


class DeviceBridge(Protocol):
    """How the agent reaches the phone. The WebSocket Session implements this."""

    async def call_device_tool(self, name: str, args: dict[str, Any], risk: Risk) -> dict[str, Any]: ...
    async def confirm(self, summary: str) -> bool: ...


class Agent:
    def __init__(self, llm: LlmClient, max_steps: int = 8) -> None:
        self.llm = llm
        self.max_steps = max_steps

    async def run(
        self,
        bridge: DeviceBridge,
        registry: ToolRegistry,
        history: list[dict[str, Any]],
        user_text: str,
        ctx: AgentContext,
    ) -> str:
        start = len(history)
        try:
            return await self._run(bridge, registry, history, user_text, ctx)
        except BaseException:
            # never leave a dangling assistant tool_call in history, it would break the next turn
            del history[start:]
            raise

    async def _run(self, bridge, registry, history, user_text, ctx) -> str:
        system = {"role": "system", "content": build_system_prompt(ctx)}
        history.append({"role": "user", "content": user_text})

        for step in range(self.max_steps):
            resp = await self.llm.chat([system, *history], registry.llm_schemas())

            if not resp.tool_calls:
                text = resp.content or ""
                history.append({"role": "assistant", "content": text})
                return text

            history.append(
                {
                    "role": "assistant",
                    "content": resp.content,
                    "tool_calls": [
                        {
                            "id": c.id,
                            "type": "function",
                            "function": {"name": c.name, "arguments": json.dumps(c.arguments)},
                        }
                        for c in resp.tool_calls
                    ],
                }
            )
            for call in resp.tool_calls:
                result = await self._execute(call, bridge, registry, ctx)
                log.info("step=%d tool=%s args=%s -> %s", step, call.name, call.arguments, result)
                history.append(
                    {"role": "tool", "tool_call_id": call.id, "content": json.dumps(result)}
                )

        history.append({"role": "assistant", "content": STUCK_MESSAGE})
        return STUCK_MESSAGE

    async def _execute(
        self, call: ToolCallRequest, bridge: DeviceBridge, registry: ToolRegistry, ctx: AgentContext
    ) -> dict[str, Any]:
        spec = registry.get(call.name)
        if spec is None or not spec.enabled:
            return {"ok": False, "error": f"unknown tool: {call.name}"}
        if call.arguments_error:
            return {"ok": False, "error": call.arguments_error}

        # validate in code, the model may retry with the error message
        errors = sorted(
            Draft202012Validator(spec.parameters).iter_errors(call.arguments),
            key=lambda e: list(e.path),
        )
        if errors:
            msg = "; ".join(f"{'.'.join(map(str, e.path)) or 'arguments'}: {e.message}" for e in errors)
            return {"ok": False, "error": f"invalid arguments: {msg}"}

        if requires_confirmation(spec):
            summary = f"{spec.name}: {json.dumps(call.arguments, ensure_ascii=False)}"
            if not await bridge.confirm(summary):
                return {"ok": False, "error": "user_declined"}

        if spec.location == "cloud":
            tool = registry.cloud_tool(call.name)
            try:
                return await tool.fn(call.arguments, ctx)  # type: ignore[union-attr]
            except Exception as e:  # tool bugs must not crash the loop
                log.exception("cloud tool %s failed", call.name)
                return {"ok": False, "error": f"tool failed: {e}"}

        return await bridge.call_device_tool(call.name, call.arguments, spec.risk)
