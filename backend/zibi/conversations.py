"""Conversation memory per DEVICE (not per WebSocket), so reconnecting never makes Zibi forget.

History lives in memory for speed and is saved to SQLite after every turn, so it also survives server restarts
(uvicorn --reload restarts on every code change).
"""
from __future__ import annotations

import asyncio
import json
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any

MAX_MESSAGES = 30          # recent messages kept; older ones are dropped at a turn boundary
KEEP_TOOL_CHARS = 400      # tool outputs from earlier turns are shortened to save tokens


def trim_history(history: list[dict[str, Any]], max_messages: int = MAX_MESSAGES,
                 keep_tool_chars: int = KEEP_TOOL_CHARS) -> None:
    """In place. Never starts the history in the middle of a tool call/response pair."""
    if len(history) > max_messages:
        cut = len(history) - max_messages
        while cut < len(history) and history[cut].get("role") != "user":
            cut += 1
        del history[:cut]
    last_user = max((i for i, m in enumerate(history) if m.get("role") == "user"), default=0)
    for m in history[:last_user]:
        content = m.get("content")
        if m.get("role") == "tool" and isinstance(content, str) and len(content) > keep_tool_chars:
            m["content"] = content[:keep_tool_chars] + "…"


class Conversations:
    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self._conn()) as c, c:
            c.execute(
                "CREATE TABLE IF NOT EXISTS conversations ("
                "device_id TEXT PRIMARY KEY, messages TEXT NOT NULL, "
                "updated_at TEXT NOT NULL DEFAULT (datetime('now')))"
            )
        self._cache: dict[str, list[dict[str, Any]]] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    def _conn(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)

    def history(self, device_id: str) -> list[dict[str, Any]]:
        """The live history list for this device. Every session of the device shares the same list."""
        if device_id not in self._cache:
            with closing(self._conn()) as c:
                row = c.execute("SELECT messages FROM conversations WHERE device_id = ?", (device_id,)).fetchone()
            self._cache[device_id] = json.loads(row[0]) if row else []
        return self._cache[device_id]

    def lock(self, device_id: str) -> asyncio.Lock:
        return self._locks.setdefault(device_id, asyncio.Lock())

    def save(self, device_id: str) -> None:
        history = self.history(device_id)
        trim_history(history)
        with closing(self._conn()) as c, c:
            c.execute(
                "INSERT INTO conversations(device_id, messages) VALUES (?, ?) "
                "ON CONFLICT(device_id) DO UPDATE SET messages = excluded.messages, updated_at = datetime('now')",
                (device_id, json.dumps(history, ensure_ascii=False)),
            )

    def clear(self, device_id: str) -> None:
        self.history(device_id).clear()  # in place, so live sessions see it too
        self.save(device_id)
