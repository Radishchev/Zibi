from datetime import datetime
from zoneinfo import ZoneInfo

from zibi.context import AgentContext, build_system_prompt, format_profile, schedule_hint

TT = [
    {"day": "Friday", "start": "10:00", "end": "12:00", "title": "Data Science"},
    {"day": "Monday", "start": "14:00", "end": "16:00", "title": "Machine Learning"},
]


def at(y, m, d, hh, mm):
    return datetime(y, m, d, hh, mm, tzinfo=ZoneInfo("UTC"))


def test_in_class_now():
    assert "In class now: Data Science until 12:00" in schedule_hint(TT, at(2026, 10, 23, 10, 30))  # a Friday


def test_next_class_today_tomorrow_and_later():
    assert schedule_hint(TT, at(2026, 10, 23, 8, 0)) == "Next class: Data Science, today 10:00."
    assert schedule_hint(TT, at(2026, 10, 23, 13, 0)) == "Next class: Machine Learning, Monday 14:00."
    assert schedule_hint(TT, at(2026, 10, 25, 20, 0)) == "Next class: Machine Learning, tomorrow 14:00."


def test_hint_is_empty_without_a_usable_timetable():
    assert schedule_hint([], at(2026, 10, 23, 9, 0)) == ""
    assert schedule_hint([{"day": "Someday", "start": "x", "end": "y"}], at(2026, 10, 23, 9, 0)) == ""


def test_abbreviated_days_work():
    tt = [{"day": "Fri", "start": "10:00", "end": "11:00", "title": "DS"}]
    assert "In class now: DS" in schedule_hint(tt, at(2026, 10, 23, 10, 5))


def test_profile_is_grouped_and_capped():
    facts = [
        {"category": "preference", "key": "alarm_style", "value": "heavy sleeper: 12 alarms, 2 min apart"},
        {"category": "routine", "key": "wake_time", "value": "07:00"},
    ]
    text = format_profile(facts)
    assert text.index("wake_time") < text.index("alarm_style")  # routine comes before preferences
    many = [{"category": "other", "key": f"k{i}", "value": "v" * 150} for i in range(100)]
    capped = format_profile(many, max_chars=600)
    assert len(capped) < 800 and "more not shown" in capped
    assert format_profile([]).strip() == "(nothing yet)"


def test_prompt_contains_profile_schedule_and_stays_small():
    ctx = AgentContext(
        now=at(2026, 10, 23, 10, 30), timetable=TT,
        profile=[{"category": "preference", "key": "alarm_style", "value": "12 alarms, 2 min apart"}],
        document_names=["ml.pdf"],
    )
    p = build_system_prompt(ctx)
    for needle in ("alarm_style", "In class now: Data Science", "ml.pdf", "set_alarm_series", "update_calendar_event",
                   "remember_fact", "all_day=true", "Context first", "Never interview"):
        assert needle in p
    assert len(p) < 5200  # ~1300 tokens including the profile; this is re-sent on every model call
