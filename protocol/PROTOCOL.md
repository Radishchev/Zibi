# Zibi wire protocol v1

Transport: one WebSocket at `/ws` (optional `?token=`). Binary files go over REST (planned) and are referenced by `file_id`.

Every message is an envelope:

```json
{ "v": 1, "id": "msg_ab12", "type": "input.text", "session": "s_1", "reply_to": null,
  "ts": "2026-10-23T09:12:00+00:00", "payload": {} }
```

## Phone -> server
| type | payload | notes |
|---|---|---|
| `hello` | `{device_id, timezone, tools:[ToolSpec], context:{timetable, preferences}}` | MUST be first. `tools` is the phone's tool manifest. |
| `input.text` | `{text}` | starts an agent turn |
| `context.update` | any keys of `context` | merged into session context |
| `tool.result` | `{ok, result, undo_token?}` | `reply_to` = the `tool.call` id |
| `tool.error` | `{error}` | `reply_to` = the `tool.call` id |
| `ui.action` | `{action_id}` | `reply_to` = the card id. `approve`/`deny` for confirm cards; the option id for ask cards |

## Server -> phone
| type | payload | notes |
|---|---|---|
| `hello.ack` | `{tools:[names], rejected_tools:[names]}` | tools with invalid schemas are rejected, not fatal |
| `tool.call` | `{tool, args, risk}` | phone MUST reply with `tool.result`/`tool.error` within 30 s |
| `response.card` | `{card: "message", text}`, `{card: "confirm", summary, actions}`, or `{card: "ask", question, options:[{id,label}]}` | more card kinds later: quick_actions, needs_input, done |
| `error` | `{code, message}` | |

## ToolSpec
```json
{ "name": "create_reminder", "description": "...", "parameters": { "...JSON Schema..." },
  "risk": "READ | WRITE | HIGH_STAKES", "enabled": true }
```

## REST (document library)
Files never travel over the WebSocket. All calls send `X-Zibi-Device: <device id>` (same id as `hello.device_id`) and, if
the server has a token set, `X-Zibi-Token`. Documents are private to the device id that uploaded them.

| call | notes |
|---|---|
| `POST /documents?name=notes.pdf` | body = raw PDF bytes (max 50 MB). Returns `{doc_id, name, pages, chunks}`. 422 with a readable `detail` if it isn't a PDF, is password protected, or has no selectable text. |
| `GET /documents` | `{documents: [{id, name, pages, chunks}]}` |
| `DELETE /documents/{id}` | removes the file's index |

The agent searches the library with the server-side `search_documents` tool and is told the document names every turn.
Big requests are not answered by the agent: it calls the phone's `propose_handoff` tool, which saves a prompt the user
can send to ChatGPT/Claude.

## Rules
1. Every `tool.call` gets exactly one reply. A timeout is reported to the model as an error so it can recover.
2. `HIGH_STAKES` tools: the server asks for approval via a `confirm` card first. The phone MUST also refuse to run a HIGH_STAKES tool without a locally recorded approval (defence in depth).
3. The server never trusts the model's arguments: they are validated against the tool's JSON Schema and failures are returned to the model.
4. Dates are resolved in code (`resolve_date`), never by the model.
5. Typing instead of tapping: an `input.text` that arrives while an **ask** card is open is taken as the answer
   (no new turn starts). While a **confirm** card is open it declines the confirmation and then starts a new turn.
6. Tools that open another app for the user to finish (calendar editor fallback, message composer) report that in
   their result (`..._user_must_save`, `composer_opened_user_must_press_send`). The model must not claim more than that.
