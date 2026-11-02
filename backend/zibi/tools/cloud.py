"""Cloud tools: run on the server. Phase 0 has one: deterministic date resolution."""
from __future__ import annotations

import asyncio
import re
from datetime import date, datetime, time, timedelta
from typing import Any

from ..privacy import redact
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


def parse_datetime_phrase(phrase: str, now: datetime) -> datetime:
    p = re.sub(r"\s+", " ", phrase.strip().lower())
    if m := re.fullmatch(r"in (\d+) (minutes?|hours?)", p):
        amount = int(m.group(1))
        delta = timedelta(minutes=amount) if m.group(2).startswith("minute") else timedelta(hours=amount)
        return now + delta

    periods = {
        "morning": time(9),
        "afternoon": time(15),
        "evening": time(18),
        "tonight": time(20),
    }
    if p in {"morning", "this morning", "in the morning", "afternoon", "this afternoon", "in the afternoon",
             "evening", "this evening", "in the evening", "tonight"}:
        period = next(word for word in periods if word in p)
        target = datetime.combine(now.date(), periods[period], tzinfo=now.tzinfo)
        if target <= now:
            target += timedelta(days=1)
        return target

    if " at " in p:
        date_phrase, time_phrase = p.rsplit(" at ", 1)
        day = parse_date_phrase(date_phrase, now.date())
        if m := re.fullmatch(r"(\d{1,2}):(\d{2})", time_phrase):
            clock = time(int(m.group(1)), int(m.group(2)))
        elif m := re.fullmatch(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)", time_phrase):
            hour = int(m.group(1)) % 12 + (12 if m.group(3) == "pm" else 0)
            clock = time(hour, int(m.group(2) or 0))
        else:
            raise ValueError(f"cannot understand time phrase: {time_phrase!r}")
        return datetime.combine(day, clock, tzinfo=now.tzinfo)

    raise ValueError(f"cannot understand date and time phrase: {phrase!r}")


async def _resolve_datetime(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    try:
        resolved = parse_datetime_phrase(args["phrase"], ctx.now)
    except ValueError as e:
        return {"ok": False, "error": f"{e}. Ask the user for an exact date and time."}
    return {
        "ok": True,
        "result": {
            "date": resolved.date().isoformat(),
            "time": resolved.strftime("%H:%M"),
            "datetime": resolved.isoformat(timespec="minutes"),
        },
    }


async def _resolve_date(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    try:
        d = parse_date_phrase(args["phrase"], ctx.now.date())
    except ValueError as e:
        return {"ok": False, "error": f"{e}. Ask the user for an exact date."}
    return {"ok": True, "result": {"date": d.isoformat(), "weekday": WEEKDAYS[d.weekday()]}}


MAX_QUESTIONS_PER_TURN = 2


async def _ask_user(args: dict[str, Any], ctx: Any) -> dict[str, Any]:
    if getattr(ctx, "questions_asked", 0) >= MAX_QUESTIONS_PER_TURN:
        return {
            "ok": False,
            "error": "You have already asked enough questions this turn. Use what the user told you and act now, "
                     "choosing sensible defaults for anything that is missing.",
        }
    ctx.questions_asked = getattr(ctx, "questions_asked", 0) + 1
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
    passages = [{"document": h["document"], "page": h["page"], "text": redact(h["text"][:1200])[0]} for h in hits]
    return {"ok": True, "result": {"passages": passages}}


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
                name="resolve_datetime",
                description=(
                    "Convert a reminder time phrase such as 'in 1 minute', 'this evening', or 'tomorrow at 5pm' "
                    "into exact local date and HH:MM values. ALWAYS use this for timed reminders instead of "
                    "computing dates or times yourself. 'Evening' means 18:00."
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
            fn=_resolve_datetime,
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
