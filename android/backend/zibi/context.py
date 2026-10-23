"""Builds what the model sees on every turn: rules + current situation."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass
class AgentContext:
    now: datetime  # timezone-aware, in the user's timezone
    timezone: str = "UTC"
    timetable: list[dict[str, Any]] = field(default_factory=list)
    preferences: dict[str, Any] = field(default_factory=dict)


SYSTEM_TEMPLATE = """You are Zibi, a personal assistant living on the user's Android phone.

Rules:
- Be brief and friendly. One or two sentences unless asked for more.
- Use tools to act. Never claim you did something unless a tool result confirmed it.
- NEVER compute dates yourself. Call resolve_date for any relative date ("tomorrow", "next friday").
- If something essential is missing (which day? what time?), ask ONE short question instead of guessing.
- If the user gives several requests in one message, handle each one with its own tool call.
- Some actions need the user's approval; the system asks them automatically. If the user declines, accept it and move on.
- After tools succeed, confirm in one short sentence what you did.

Current situation:
- Now: {now} ({tz})
- Timetable:
{timetable}
- User preferences: {prefs}
"""


def build_system_prompt(ctx: AgentContext) -> str:
    if ctx.timetable:
        lines = "\n".join(
            f"  - {e.get('day', '?')} {e.get('start', '?')}-{e.get('end', '?')}: {e.get('title', '?')}"
            for e in ctx.timetable
        )
    else:
        lines = "  (none provided)"
    return SYSTEM_TEMPLATE.format(
        now=ctx.now.strftime("%A, %Y-%m-%d %H:%M"),
        tz=ctx.timezone,
        timetable=lines,
        prefs=ctx.preferences or "(none)",
    )
