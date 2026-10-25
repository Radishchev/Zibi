"""Cloud tools: run on the server. Phase 0 has one: deterministic date resolution."""
from __future__ import annotations

import asyncio
import re
from datetime import date, timedelta
from typing import Any

from .base import CloudTool, Risk, ToolSpec
from .registry import ToolRegistry

WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def parse_date_phrase(phrase: str, today: date) -> date:
    """Resolve a relative date phrase in CODE (LLMs are unreliable at date arithmetic).

    A bare or 'next' weekday means the first such day strictly after today.
    """
    p = re.sub(r"\s+", " ", phrase.strip().lower())
    if p == "today":
        return today
    if p == "tomorrow":
        return today + timedelta(days=1)
    if p == "day after tomorrow":
        return today + timedelta(days=2)
    if m := re.fullmatch(r"in (\d+) days?", p):
        return today + timedelta(days=int(m.group(1)))
    if m := re.fullmatch(r"in (\d+) weeks?", p):
        return today + timedelta(weeks=int(m.group(1)))
    if m := re.fullmatch(r"(?:next |this |on )?(" + "|".join(WEEKDAYS) + ")", p):
        target = WEEKDAYS.index(m.group(1))
        delta = (target - today.weekday()) % 7 or 7
        return today + timedelta(days=delta)
    try:
        return date.fromisoformat(p)
    except ValueError:
        raise ValueError(f"cannot understand date phrase: {phrase!r}") from None


async def _resolve_date(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    try:
        d = parse_date_phrase(args["phrase"], ctx.now.date())
    except ValueError as e:
        return {"ok": False, "error": f"{e}. Ask the user for an exact date."}
    return {"ok": True, "result": {"date": d.isoformat(), "weekday": WEEKDAYS[d.weekday()]}}


async def _ask_user(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    bridge = getattr(ctx, "bridge", None)
    if bridge is None:
        return {"ok": False, "error": "cannot ask the user right now"}
    answer = await bridge.ask(args["question"], list(args.get("options") or []))
    if answer is None:
        return {"ok": False, "error": "the user did not answer"}
    return {"ok": True, "result": {"answer": answer}}


async def _search_documents(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    store = getattr(ctx, "documents", None)
    if store is None:
        return {"ok": False, "error": "the document library is not available"}
    hits = await asyncio.to_thread(store.search, ctx.owner, args["query"], 5, args.get("document"))
    if not hits:
        return {"ok": True, "result": {"passages": [], "note": "nothing relevant found in the user's documents"}}
    return {
        "ok": True,
        "result": {
            "passages": [
                {"document": h["document"], "page": h["page"], "text": h["text"][:1200]} for h in hits
            ]
        },
    }


def build_default_registry() -> ToolRegistry:
    r = ToolRegistry()
    r.register_cloud(
        CloudTool(
            spec=ToolSpec(
                name="resolve_date",
                description=(
                    "Convert a relative date phrase (today, tomorrow, next friday, in 3 days, "
                    "in 2 weeks) into an exact ISO date. ALWAYS use this instead of computing dates yourself."
                ),
                parameters={
                    "type": "object",
                    "properties": {"phrase": {"type": "string"}},
                    "required": ["phrase"],
                    "additionalProperties": False,
                },
                risk=Risk.READ,
                location="cloud",
            ),
            fn=_resolve_date,
        )
    )
    r.register_cloud(
        CloudTool(
            spec=ToolSpec(
                name="ask_user",
                description=(
                    "Ask the user ONE short question and wait for the answer. Give 2-4 options when the choices "
                    "are obvious; leave options empty for an open question (the user types the answer). The answer "
                    "may also be free text the user typed instead of tapping an option."
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "question": {"type": "string"},
                        "options": {"type": "array", "items": {"type": "string"}, "maxItems": 4},
                    },
                    "required": ["question"],
                    "additionalProperties": False,
                },
                risk=Risk.READ,
                location="cloud",
            ),
            fn=_ask_user,
        )
    )
    r.register_cloud(
        CloudTool(
            spec=ToolSpec(
                name="search_documents",
                description=(
                    "Search the user's uploaded documents (PDFs) for passages relevant to a query. Use short "
                    "keyword queries. Optionally restrict to one document by part of its name. Returns the best "
                    "matching passages with document name and page."
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "keywords, e.g. 'gradient descent learning rate'"},
                        "document": {"type": "string", "description": "optional part of a document name"},
                    },
                    "required": ["query"],
                    "additionalProperties": False,
                },
                risk=Risk.READ,
                location="cloud",
            ),
            fn=_search_documents,
        )
    )
    return r
