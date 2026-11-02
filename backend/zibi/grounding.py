"""Facts must come from the user's own words. A tool can demand a quote ('grounded' argument) and we check that it
really appears in the recent conversation, so the model cannot save things nobody said."""
from __future__ import annotations

import json
import re
from typing import Any

WINDOW = 16  # how many recent messages count as "this conversation"


def normalize(text: str) -> str:
    return " ".join(re.findall(r"\w+", text.lower()))


def evidence_sources(history: list[dict[str, Any]], limit: int = WINDOW) -> list[str]:
    """What the USER said recently: their messages and their answers to ask_user. The assistant's own words never count."""
    recent = history[-limit:]
    ask_ids = {
        c["id"]
        for m in recent if m.get("role") == "assistant"
        for c in (m.get("tool_calls") or [])
        if c.get("function", {}).get("name") == "ask_user"
    }
    out: list[str] = []
    for m in recent:
        if m.get("role") == "user" and isinstance(m.get("content"), str):
            out.append(m["content"])
        elif m.get("role") == "tool" and m.get("tool_call_id") in ask_ids:
            try:
                answer = json.loads(m["content"]).get("result", {}).get("answer")
            except (ValueError, AttributeError, TypeError):
                answer = None
            if answer:
                out.append(str(answer))
    return out


def is_grounded(evidence: str, history: list[dict[str, Any]]) -> bool:
    ev = normalize(evidence)
    if len(ev) < 3:
        return False
    tokens = ev.split()
    for source in evidence_sources(history):
        src = normalize(source)
        if f" {ev} " in f" {src} ":
            return True
        if len(tokens) >= 4:  # tolerate a small slip when quoting a longer sentence
            words = set(src.split())
            if sum(t in words for t in tokens) / len(tokens) >= 0.9:
                return True
    return False
