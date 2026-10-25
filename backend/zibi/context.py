"""Builds what the model sees on every turn: rules + who the user is + the current situation.

Everything here is re-sent on EVERY model call, so it is kept compact (rate limits are measured in tokens).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, time
from typing import Any

DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
PROFILE_ORDER = ["routine", "study", "preference", "people", "other"]
MAX_PROFILE_CHARS = 1500


@dataclass
class AgentContext:
    now: datetime  # timezone-aware, in the user's timezone
    timezone: str = "UTC"
    timetable: list[dict[str, Any]] = field(default_factory=list)
    preferences: dict[str, Any] = field(default_factory=dict)
    profile: list[dict[str, Any]] = field(default_factory=list)  # [{category, key, value}]
    profile_store: Any = None  # ProfileStore
    bridge: Any = None  # set by the agent each turn so cloud tools like ask_user can reach the phone
    owner: str = ""  # the device id; scopes the document library
    documents: Any = None  # DocumentStore
    document_names: list[str] = field(default_factory=list)


SYSTEM_TEMPLATE = """You are Zibi, a personal assistant on the user's Android phone.
Style: brief and friendly, 1-2 sentences. Act with tools; never claim something is done unless a tool result says so. Never compute dates or times yourself (use resolve_date). Never invent a time, date or recipient: if something essential is missing, ask ONE short question. The system handles approvals for risky actions.

Now: {now} ({tz}). {hint}
Timetable:
{timetable}
What you know about the user:
{profile}
Documents in the library:
{docs}

Capturing thoughts (the user often dumps several at once): handle EVERY item in one go with the right tool and no questions.
- Things to buy/pack, named lists -> add_to_list (reuse the same list name, e.g. "Grocery list").
- To-do with no time -> add_task (project name in `project`).
- Something to tell someone later -> save_message_draft. Message now -> send_message (use the user's messaging_app if known).
- End with ONE sentence saying what you saved.

Time:
- Fixed events (exam, class, meeting, appointment) -> create_calendar_event. It needs a start_time: ask if unknown.
- Deadlines/submissions -> create_reminder, then if it matters ask_user ONCE ("Add to calendar" / "No thanks"); if yes, create_calendar_event with all_day=true unless a time is known.
- Chores ("clip my nails") -> reminder only, no calendar question.
- To change an event you created, use update_calendar_event (the event_id is in the earlier tool result).
- Alarms: follow the user's alarm_style. One alarm -> set_alarm; many alarms a few minutes apart -> set_alarm_series.

Documents: for questions about them call search_documents with short keywords (retry with other words if empty); answer ONLY from the passages, briefly, citing (name, p.N); if not found say so. BIG requests (teach a topic, summarise a whole document, essays, study plans) are not yours to do: call propose_handoff with a complete self-contained prompt (goal, level, what the files are, format) and the exact file names in `documents`, then reply with ONE sentence; the user chooses where to send it.

Context first: your job is to know this user so they never have to configure anything. Use "What you know about the user" for every choice, and never ask for something you already know.
- If a task depends on a personal preference you don't have, ask ONE short question as part of that task (ask_user with no options, or with options when the choices are obvious), then carry on and save the answer with remember_fact. Typical keys: alarm_style (before their first alarm), messaging_app or <name>_messaging_app (before their first message), reminder_lead (how early to remind about deadlines), event_length, wake_time, sleep_time, meal_times, hobbies, goals.
- Never interview. At most one such question per reply, and only when the current task needs the answer.
- Save lasting facts they mention in passing (routine, studies, hobbies, people, goals) with remember_fact, using a short stable snake_case key and reusing an existing key to update it. When they pick between options you offered, save the choice so you don't ask again. Timetable -> update_timetable.
- Don't save sensitive details (health, money, passwords) unless asked.
"""


def _day_index(name: str) -> int | None:
    t = name.strip().lower()
    if len(t) < 3:
        return None
    for i, d in enumerate(DAY_NAMES):
        if d.lower().startswith(t) or (len(t) <= 5 and d.lower()[:3] == t[:3]):
            return i
    return None


def _parse_hhmm(value: Any) -> time | None:
    try:
        h, m = str(value).strip().split(":")
        return time(int(h), int(m))
    except Exception:
        return None


def schedule_hint(timetable: list[dict[str, Any]], now: datetime) -> str:
    """'In class now: ...' or 'Next class: ...', computed in code from the timetable."""
    entries = []
    for e in timetable:
        d, s, en = _day_index(str(e.get("day", ""))), _parse_hhmm(e.get("start")), _parse_hhmm(e.get("end"))
        if d is None or s is None or en is None:
            continue
        entries.append((d, s, en, str(e.get("title", "?"))))
    today, t = now.weekday(), now.time().replace(second=0, microsecond=0)
    for d, s, en, title in entries:
        if d == today and s <= t < en:
            return f"In class now: {title} until {en:%H:%M}."
    best = None
    for d, s, _en, title in entries:
        ahead = (d - today) % 7
        if ahead == 0 and s <= t:
            ahead = 7
        if best is None or (ahead, s) < best[0]:
            best = ((ahead, s), title, d)
    if best is None:
        return ""
    (ahead, start), title, d = best
    when = "today" if ahead == 0 else "tomorrow" if ahead == 1 else DAY_NAMES[d]
    return f"Next class: {title}, {when} {start:%H:%M}."


def format_profile(facts: list[dict[str, Any]], max_chars: int = MAX_PROFILE_CHARS) -> str:
    if not facts:
        return "  (nothing yet)"
    by_cat: dict[str, list[dict[str, Any]]] = {}
    for f in facts:
        by_cat.setdefault(str(f.get("category", "other")), []).append(f)
    order = sorted(by_cat, key=lambda c: PROFILE_ORDER.index(c) if c in PROFILE_ORDER else len(PROFILE_ORDER))
    lines: list[str] = []
    used = shown = 0
    for cat in order:
        for f in by_cat[cat]:
            line = f"  - {f.get('key', '?')}: {str(f.get('value', ''))[:200]}".replace("\n", " ")
            if used + len(line) > max_chars:
                break
            lines.append(line)
            used += len(line) + 1
            shown += 1
    if shown < len(facts):
        lines.append(f"  (+{len(facts) - shown} more not shown)")
    return "\n".join(lines)


def build_system_prompt(ctx: AgentContext) -> str:
    if ctx.timetable:
        timetable = "\n".join(
            f"  - {e.get('day', '?')} {e.get('start', '?')}-{e.get('end', '?')}: {e.get('title', '?')}"
            for e in ctx.timetable
        )
    else:
        timetable = "  (none provided)"
    docs = "\n".join(f"  - {n}" for n in ctx.document_names[:30]) or "  (none added yet)"
    return SYSTEM_TEMPLATE.format(
        now=ctx.now.strftime("%A, %Y-%m-%d %H:%M"),
        tz=ctx.timezone,
        hint=schedule_hint(ctx.timetable, ctx.now),
        timetable=timetable,
        profile=format_profile(ctx.profile),
        docs=docs,
    )
