"""Cloud tools: run on the server. Phase 0 has one: deterministic date resolution."""
from __future__ import annotations

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
    return r
