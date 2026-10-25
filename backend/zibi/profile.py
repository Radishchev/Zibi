"""User profile & timetable persistence per device in SQLite.

Learned facts (habits, preferences, routines, people) and recurring timetable
survive server restarts and reconnects.
"""
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

SCHEMA = """
CREATE TABLE IF NOT EXISTS profile (
    device_id TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'other',
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, key)
);
CREATE TABLE IF NOT EXISTS timetable (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    day TEXT NOT NULL,
    start TEXT NOT NULL,
    end TEXT NOT NULL,
    title TEXT NOT NULL
);
"""


class ProfileStore:
    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        self._is_mem = self.path == ":memory:"
        if self._is_mem:
            self._conn_inst: sqlite3.Connection | None = sqlite3.connect(":memory:", check_same_thread=False)
            self._conn_inst.row_factory = sqlite3.Row
        else:
            self._conn_inst = None
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)

        with self._get_conn() as c:
            c.executescript(SCHEMA)

    @contextmanager
    def _get_conn(self) -> Iterator[sqlite3.Connection]:
        if self._is_mem and self._conn_inst is not None:
            yield self._conn_inst
        else:
            conn = sqlite3.connect(self.path)
            conn.row_factory = sqlite3.Row
            try:
                yield conn
            finally:
                conn.close()

    # --- Profile facts --------------------------------------------------------

    def remember_fact(self, device_id: str, key: str, value: str, category: str = "other") -> None:
        key = key.strip().lower().replace(" ", "_")
        category = category.strip().lower() if category else "other"
        with self._get_conn() as c:
            with c:
                c.execute(
                    """
                    INSERT INTO profile (device_id, key, value, category, updated_at)
                    VALUES (?, ?, ?, ?, datetime('now'))
                    ON CONFLICT(device_id, key) DO UPDATE SET
                        value = excluded.value,
                        category = excluded.category,
                        updated_at = datetime('now')
                    """,
                    (device_id, key, str(value).strip(), category),
                )

    def get_profile(self, device_id: str) -> list[dict[str, Any]]:
        with self._get_conn() as c:
            rows = c.execute(
                "SELECT category, key, value FROM profile WHERE device_id = ? ORDER BY updated_at ASC",
                (device_id,),
            ).fetchall()
        return [dict(r) for r in rows]

    def delete_fact(self, device_id: str, key: str) -> bool:
        with self._get_conn() as c:
            with c:
                cur = c.execute("DELETE FROM profile WHERE device_id = ? AND key = ?", (device_id, key))
                return cur.rowcount > 0

    # --- Timetable ------------------------------------------------------------

    def set_timetable(self, device_id: str, entries: list[dict[str, Any]]) -> None:
        with self._get_conn() as c:
            with c:
                c.execute("DELETE FROM timetable WHERE device_id = ?", (device_id,))
                for e in entries:
                    c.execute(
                        "INSERT INTO timetable (device_id, day, start, end, title) VALUES (?, ?, ?, ?, ?)",
                        (
                            device_id,
                            str(e.get("day", "")).strip(),
                            str(e.get("start", "")).strip(),
                            str(e.get("end", "")).strip(),
                            str(e.get("title", "")).strip(),
                        ),
                    )

    def get_timetable(self, device_id: str) -> list[dict[str, Any]]:
        with self._get_conn() as c:
            rows = c.execute(
                "SELECT day, start, end, title FROM timetable WHERE device_id = ? ORDER BY id ASC",
                (device_id,),
            ).fetchall()
        return [dict(r) for r in rows]

