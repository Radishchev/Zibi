"""FastAPI app: one WebSocket endpoint that carries the whole conversation."""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from .agent import Agent
from .config import Settings, load_model_config
from .context import AgentContext
from .llm.base import LlmClient
from .protocol import Envelope, HelloPayload, T, message_card, new_id
from .rag import MAX_UPLOAD_BYTES, DocumentStore, PdfProblem, ingest_pdf
from .session import Session
from .tools.cloud import build_default_registry
from .tools.registry import ToolRegistry

log = logging.getLogger("zibi.server")


def create_app(
    llm: LlmClient | None = None,
    settings: Settings | None = None,
    base_registry: ToolRegistry | None = None,
    store: DocumentStore | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    if llm is None:
        from .llm.litellm_client import LiteLLMClient

        llm = LiteLLMClient(load_model_config(settings.models_file))
    base_registry = base_registry or build_default_registry()
    store = store or DocumentStore(settings.data_dir / "zibi.db")
    agent = Agent(llm)

    app = FastAPI(title="Zibi backend")

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True}

    async def make_context(session: Session) -> AgentContext:
        try:
            tz = ZoneInfo(session.timezone)
        except Exception:
            tz = ZoneInfo("UTC")
        docs = await asyncio.to_thread(store.list_documents, session.device_id)
        return AgentContext(
            now=datetime.now(tz),
            timezone=str(tz),
            timetable=session.context.get("timetable", []),
            preferences=session.context.get("preferences", {}),
            owner=session.device_id,
            documents=store,
            document_names=[d["name"] for d in docs],
        )

    # --- document library (REST). Files never travel over the WebSocket. ---
    def check_token(token: str | None) -> None:
        if settings.dev_token and token != settings.dev_token:
            raise HTTPException(status_code=401, detail="Bad token.")

    @app.post("/documents")
    async def upload_document(
        request: Request,
        name: str,
        x_zibi_device: str = Header(),
        x_zibi_token: str | None = Header(default=None),
    ) -> dict:
        check_token(x_zibi_token)
        declared = request.headers.get("content-length", "")
        if declared.isdigit() and int(declared) > MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail="That file is too large (50 MB limit).")
        data = await request.body()
        if len(data) > MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail="That file is too large (50 MB limit).")
        if not data.startswith(b"%PDF"):
            raise HTTPException(status_code=422, detail="Only PDF files are supported.")
        try:
            return await asyncio.to_thread(ingest_pdf, store, x_zibi_device, name, data)
        except PdfProblem as e:
            raise HTTPException(status_code=422, detail=str(e)) from None

    @app.get("/documents")
    async def list_documents(
        x_zibi_device: str = Header(), x_zibi_token: str | None = Header(default=None)
    ) -> dict:
        check_token(x_zibi_token)
        return {"documents": await asyncio.to_thread(store.list_documents, x_zibi_device)}

    @app.delete("/documents/{doc_id}")
    async def delete_document(
        doc_id: int, x_zibi_device: str = Header(), x_zibi_token: str | None = Header(default=None)
    ) -> dict:
        check_token(x_zibi_token)
        if not await asyncio.to_thread(store.delete_document, x_zibi_device, doc_id):
            raise HTTPException(status_code=404, detail="No such document.")
        return {"ok": True}

    async def run_turn(session: Session, text: str) -> None:
        async with session.run_lock:
            try:
                reply = await agent.run(session, session.registry, session.history, text, await make_context(session))
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
                    # typing while a card is waiting answers it (ask) or declines it (confirm)
                    if session.consume_typed_text(text):
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
