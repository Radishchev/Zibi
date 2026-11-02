"""Builds what the model sees on every turn: rules + who the user is + the current situation.

Everything here is re-sent on EVERY model call, so it is kept compact (rate limits are measured in tokens).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, time
from typing import Any

from .privacy import redact

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
    bridge: Any = None  # set by the agent each turn so cloud tools like ask_user can reach the phone
    owner: str = ""  # the device id; scopes the document library
    documents: Any = None  # DocumentStore
    document_names: list[str] = field(default_factory=list)
    questions_asked: int = 0  # ask_user calls so far this turn
    query: str = ""  # the user's current message, used to pick the most relevant facts


SYSTEM_TEMPLATE = """You are Zibi, a personal assistant on the user's Android phone.
Style: brief and friendly, 1-2 sentences. Do what the user asks, exactly as they said it: never refuse, shrink or replace a request because of limits you imagine. If a tool rejects something, say so in one sentence and offer the nearest option; never silently change what they asked for. Act with tools; never claim something is done unless a tool result says so. Never compute dates or times yourself (use resolve_date or resolve_datetime). Never invent a time, date or recipient: if something essential is missing, ask ONE short question. The system handles approvals for risky actions.

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
- "Remind me to email/message X about Y" is ALWAYS a save_message_draft quick action (tap it in the Quick tab, the mail/messaging app opens ready to send) — NEVER create_reminder for something with a recipient (create_reminder is only for no-recipient things: deadlines, chores). app: email, whatsapp or sms by the user's saved preferences (e.g. boss_channel=whatsapp, <context>_email_app=outlook; keys are whatever the user uses); if none fits, ask once or leave app out. Message now -> send_message or send_email, same rules; omit 'app' for email's default mail app. Never invent an email address or recipient.
- End with ONE sentence saying what you saved.

Time:
- Fixed events (exam, class, meeting, appointment) -> create_calendar_event. It needs a start_time: ask if unknown.
- Deadlines/submissions -> create_reminder, then if it matters ask_user ONCE ("Add to calendar" / "No thanks"); if yes, create_calendar_event with all_day=true unless a time is known.
- Chores ("clip my nails") -> reminder only, no calendar question.
- Timed reminders -> first call resolve_datetime with the user's full time phrase (including relative times like "in 1 minute" or periods like "this evening"), then pass its date and time into create_reminder. Never invent or calculate the date/time yourself.
- To change an event you created, use update_calendar_event (the event_id is in the earlier tool result).
- Alarms: set EXACTLY what the user describes, however many and however spaced (set_alarm for one; set_alarms for several: a list of times, every-N-minutes ranges, or random spacing). What they say beats their saved alarm_style; use alarm_style only when they don't say.

Documents: for questions about them call search_documents with short keywords (retry with other words if empty); answer ONLY from the passages, briefly, citing (name, p.N); if not found say so. BIG requests (teach a topic, summarise a whole document, essays, study plans) are not yours to do: call propose_handoff with a complete self-contained prompt (goal, level, what the files are, format) and the exact file names in `documents`, then reply with ONE sentence; the user chooses where to send it.

Private values: text like [PHONE_1], [EMAIL_1] or [PRIVATE_2] stands for a real value that stays on the phone. You can't see it and must never guess it. Pass the placeholder unchanged to tools (the phone fills in the real value) and don't ask the user to repeat it.

Context first: your job is to know this user so they never have to configure anything. Use "What you know about the user" for every choice, and never ask for something you already know.
- If a task depends on a personal preference you don't have, ask ONE short open question (ask_user with no options) as part of that task. When they answer, ACT on it at once (don't ask follow-ups for things you can decide) and save it with remember_fact under a key from the user's own words (university_email_app, boss_channel, alarm_style, messaging_app or <name>_messaging_app, reminder_lead, event_length, wake_time, sleep_time, meal_times, hobbies, goals). Setups differ (three work accounts, no university): never assume a fixed set of keys.
- Never interview. Ask only what the current task needs, and never ask what the user already said or what you can reasonably decide yourself.
- Save lasting facts they state with remember_fact, using a short snake_case key and their exact words as `evidence`. Reuse keys for updates and save choices they make. Timetable -> update_timetable.
- When facts change, remember_fact the new value, then forget_fact or archive_facts what's false or finished; set valid_until when known. Use recall_memory for archived facts.
- Health, money, IDs, passwords and similar: save only if the user asks, with private=true.
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


def _tokens(text: str) -> set[str]:
    return {t for t in re.findall(r"\w+", text.lower().replace("_", " ")) if len(t) > 2}


def _fact_line(f: dict[str, Any]) -> str:
    value = redact(str(f.get("value", ""))[:200])[0]  # defence in depth: the phone already hid private values
    note = " (not confirmed lately)" if f.get("stale") else ""
    return f"  - {f.get('key', '?')}: {value}{note}".replace("\n", " ")


def format_profile(facts: list[dict[str, Any]], max_chars: int = MAX_PROFILE_CHARS, query: str = "") -> str:
    """The facts that fit the budget, most relevant to the user's current message first, grouped by category."""
    if not facts:
        return "  (nothing yet)"
    q = _tokens(query)

    def score(f: dict[str, Any]) -> float:
        s = 2.0 * len(q & _tokens(f"{f.get('key', '')} {f.get('value', '')}"))
        return s + (0.5 if f.get("category") in ("routine", "preference") else 0.0)

    chosen: set[int] = set()
    used = 0
    for i in sorted(range(len(facts)), key=lambda i: (-score(facts[i]), i)):
        line = _fact_line(facts[i])
        if used + len(line) + 1 <= max_chars:
            chosen.add(i)
            used += len(line) + 1
    by_cat: dict[str, list[str]] = {}
    for i in sorted(chosen):
        by_cat.setdefault(str(facts[i].get("category", "other")), []).append(_fact_line(facts[i]))
    order = sorted(by_cat, key=lambda c: PROFILE_ORDER.index(c) if c in PROFILE_ORDER else len(PROFILE_ORDER))
    lines = [line for cat in order for line in by_cat[cat]]
    if len(chosen) < len(facts):
        lines.append(f"  (+{len(facts) - len(chosen)} more not shown)")
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
        profile=format_profile(ctx.profile, query=ctx.query),
        docs=docs,
    )
