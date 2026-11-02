# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Zibi: personal assistant

Android app (Kotlin + Compose) + agent backend (Python/FastAPI). The agent reasons on the server; tools run on the phone (device tools) or the server (cloud tools). Communication is over a single WebSocket using the envelope protocol in `protocol/PROTOCOL.md`.

```
protocol/   JSON wire contract between phone and server
backend/    FastAPI + agent loop + tool registry (Python 3.11+)
android/    Kotlin + Jetpack Compose app
```

## Run the backend

```bash
cd backend
pip install -e ".[dev,llm]"
cp .env.example .env            # export GEMINI_API_KEY=... ZIBI_DEV_TOKEN=...
pytest                          # no API key needed, uses a scripted fake LLM
uvicorn zibi.main:app --reload  # ws://localhost:8000/ws
python scripts/phone_sim.py --token $ZIBI_DEV_TOKEN   # fake phone in terminal
```

Run a single test: `pytest tests/test_agent.py -k "test_name"`

## Run the Android app

Open `android/` in Android Studio, let Gradle sync. Start the backend first, then set the WebSocket URL in the app's Settings tab (`ws://10.0.2.2:8000/ws` for emulator). Unit tests: `./gradlew test`.

## Architecture

**Backend** (`backend/zibi/`):
- `agent.py` — the loop: LLM → tool calls → results → LLM (max 8 steps)
- `server.py` — FastAPI app, one WebSocket at `/ws`, REST endpoints for documents/conversation
- `session.py` — turns "call a device tool" into a WebSocket round trip with timeouts
- `tools/registry.py` — merges cloud tools with the phone's manifest from `hello`
- `policy.py` — decides which tools need a confirmation card (HIGH_STAKES)
- `llm/` — provider-agnostic interface (`base.py`), LiteLLM client, fake for tests
- `context.py` — builds the system prompt from session context (timetable, profile, preferences)
- `privacy.py` — server-side masking of sensitive values that slip through
- `grounding.py` — enforces that saved facts quote the user's own words
- `rag.py` — PDF document library (SQLite FTS)

**Android** (`android/app/src/main/java/com/zibi/app/`):
- `net/ZibiClient.kt` — WebSocket client, reconnect, dispatches tool calls
- `tools/` — ZibiTool implementations (reminders, messages, calendar, capture, profile)
- `privacy/` — Redactor replaces sensitive values with placeholders before sending; PrivacyVault stores real values encrypted with Android Keystore
- `ui/` — Compose screens: Chat, Quick, Reminders, Library, History, Profile, Settings

## Model configuration

Edit `backend/config/models.yaml` to switch models per role (orchestrator, fast, vision, stt). Currently uses Groq (`gpt-oss-120b`) with Gemini fallback. Export `GROQ_API_KEY` and `GEMINI_API_KEY`. Check what your keys can use: `python scripts/check_models.py --ping`.

## Privacy model (critical)

Sensitive values (phone numbers, emails, card/ID numbers, passwords) are replaced by placeholders **on the phone** before anything is sent to the server. The real values stay on the phone, encrypted with the Android Keystore. The server masks anything that still slips through (`privacy.py`). Facts are only saved with a quote of what the user actually said (`grounding.py`).

## Key protocol rules (protocol/PROTOCOL.md)

1. Phone sends `hello` first with its tool manifest; server replies `hello.ack`
2. Every `tool.call` gets exactly one `tool.result`/`tool.error` reply within 30s
3. HIGH_STAKES tools require a `confirm` card approval first
4. Tool arguments are validated server-side against JSON Schema
5. Dates are resolved in code (`resolve_date`), never by the model
