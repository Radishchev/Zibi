from datetime import date

import pytest

from zibi.tools.cloud import parse_date_phrase

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
