# Zibi Android app (Phase 0)

Kotlin + Jetpack Compose. Talks to the backend over the protocol in `/protocol/PROTOCOL.md`.

## Open and run
1. Android Studio -> **Open** -> select this `android/` folder. Let Gradle sync (first sync downloads a lot; Studio may ask to
   install the Android SDK 35 platform, accept). If it offers to upgrade AGP/Gradle/Kotlin, you can decline for now.
2. Start the backend (see root README). **Emulator:** `uvicorn zibi.main:app --reload`.
   **Real phone:** `uvicorn zibi.main:app --host 0.0.0.0 --reload`, same Wi-Fi as your laptop, and allow port 8000 through
   the laptop firewall.
3. Run the app. Open the **Settings** tab and set:
   - Emulator: `ws://10.0.2.2:8000/ws` (default)
   - Phone: `ws://<your-laptop-LAN-IP>:8000/ws`
   - Token: the same value as `ZIBI_DEV_TOKEN` on the backend.
   Tap **Save and reconnect**; the Chat tab should say **Connected**.
4. Try these (start with the brain dump, it is the main idea):
   - "grocery list: milk, eggs, bread. I have to inform Sam about project X. Also add cheese to the grocery list. Fix the login bug in project X."
     -> open the **Quick** tab: a Grocery list you can tick off, a Task under "project X", and a **Message Sam** button
     that opens SMS/WhatsApp with the message ready (you press Send).
   - "remind me to clip my nails before going out", then open **Reminders** and tap **I'm leaving now**.
   - "I have a lab notebook due today at 1pm" -> saves a reminder, then **asks** whether to add it to the calendar too.
   - "I have an exam next Tuesday at 8am" -> added to the calendar directly (Android asks for calendar permission the first time).
   - "text Sam I'm running late" -> Zibi asks SMS or WhatsApp, then opens the app with the message filled in. **You press Send.**
     (First time by name, Android asks for contacts permission. Lookup happens on the phone.)
   - "set an alarm for 7:30".
   - **Library:** open the Library tab, tap **Add PDF**, pick your notes. Wait for "indexed". Then ask in chat:
     "what did the notes say about gradient descent?" -> short answer with the document and page.
   - **Big requests:** "teach me everything about backpropagation from my notes" -> Zibi does NOT answer at length.
     It writes a prompt, shows it in the chat (editable) with the PDF listed, and you tap **Claude** or **ChatGPT**.
     The prompt is also copied to your clipboard. Whether those apps accept a shared PDF depends on their versions;
     if the file doesn't arrive, attach it manually. Please tell me what happens on your phone.
   Everything Zibi does appears in **History**, with **Undo** for reminders and calendar events.

## Unit tests (no device needed)
`./gradlew test` (or run `ProtocolTest` / `DispatcherTest` from Studio).

## Note on the database
This version migrates from the previous one without losing data (adds the library and hand-off tables).
Older installs may still be wiped once. If the app crashes at launch with a Room schema message, uninstall and reinstall it.

## Layout
```
protocol/  Envelope, ToolSpec, hello (mirrors the backend)
net/       ZibiClient: WebSocket, reconnect, runs tool calls the server sends
tools/     ZibiTool interface, registry, dispatcher (logging + HIGH_STAKES gate), the tools
data/      Room (reminders, action log) + DataStore settings
ui/        Compose screens and view models
AppContainer.kt   all wiring. To add a capability: write a ZibiTool, add it to the registry list.
```

## Known limitations (deliberate for Phase 0)
- Reminders are **stored but nothing fires them yet**. No notification at the due time. That is the trigger engine.
- Messages are only **prepared**: Zibi opens SMS/WhatsApp and you press Send. WhatsApp needs numbers with a country code; contacts saved without one may not open (SMS still works).
- Library search is keyword-based (SQLite full-text), not semantic. Scanned PDFs (images) can't be read yet.
- No voice input, share target, bubble, or photo triage yet.
- No timetable editor yet (the app sends an empty timetable in `hello`).
- `usesCleartextTraffic` is on so `ws://` works in development. Remove it once the backend is behind TLS.
- Versions in `gradle/libs.versions.toml` are a late-2024 set; this code has not been compiled yet, so expect to fix a few small errors on first build.
