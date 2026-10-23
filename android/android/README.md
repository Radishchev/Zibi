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
4. Try: "remind me to clip my nails before going out", then open **Reminders** and tap **I'm leaving now**.
   Try "set an alarm for 7:30", "I have a test next friday" (opens the calendar prefilled; you tap Save).
   Everything Zibi does appears in **History**, with **Undo** for reminders.

## Unit tests (no device needed)
`./gradlew test` (or run `ProtocolTest` / `DispatcherTest` from Studio).

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
- Reminders are **stored but nothing fires them yet**. No notification at the due time. That is the trigger engine (next).
- No voice input, share target, bubble, or photo triage yet.
- No timetable editor yet (the app sends an empty timetable in `hello`).
- `usesCleartextTraffic` is on so `ws://` works in development. Remove it once the backend is behind TLS.
- Versions in `gradle/libs.versions.toml` are a late-2024 set; this code has not been compiled yet, so expect to fix a few small errors on first build.
