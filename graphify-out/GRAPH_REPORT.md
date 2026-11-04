# Graph Report - zibi  (2026-11-03)

## Corpus Check
- Corpus is ~45,300 words - fits in a single context window. You may not need a graph.

## Summary
- 1387 nodes · 3648 edges · 80 communities (42 shown, 38 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 141 edges (avg confidence: 0.88)
- Token cost: 6,907 input · 2,550 output

## Community Hubs (Navigation)
- LLM Client Tests
- Context Prompt Tests
- Text Sharing and Lists
- External App Handoff
- Agent Tool Execution
- Privacy and Date Helpers
- Profile and Timetable Models
- Approval and Action Logging
- Document Search Tests
- Activity Navigation
- History and Undo UI
- Backend API Clients
- Agent Runtime
- Device Tool Registry
- WebSocket Tests
- Cloud Tool Schemas
- Calendar and Alarm Tools
- Bubble Overlay Service
- Tasks and Reminders
- Model Check Scripts
- Bubble Menu UI
- Conversation Persistence
- Reminder Scheduling
- Wire Protocol and Server
- Profile Data Repository
- Profile and Memory Tools
- WebSocket Client
- Document and Speech APIs
- Protocol Models
- Email Draft Tools
- Settings UI
- Encrypted Privacy Vault
- Test Data Access
- Speech Transcription Tests
- Local Privacy Storage
- Tool Implementations
- Server Protocol Cards
- Model Configuration Tests
- Messaging Tools
- Backend Test Fixtures
- Fact Grounding Tests
- Android Permission Broker
- Bubble Positioning
- WebSocket Serialization
- Calendar Time Tests
- Activity Screen UI
- Chat Screen UI
- Document Library UI
- Test Fakes
- Date Resolution Tests
- WebSocket Reconnection
- Timetable Updates
- Reminder Management UI
- App Theme
- Model Role Configuration
- Incoming Share Activity
- Tool Dispatch Events
- Email Sending Tools
- Vault Privacy Tests
- External Handoff Tools
- Core Protocol References
- Bubble Animation Persistence
- Application Startup
- Privacy Vault
- Backend CLI
- Envelope Protocol

## God Nodes (most connected - your core abstractions)
1. `create_app()` - 55 edges
2. `FakeLlm` - 49 edges
3. `AppContainer` - 48 edges
4. `LlmResponse` - 48 edges
5. `Agent` - 37 edges
6. `ToolSpec` - 35 edges
7. `FakeBridge` - 31 edges
8. `BubbleService` - 27 edges
9. `ZibiClient` - 27 edges
10. `PrivacyVault` - 25 edges

## Surprising Connections (you probably didn't know these)
- `AppContainer` --calls--> `PermissionBroker`  [INFERRED]
  android/app/src/main/java/com/zibi/app/AppContainer.kt → android/app/src/main/java/com/zibi/app/PermissionBroker.kt
- `BubbleOverlay()` --calls--> `remember()`  [INFERRED]
  android/app/src/main/java/com/zibi/app/bubble/BubbleOverlay.kt → android/app/src/test/java/com/zibi/app/MemoryTest.kt
- `parseTime()` --calls--> `normalizeTime()`  [INFERRED]
  android/app/src/main/java/com/zibi/app/tools/CalendarMath.kt → android/app/src/main/java/com/zibi/app/tools/ProfileTools.kt
- `AddToListTool` --calls--> `schema()`  [INFERRED]
  android/app/src/main/java/com/zibi/app/tools/CaptureTools.kt → android/app/src/main/java/com/zibi/app/tools/ZibiTool.kt
- `SendEmailTool` --calls--> `schema()`  [INFERRED]
  android/app/src/main/java/com/zibi/app/tools/EmailTool.kt → android/app/src/main/java/com/zibi/app/tools/ZibiTool.kt

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Privacy Protection Flow** — android_app_privacy_vault, backend_zibi_privacy, backend_zibi_grounding [EXTRACTED 0.95]
- **Agent Tool Execution Loop** — backend_zibi_agent, backend_zibi_tools_registry, backend_zibi_session, android_app_net_zibiclient [EXTRACTED 0.90]

## Communities (80 total, 38 thin omitted)

### Community 0 - "LLM Client Tests"
Cohesion: 0.09
Nodes (40): APIConnectionError, AuthenticationError, BadRequestError, client(), fake_litellm(), NotFoundError, RateLimitError, reply() (+32 more)

### Community 1 - "Context Prompt Tests"
Cohesion: 0.08
Nodes (32): at(), test_abbreviated_days_work(), test_hint_is_empty_without_a_usable_timetable(), test_in_class_now(), test_next_class_today_tomorrow_and_later(), test_old_unconfirmed_facts_are_flagged_and_private_values_stay_masked(), test_profile_is_grouped_and_capped(), test_prompt_contains_profile_schedule_and_stays_small() (+24 more)

### Community 2 - "Text Sharing and Lists"
Cohesion: 0.08
Nodes (11): httpBase(), extractIncomingSharedText(), AddToListTool, listKey(), findDocument(), args(), CaptureToolsTest, FakeListDao (+3 more)

### Community 3 - "External App Handoff"
Cohesion: 0.08
Nodes (13): HandoffLauncher, HandoffTarget, ChatGpt, Claude, Other, Ask, Assistant, ChatItem (+5 more)

### Community 4 - "Agent Tool Execution"
Cohesion: 0.21
Nodes (31): FakeBridge, _BridgeFailsOnSecondCall, call(), test_a_fact_the_user_never_said_is_refused_and_a_real_quote_is_accepted(), test_ask_user_rejects_more_than_four_options_with_feedback(), test_ask_user_returns_the_answer_to_the_model(), test_ask_user_with_an_empty_options_list_is_also_fine(), test_ask_user_with_no_answer_is_an_error_not_a_crash() (+23 more)

### Community 5 - "Privacy and Date Helpers"
Cohesion: 0.12
Nodes (16): findSensitive(), isPhone(), luhn(), Span, AlarmPlan, Invalid, minutesOf(), parseTime() (+8 more)

### Community 6 - "Profile and Timetable Models"
Cohesion: 0.13
Nodes (7): dateOf(), FactRow(), ProfileScreen(), ProfileViewModel, fact(), MemoryTest, remember()

### Community 7 - "Approval and Action Logging"
Cohesion: 0.13
Nodes (6): ApprovalLedger, ToolDispatcher, ToolRegistry, DispatcherTest, FakeLog, FakeTool

### Community 8 - "Document Search Tests"
Cohesion: 0.13
Nodes (16): test_documents_are_private_to_their_owner(), test_giant_unbroken_text_is_hard_split(), test_long_text_is_split_with_overlap_and_nothing_is_lost(), test_pdf_roundtrip_extracts_pages_and_indexes(), test_query_with_punctuation_cannot_break_the_search(), test_restrict_search_to_one_document_and_delete(), test_search_finds_the_right_passage_with_page_and_stemming(), test_search_terms_drop_filler_words() (+8 more)

### Community 9 - "Activity Navigation"
Cohesion: 0.07
Nodes (7): Screen, Activity, Chat, Library, Quick, Settings, ZibiRoot()

### Community 11 - "Backend API Clients"
Cohesion: 0.11
Nodes (4): LibraryApi, Uploaded, LibraryRepository, VoiceRecorder

### Community 12 - "Agent Runtime"
Cohesion: 0.10
Nodes (5): DeviceBridge, _repair_after_failure(), LlmClient, requires_confirmation(), Risk

### Community 13 - "Device Tool Registry"
Cohesion: 0.15
Nodes (13): AppContainer, ToolSpec, AddTaskTool, SaveMessageDraftTool, SetAlarmsTool, SetAlarmTool, ArchiveFactsTool, ForgetFactTool (+5 more)

### Community 14 - "WebSocket Tests"
Cohesion: 0.15
Nodes (25): store(), test_a_phone_number_that_reaches_the_server_is_masked_before_the_model_sees_it(), test_ask_card_tap_answers_the_model(), test_bad_device_tool_schema_is_rejected_not_fatal(), test_conversation_survives_a_reconnect_and_can_be_cleared(), test_first_message_must_be_hello(), test_full_roundtrip_with_device_tool(), test_high_stakes_goes_through_confirm_card() (+17 more)

### Community 15 - "Cloud Tool Schemas"
Cohesion: 0.12
Nodes (11): test_lenient_schema_does_not_modify_the_original(), test_providers_get_lenient_schemas_but_we_keep_the_strict_ones(), CloudTool, ToolSpec, _ask_user(), build_default_registry(), _resolve_date(), _resolve_datetime() (+3 more)

### Community 16 - "Calendar and Alarm Tools"
Cohesion: 0.11
Nodes (7): EventTimes, alarmIntent(), CreateCalendarEventTool, Existing, findWritableCalendarId(), repeatDays(), UpdateCalendarEventTool

### Community 18 - "Tasks and Reminders"
Cohesion: 0.15
Nodes (8): Handoff, CheckRow(), DraftRow(), ListWithItems, QuickScreen(), QuickState, QuickViewModel, SectionCard()

### Community 19 - "Model Check Scripts"
Cohesion: 0.13
Nodes (10): available(), direct_call(), call(), litellm_call(), main(), parse_gemini(), parse_groq(), _request() (+2 more)

### Community 20 - "Bubble Menu UI"
Cohesion: 0.09
Nodes (4): appContainer(), appViewModel(), BubbleOverlay(), openMainScreen()

### Community 21 - "Conversation Persistence"
Cohesion: 0.16
Nodes (7): msgs(), test_clear_empties_the_shared_list_in_place(), test_history_survives_a_new_connection_and_a_server_restart(), test_old_tool_outputs_are_shortened_but_the_latest_turn_is_kept_whole(), test_trimming_never_starts_in_the_middle_of_a_tool_exchange(), Conversations, trim_history()

### Community 22 - "Reminder Scheduling"
Cohesion: 0.16
Nodes (4): ReminderAlarmReceiver, ReminderRescheduleReceiver, ReminderScheduler, reminderTriggerAtMillis()

### Community 23 - "Wire Protocol and Server"
Cohesion: 0.19
Nodes (7): Envelope, new_id(), run_turn(), ws_endpoint(), send(), send_error(), Session

### Community 25 - "Profile and Memory Tools"
Cohesion: 0.16
Nodes (3): dateOf(), normalizeDay(), normalizeTime()

### Community 26 - "WebSocket Client"
Cohesion: 0.14
Nodes (5): Connected, Connecting, ConnState, Disconnected, ZibiClient

### Community 28 - "Protocol Models"
Cohesion: 0.11
Nodes (5): MsgType, Risk, HIGH_STAKES, READ, WRITE

### Community 29 - "Email Draft Tools"
Cohesion: 0.18
Nodes (5): emailComposerSummary(), findMailApps(), MailApp, matchMailApp(), EmailToolTest

### Community 31 - "Encrypted Privacy Vault"
Cohesion: 0.13
Nodes (3): KeystoreCipher, NoopCipher, SecretCipher

### Community 33 - "Speech Transcription Tests"
Cohesion: 0.18
Nodes (9): client(), test_transcribe_enforces_token_and_reports_stt_failure(), broken(), test_transcribe_pins_language_to_english(), test_transcribe_rejects_non_audio_and_oversize(), test_transcribe_returns_text(), mime_for(), SttProblem (+1 more)

### Community 36 - "Server Protocol Cards"
Cohesion: 0.19
Nodes (5): ask_card(), confirm_card(), HelloPayload, message_card(), T

### Community 37 - "Model Configuration Tests"
Cohesion: 0.20
Nodes (7): test_env_can_change_or_disable_the_fallback(), test_fallbacks_load_from_yaml_and_count_toward_the_key_check(), test_missing_keys_are_reported_for_the_configured_providers(), test_reasoning_level_can_be_tried_from_the_environment(), all_model_names(), load_model_config(), missing_api_keys()

### Community 38 - "Messaging Tools"
Cohesion: 0.25
Nodes (5): Found, Lookup, Problem, SendMessageTool, Skipped

### Community 39 - "Backend Test Fixtures"
Cohesion: 0.23
Nodes (9): ctx(), _isolated_data_dir(), make_pdf(), registry(), client(), test_rejects_non_pdf_scans_and_missing_device(), test_token_is_enforced_on_rest_too(), test_upload_list_search_delete() (+1 more)

### Community 40 - "Fact Grounding Tests"
Cohesion: 0.29
Nodes (10): test_a_paraphrase_or_invention_is_not_grounded(), test_a_small_slip_in_a_long_quote_is_tolerated(), test_answers_to_questions_count_but_other_tool_output_does_not(), test_exact_quote_is_grounded_even_with_different_case_and_punctuation(), test_old_messages_fall_out_of_the_window(), test_the_assistants_own_words_never_count(), user(), evidence_sources() (+2 more)

### Community 42 - "Bubble Positioning"
Cohesion: 0.32
Nodes (3): BubblePosition, BubblePositioning, BubblePositioningTest

### Community 43 - "WebSocket Serialization"
Cohesion: 0.24
Nodes (5): Envelope, helloEnvelope(), parseEnvelope(), toJson(), ProtocolTest

### Community 44 - "Calendar Time Tests"
Cohesion: 0.29
Nodes (4): eventTimes(), entries(), entry(), ProfileAndTimeTest

### Community 46 - "Chat Screen UI"
Cohesion: 0.22
Nodes (4): ChatRow(), ChatScreen(), ConnectionBar(), HandoffCard()

### Community 49 - "Date Resolution Tests"
Cohesion: 0.26
Nodes (7): test_evening_after_six_resolves_to_next_day(), test_evening_resolves_to_six_pm(), test_garbage_raises(), test_phrases(), test_relative_minutes_resolve_to_exact_local_datetime(), parse_date_phrase(), parse_datetime_phrase()

### Community 52 - "Reminder Management UI"
Cohesion: 0.44
Nodes (3): describe(), RemindersScreen(), RemindersViewModel

### Community 54 - "Model Role Configuration"
Cohesion: 0.20
Nodes (9): fallbacks, orchestrator, models, fast, orchestrator, stt, vision, reasoning_effort (+1 more)

### Community 57 - "Tool Dispatch Events"
Cohesion: 0.39
Nodes (6): Ask, ClientEvent, Confirm, Message, Problem, ToolRan

### Community 58 - "Email Sending Tools"
Cohesion: 0.50
Nodes (5): Empty, Found, Lookup, NotFound, SendEmailTool

## Knowledge Gaps
- **18 isolated node(s):** `Claude`, `ChatGpt`, `Other`, `Disconnected`, `READ` (+13 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 293 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **38 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AppContainer` connect `Device Tool Registry` to `Text Sharing and Lists`, `External App Handoff`, `Profile and Timetable Models`, `Approval and Action Logging`, `History and Undo UI`, `Backend API Clients`, `Calendar and Alarm Tools`, `Tasks and Reminders`, `Bubble Menu UI`, `Reminder Scheduling`, `Profile Data Repository`, `WebSocket Client`, `Document and Speech APIs`, `Settings UI`, `Encrypted Privacy Vault`, `Local Privacy Storage`, `Messaging Tools`, `Android Permission Broker`, `Document Library UI`, `Timetable Updates`, `Reminder Management UI`, `Email Sending Tools`, `External Handoff Tools`, `Application Startup`?**
  _High betweenness centrality (0.182) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `create_app()` (e.g. with `Settings` and `LlmClient`) actually correct?**
  _`create_app()` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Claude`, `ChatGpt`, `Other` to the rest of the system?**
  _18 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `LLM Client Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.09134906231094979 - nodes in this community are weakly interconnected._
- **Why does `UpdateTimetableTool` connect `Timetable Updates` to `Profile and Memory Tools`, `Calendar Time Tests`, `Device Tool Registry`, `Profile and Timetable Models`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `LlmResponse` (e.g. with `FakeLlm` and `LiteLLMClient`) actually correct?**
  _`LlmResponse` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `Context Prompt Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.08489795918367347 - nodes in this community are weakly interconnected._