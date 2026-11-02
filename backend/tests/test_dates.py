from datetime import date, datetime, timezone

import pytest

from zibi.tools.cloud import parse_date_phrase, parse_datetime_phrase

FRI = date(2026, 10, 23)  # a Friday


@pytest.mark.parametrize("phrase,expected", [
    ("today", date(2026, 10, 23)),
    ("Tomorrow", date(2026, 10, 24)),
    ("day after tomorrow", date(2026, 10, 25)),
    ("in 3 days", date(2026, 10, 26)),
    ("in 2 weeks", date(2026, 11, 6)),
    ("next friday", date(2026, 10, 30)),   # strictly after today
    ("monday", date(2026, 10, 26)),
    ("on Sunday", date(2026, 10, 25)),
    ("2026-12-01", date(2026, 12, 1)),
])
def test_phrases(phrase, expected):
    assert parse_date_phrase(phrase, FRI) == expected


def test_garbage_raises():
    with pytest.raises(ValueError):
        parse_date_phrase("whenever", FRI)


def test_relative_minutes_resolve_to_exact_local_datetime():
    now = datetime(2026, 10, 23, 9, 0, tzinfo=timezone.utc)
    assert parse_datetime_phrase("in 1 minute", now) == datetime(2026, 10, 23, 9, 1, tzinfo=timezone.utc)


def test_evening_resolves_to_six_pm():
    now = datetime(2026, 10, 23, 9, 0, tzinfo=timezone.utc)
    assert parse_datetime_phrase("this evening", now) == datetime(2026, 10, 23, 18, 0, tzinfo=timezone.utc)


def test_evening_after_six_resolves_to_next_day():
    now = datetime(2026, 10, 23, 20, 0, tzinfo=timezone.utc)
    assert parse_datetime_phrase("this evening", now) == datetime(2026, 10, 24, 18, 0, tzinfo=timezone.utc)
