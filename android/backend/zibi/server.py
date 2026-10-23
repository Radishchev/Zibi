"""FastAPI app: one WebSocket endpoint that carries the whole conversation."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from .agent import Agent
from .config import Settings, load_model_config
from .context import AgentContext
from .llm.base import LlmClient
from .protocol import Envelope, HelloPayload, T, message_card, new_id
from .session import Session
from .tools.cloud import build_default_registry
from .tools.registry import ToolRegistry

log = logging.getLogger("zibi.server")


def create_app(
    llm: LlmClient | None = None,
    settings: Settings | None = None,
    base_registry: ToolRegistry | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    if llm is None:
        from .llm.litellm_client import LiteLLMClient

        llm = LiteLLMClient(load_model_config(settings.models_file))
    base_registry = base_registry or build_default_registry()
    agent = Agent(llm)

    app = FastAPI(title="Zibi backend")

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    def make_context(session: Session) -> AgentContext:
        try:
            tz = ZoneInfo(session.timezone)
        except Exception:
            tz = ZoneInfo("UTC")
        return AgentContext(
            now=datetime.now(tz),
            timezone=str(tz),
            timetable=session.context.get("timetable", []),
            preferences=session.context.get("preferences", {}),
        )

    async def run_turn(session: Session, text: str) -> None:
        async with session.run_lock:
            try:
                reply = await agent.run(session, session.registry, session.history, text, make_context(session))
                await session.send(Envelope(type=T.RESPONSE_CARD, payload=message_card(reply)))
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.exception("agent turn failed")
                await session.send(
                    Envelope(type=T.ERROR, payload={"code": "agent_failed", "message": str(e)})
                )

    @app.websocket("/ws")
    async def ws_endpoint(websocket: WebSocket) -> None:
        if settings.dev_token and websocket.query_params.get("token") != settings.dev_token:
            await websocket.close(code=4401)
            return
        await websocket.accept()

        async def send(env: Envelope) -> None:
            await websocket.send_text(env.model_dump_json())

        async def send_error(code: str, message: str) -> None:
            await send(Envelope(type=T.ERROR, payload={"code": code, "message": message}))

        session: Session | None = None
        tasks: set[asyncio.Task] = set()
        try:
            while True:
                raw = await websocket.receive_text()
                try:
                    env = Envelope.model_validate_json(raw)
                except ValidationError as e:
                    await send_error("bad_envelope", str(e))
                    continue

                if session is None:
                    if env.type != T.HELLO:
                        await send_error("expected_hello", "first message must be 'hello'")
                        continue
                    try:
                        hello = HelloPayload.model_validate(env.payload)
                    except ValidationError as e:
                        await send_error("bad_hello", str(e))
                        continue
                    registry = base_registry.fork()
                    rejected = registry.set_device_tools(hello.tools)
                    session = Session(
                        send=send,
                        session_id=new_id("s"),
                        device_id=hello.device_id,
                        timezone=hello.timezone,
                        registry=registry,
                        context=dict(hello.context),
                    )
                    await session.send(
                        Envelope(
                            type=T.HELLO_ACK,
                            reply_to=env.id,
                            payload={
                                "tools": [s.name for s in registry.specs()],
                                "rejected_tools": rejected,
                            },
                        )
                    )
                    continue

                if env.type in (T.TOOL_RESULT, T.TOOL_ERROR, T.UI_ACTION):
                    session.resolve(env)
                elif env.type == T.INPUT_TEXT:
                    text = str(env.payload.get("text", "")).strip()
                    if not text:
                        await send_error("empty_input", "input.text needs payload.text")
                        continue
                    # run in a task so we keep receiving tool results while the agent works
                    task = asyncio.create_task(run_turn(session, text))
                    tasks.add(task)
                    task.add_done_callback(tasks.discard)
                elif env.type == T.CONTEXT_UPDATE:
                    session.context.update(env.payload)
                else:
                    await send_error("unknown_type", f"unsupported message type: {env.type}")
        except WebSocketDisconnect:
            pass
        finally:
            for t in tasks:
                t.cancel()
            if session:
                session.close()

    return app
