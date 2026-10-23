"""One connected phone. Implements DeviceBridge over the WebSocket."""
from __future__ import annotations

import asyncio
from typing import Any, Awaitable, Callable

from .protocol import Envelope, T, confirm_card, new_id
from .tools.base import Risk
from .tools.registry import ToolRegistry

SendFn = Callable[[Envelope], Awaitable[None]]


class Session:
    def __init__(
        self,
        send: SendFn,
        session_id: str,
        device_id: str,
        timezone: str,
        registry: ToolRegistry,
        context: dict[str, Any],
        tool_timeout: float = 30.0,
        confirm_timeout: float = 120.0,
    ) -> None:
        self._send = send
        self._send_lock = asyncio.Lock()
        self.id = session_id
        self.device_id = device_id
        self.timezone = timezone
        self.registry = registry
        self.context = context
        self.history: list[dict[str, Any]] = []
        self.run_lock = asyncio.Lock()  # one agent turn at a time per session
        self.tool_timeout = tool_timeout
        self.confirm_timeout = confirm_timeout
        self._pending: dict[str, asyncio.Future[Envelope]] = {}

    async def send(self, env: Envelope) -> None:
        env.session = self.id
        async with self._send_lock:
            await self._send(env)

    # --- replies from the phone -------------------------------------------------
    def resolve(self, env: Envelope) -> None:
        fut = self._pending.get(env.reply_to or "")
        if fut and not fut.done():
            fut.set_result(env)

    def close(self) -> None:
        for fut in self._pending.values():
            if not fut.done():
                fut.cancel()
        self._pending.clear()

    async def _request(self, env: Envelope, timeout: float) -> Envelope | None:
        fut: asyncio.Future[Envelope] = asyncio.get_running_loop().create_future()
        self._pending[env.id] = fut
        try:
            await self.send(env)
            return await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            return None
        finally:
            self._pending.pop(env.id, None)

    # --- DeviceBridge -----------------------------------------------------------
    async def call_device_tool(self, name: str, args: dict[str, Any], risk: Risk) -> dict[str, Any]:
        call = Envelope(
            id=new_id("tc"),
            type=T.TOOL_CALL,
            payload={"tool": name, "args": args, "risk": risk.value},
        )
        reply = await self._request(call, self.tool_timeout)
        if reply is None:
            return {"ok": False, "error": "timeout: the phone did not respond"}
        if reply.type == T.TOOL_ERROR:
            return {"ok": False, "error": reply.payload.get("error", "unknown device error")}
        payload = dict(reply.payload)
        payload.setdefault("ok", True)
        return payload

    async def confirm(self, summary: str) -> bool:
        card = Envelope(id=new_id("cf"), type=T.RESPONSE_CARD, payload=confirm_card(summary))
        reply = await self._request(card, self.confirm_timeout)
        return bool(
            reply and reply.type == T.UI_ACTION and reply.payload.get("action_id") == "approve"
        )
