# Zibi: personal assistant

Android app (Kotlin, planned) + agent backend (Python, this repo). The agent reasons on the server;
tools run on the phone (device tools) or the server (cloud tools).

```
protocol/   the JSON contract between phone and server
backend/    FastAPI + agent loop + tool registry   (Phase 0 done)
android/    Kotlin + Compose app                   (Phase 0 skeleton, see android/README.md)
```

## Run the backend
```bash
cd backend
pip install -e ".[dev,llm]"
cp .env.example .env            # then: export GROQ_API_KEY=... ZIBI_DEV_TOKEN=...
pytest                           # no API key needed, uses a scripted fake LLM
uvicorn zibi.main:app --reload   # ws://localhost:8000/ws
python scripts/phone_sim.py --token $ZIBI_DEV_TOKEN   # a fake phone in your terminal
```
Try: "remind me to clip my nails before going out", "I have a test next friday",
"text Sam I'm running late" (triggers the confirmation card).

## Switch model / provider
Edit `backend/config/models.yaml` (roles -> LiteLLM model names) or set `ZIBI_MODEL_ORCHESTRATOR=...`.
No code changes. Re-run your test conversations after switching.

## Architecture in one paragraph
`agent.py` is the loop (LLM -> tool calls -> results -> LLM). `tools/registry.py` merges cloud tools with the
phone's manifest sent in `hello`. `policy.py` decides which tools need approval. `session.py` turns "call a device
tool" into a WebSocket round trip with timeouts. `llm/` hides the provider behind one interface.

## Next
1. Brain-dump capture -> Quick tab (lists, tasks, message buttons). Done in this version, needs your testing.
2. Document library + hand-off to ChatGPT/Claude: built in this version, needs your testing.
3. Trigger engine: fire reminders (AlarmManager + notifications). "Leaving now" becomes a notification action / bubble button.
4. Share target, voice, photo triage workflow, timetable editor and context.
5. Bubble surface. 6. Memory, proactive questions, Gmail (MCP).
