# Zibi: personal assistant

Android app (Kotlin, planned) + agent backend (Python, this repo). The agent reasons on the server;
tools run on the phone (device tools) or the server (cloud tools).

```
protocol/   the JSON contract between phone and server
backend/    FastAPI + agent loop + tool registry   (Phase 0 done)
android/    Kotlin + Compose app                   (Phase 0 skeleton, see android/README.md)
```

## Choosing the model (Gemini now, Groq optional)
`backend/config/models.yaml` picks the model per role. It currently uses Groq's `gpt-oss-120b` (fast: about 1 s per call; needs `GROQ_API_KEY`) with Gemini's
`gemini-flash-lite-latest` as the fallback (needs `GEMINI_API_KEY`). Export both in the terminal that runs uvicorn (the
server logs a warning at startup if one is missing).

**If it feels slow:** every model call is logged with its time, e.g. `llm ... seconds=2.3 prompt_tokens=... thinking_tokens=...`
(calls over 10 s are logged as warnings). `python scripts/check_models.py --ping` times a tiny call to each model directly
and through LiteLLM, so you can see whether the model/service or the library is the bottleneck, and compare candidates:
`--ping gemini/gemini-3.1-flash-lite gemini/gemini-flash-lite-latest`. Try a reasoning level without editing files via
`ZIBI_REASONING_ORCHESTRATOR=minimal` (or `off`). A single call times out after 30 s (`ZIBI_LLM_TIMEOUT`).

**Model names change fast** (a name that worked last month can return 404). Run `python scripts/check_models.py`
(add `--ping` for a real test call) to see what your key can use today.

**Fallback:** if the main model is overloaded (503), rate limited or retired, the server retries with growing waits and
then tries the models under `fallbacks:` in `models.yaml` (a fallback equal to the main model is ignored). Turn it off with `ZIBI_FALLBACK_ORCHESTRATOR=""`.

**Privacy note for Gemini:** on the free (unpaid) tier Google's terms say prompts and responses may be used to improve
its products and read by human reviewers, and they advise not to send personal information. Zibi sends your profile and
messages to the model, so use fake or low-sensitivity data while testing on the free tier. Linking a Cloud Billing
account to the key makes all use "Paid" for data purposes (not used for training) even within the free quota. Check
Google's current terms (ai.google.dev/gemini-api/terms) before relying on this.

## Security model (short version)
Sensitive values (phone numbers, e-mails, card/ID numbers, passwords) are replaced by placeholders on the phone before
anything is sent; the real values stay on the phone, encrypted with the Android Keystore, and are put back locally when a
tool runs. The server masks anything that still slips through. Facts are only saved with a quote of what the user said,
checked by the server. Full details and honest limits: `protocol/PROTOCOL.md` ("Privacy model").

## Run the backend
```bash
cd backend
pip install -e ".[dev,llm]"
cp .env.example .env            # then: export GEMINI_API_KEY=... ZIBI_DEV_TOKEN=...
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
2. Done, needs your testing: conversation memory that survives reconnects, rate-limit retries, user profile + timetable, alarm series, all-day/editable calendar events.
3. Trigger engine + Zibi's own alarm engine: fire reminders (AlarmManager + notifications). "Leaving now" becomes a notification action / bubble button.
4. Share target, voice, photo triage workflow, timetable editor and context.
5. Bubble surface. 6. Memory, proactive questions, Gmail (MCP).
