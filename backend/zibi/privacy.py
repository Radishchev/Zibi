"""Finds and masks sensitive values: phone numbers, e-mail addresses, card and ID numbers, passwords.

The PHONE is the main privacy boundary: it replaces these values with placeholders before anything is sent here. This
module is the server's second line of defence: if something sensitive still arrives (or sits in a document), it is
masked irreversibly before it can reach the AI model. The same rules exist in Kotlin (privacy/Redactor.kt); both are
tested with the same cases.

Honest limit: this recognises common PATTERNS. It cannot know that a free-text sentence is private. That is why facts
can also be marked private by hand.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
CARD = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")
AADHAAR = re.compile(r"(?<!\d[ -])\b\d{4}[ -]\d{4}[ -]\d{4}\b(?![ -]\d)")  # not part of a longer digit group
PAN = re.compile(r"\b[A-Z]{5}\d{4}[A-Z]\b")
SSN = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
KEYWORD = re.compile(r"(?i)\b(?:password|passcode|pin|otp|cvv|secret|token|api[ _-]?key)\b(\s*(?:is|=|:)\s*|\s+)[\"']?([^\s\"']{3,})")
LONG_TOKEN = re.compile(r"\b[A-Za-z0-9_\-]{28,}\b")
PHONE = re.compile(r"(?<![\w:./-])\+?\(?\d[\d ()\-]{6,}\d(?![\w:/-])")
DATE = re.compile(r"\d{4}-\d{2}-\d{2}|\d{1,2}-\d{1,2}-\d{2,4}")


@dataclass(frozen=True)
class Span:
    start: int
    end: int
    kind: str


def luhn(digits: str) -> bool:
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def looks_like_slug(token: str) -> bool:
    """File names and slugs (Lab_Report_2026_10_30_Final_v2) are words and short numbers joined by _ or -.
    Real keys are random mixed characters. Slugs must not be mistaken for secrets."""
    parts = [p for p in re.split(r"[_-]", token) if p]
    if len(parts) < 3:
        return False
    plain = sum(1 for p in parts if p.isalpha() or (p.isdigit() and len(p) <= 4))
    return plain / len(parts) >= 0.6


def is_phone(candidate: str) -> bool:
    c = candidate.strip()
    digits = re.sub(r"\D", "", c)
    if not 8 <= len(digits) <= 15:
        return False
    if DATE.fullmatch(c):
        return False
    groups = re.findall(r"\d+", c)
    if sum(1 for g in groups if len(g) == 1) > 1:  # "3 5 7 9 11 13": a list of numbers, not a phone number
        return False
    return bool(re.search(r"[ ()+\-]", c)) or len(digits) >= 10


def find_sensitive(text: str) -> list[Span]:
    spans: list[Span] = []

    def add(start: int, end: int, kind: str) -> None:
        if all(end <= s.start or start >= s.end for s in spans):
            spans.append(Span(start, end, kind))

    for m in EMAIL.finditer(text):
        add(m.start(), m.end(), "EMAIL")
    for m in CARD.finditer(text):
        digits = re.sub(r"\D", "", m.group())
        if 13 <= len(digits) <= 19 and luhn(digits):
            add(m.start(), m.end(), "CARD")
    for rx in (AADHAAR, PAN, SSN):
        for m in rx.finditer(text):
            add(m.start(), m.end(), "ID")
    for m in KEYWORD.finditer(text):
        connector, value = m.group(1), m.group(2)
        if re.search(r"[=:]|\bis\b", connector, re.I) or re.search(r"\d", value):
            add(m.start(2), m.end(2), "SECRET")
    for m in LONG_TOKEN.finditer(text):
        t = m.group()
        if re.search(r"[a-z]", t) and re.search(r"[A-Z]", t) and re.search(r"\d", t) and not looks_like_slug(t):
            add(m.start(), m.end(), "SECRET")
    for m in PHONE.finditer(text):
        if is_phone(m.group()):
            add(m.start(), m.end(), "PHONE")
    return sorted(spans, key=lambda s: s.start)


def redact(text: str) -> tuple[str, dict[str, int]]:
    """Returns the text with sensitive spans replaced by [KIND], and how many of each kind were found."""
    out: list[str] = []
    pos = 0
    counts: dict[str, int] = {}
    for s in find_sensitive(text):
        out.append(text[pos:s.start])
        out.append(f"[{s.kind}]")
        pos = s.end
        counts[s.kind] = counts.get(s.kind, 0) + 1
    out.append(text[pos:])
    return "".join(out), counts


def redact_json(value: Any) -> Any:
    if isinstance(value, str):
        return redact(value)[0]
    if isinstance(value, list):
        return [redact_json(v) for v in value]
    if isinstance(value, dict):
        return {k: redact_json(v) for k, v in value.items()}
    return value
