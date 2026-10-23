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
| `ui.action` | `{action_id}` | `reply_to` = the card id (e.g. `approve` / `deny`) |

## Server -> phone
| type | payload | notes |
|---|---|---|
| `hello.ack` | `{tools:[names], rejected_tools:[names]}` | tools with invalid schemas are rejected, not fatal |
| `tool.call` | `{tool, args, risk}` | phone MUST reply with `tool.result`/`tool.error` within 30 s |
| `response.card` | `{card: "message", text}` or `{card: "confirm", summary, actions}` | more card kinds later: quick_actions, ask_user, needs_input, done |
| `error` | `{code, message}` | |

## ToolSpec
```json
{ "name": "create_reminder", "description": "...", "parameters": { "...JSON Schema..." },
  "risk": "READ | WRITE | HIGH_STAKES", "enabled": true }
```

## Rules
1. Every `tool.call` gets exactly one reply. A timeout is reported to the model as an error so it can recover.
2. `HIGH_STAKES` tools: the server asks for approval via a `confirm` card first. The phone MUST also refuse to run a HIGH_STAKES tool without a locally recorded approval (defence in depth).
3. The server never trusts the model's arguments: they are validated against the tool's JSON Schema and failures are returned to the model.
4. Dates are resolved in code (`resolve_date`), never by the model.
