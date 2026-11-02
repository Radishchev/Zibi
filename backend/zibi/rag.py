"""Document library: PDF text extraction, chunking, and keyword search (SQLite FTS5 + BM25).

Why keyword search first: it needs no model download, runs anywhere, and works well for notes and slides where
exact terms matter. The agent also rewrites the user's question into search keywords itself. Vector search can be
added later behind DocumentStore.search (hybrid ranking) once a test set shows keyword recall is not enough.
"""
from __future__ import annotations

import io
import re
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any

from pypdf import PdfReader

MAX_UPLOAD_BYTES = 50 * 1024 * 1024
MAX_PAGES = 2000

STOPWORDS = frozenset(
    """a an and are as at be but by can did do does for from had has have how i in is it its me my of on or our
    so than that the their them then there these they this to us was we were what when where which who why will with
    would you your tell about explain say said teacher""".split()
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner TEXT NOT NULL,
    name TEXT NOT NULL,
    pages INTEGER NOT NULL,
    chunks INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE VIRTUAL TABLE IF NOT EXISTS chunks USING fts5(
    text, doc_id UNINDEXED, owner UNINDEXED, page UNINDEXED,
    tokenize = 'porter unicode61'
);
"""


class PdfProblem(Exception):
    """A PDF we cannot index. The message is safe to show to the user."""


def chunk_text(text: str, max_chars: int = 1000, overlap: int = 150) -> list[str]:
    """Split page text into chunks of about max_chars on sentence boundaries, with a little overlap."""
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return []
    if len(text) <= max_chars:
        return [text]

    sentences: list[str] = []
    for s in re.split(r"(?<=[.!?])\s+", text):
        while len(s) > max_chars:
            sentences.append(s[:max_chars])
            s = s[max_chars:]
        if s:
            sentences.append(s)

    chunks: list[str] = []
    cur: list[str] = []
    cur_len = 0
    for s in sentences:
        if cur and cur_len + len(s) + 1 > max_chars:
            chunks.append(" ".join(cur))
            carry: list[str] = []
            carry_len = 0
            for prev in reversed(cur):
                if carry_len + len(prev) > overlap:
                    break
                carry.insert(0, prev)
                carry_len += len(prev) + 1
            cur, cur_len = carry, carry_len
        cur.append(s)
        cur_len += len(s) + 1
    if cur:
        chunks.append(" ".join(cur))
    return chunks


def extract_pages(data: bytes) -> list[str]:
    """Text of each page. Raises PdfProblem with a user-safe message when the file can't be used."""
    try:
        reader = PdfReader(io.BytesIO(data))
    except Exception:
        raise PdfProblem("That file doesn't look like a readable PDF.") from None
    if reader.is_encrypted:
        try:
            ok = reader.decrypt("")
        except Exception:
            ok = 0
        if not ok:
            raise PdfProblem("This PDF is password protected.")
    pages: list[str] = []
    for page in reader.pages[:MAX_PAGES]:
        try:
            pages.append(page.extract_text() or "")
        except Exception:
            pages.append("")
    return pages


def search_terms(query: str) -> list[str]:
    seen: list[str] = []
    for t in re.findall(r"\w+", query.lower()):
        if t not in STOPWORDS and t not in seen and len(t) > 1:
            seen.append(t)
    return seen[:12]


class DocumentStore:
    def __init__(self, path: str | Path) -> None:
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self._conn()) as c, c:
            c.executescript(SCHEMA)

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        return conn

    def add_document(self, owner: str, name: str, pages: int, page_chunks: list[tuple[int, str]]) -> int:
        with closing(self._conn()) as c, c:
            cur = c.execute(
                "INSERT INTO documents(owner, name, pages, chunks) VALUES (?, ?, ?, ?)",
                (owner, name, pages, len(page_chunks)),
            )
            doc_id = int(cur.lastrowid)
            c.executemany(
                "INSERT INTO chunks(text, doc_id, owner, page) VALUES (?, ?, ?, ?)",
                [(text, doc_id, owner, page) for page, text in page_chunks],
            )
        return doc_id

    def list_documents(self, owner: str) -> list[dict[str, Any]]:
        with closing(self._conn()) as c:
            rows = c.execute(
                "SELECT id, name, pages, chunks FROM documents WHERE owner = ? ORDER BY id DESC", (owner,)
            ).fetchall()
        return [dict(r) for r in rows]

    def delete_document(self, owner: str, doc_id: int) -> bool:
        with closing(self._conn()) as c, c:
            owned = c.execute(
                "SELECT 1 FROM documents WHERE id = ? AND owner = ?", (doc_id, owner)
            ).fetchone()
            if not owned:
                return False
            c.execute("DELETE FROM chunks WHERE doc_id = ?", (doc_id,))
            c.execute("DELETE FROM documents WHERE id = ?", (doc_id,))
        return True

    def search(
        self, owner: str, query: str, limit: int = 5, document: str | None = None
    ) -> list[dict[str, Any]]:
        terms = search_terms(query)
        if not terms:
            return []
        match = " OR ".join(f'"{t}"' for t in terms)
        doc_filter = ""
        params: list[Any] = [match, owner]
        if document:
            doc_filter = "AND doc_id IN (SELECT id FROM documents WHERE owner = ? AND lower(name) LIKE ?)"
            params += [owner, f"%{document.lower()}%"]
        params.append(limit)
        sql = f"""
            SELECT d.name AS document, h.page AS page, h.text AS text, h.score AS score
            FROM (
                SELECT text, page, doc_id, bm25(chunks) AS score
                FROM chunks
                WHERE chunks MATCH ? AND owner = ? {doc_filter}
                ORDER BY score
                LIMIT ?
            ) h
            JOIN documents d ON d.id = h.doc_id
            ORDER BY h.score
        """
        with closing(self._conn()) as c:
            rows = c.execute(sql, params).fetchall()
        return [{"document": r["document"], "page": int(r["page"]), "text": r["text"], "score": r["score"]} for r in rows]


def ingest_pdf(store: DocumentStore, owner: str, name: str, data: bytes) -> dict[str, Any]:
    pages = extract_pages(data)
    if sum(len(p.strip()) for p in pages) < 20:
        raise PdfProblem(
            "This PDF has no selectable text (it is probably a scan). Text recognition isn't supported yet."
        )
    page_chunks = [(i + 1, chunk) for i, text in enumerate(pages) for chunk in chunk_text(text)]
    clean_name = Path(name).name.strip()[:200] or "document.pdf"
    doc_id = store.add_document(owner, clean_name, len(pages), page_chunks)
    return {"doc_id": doc_id, "name": clean_name, "pages": len(pages), "chunks": len(page_chunks)}
