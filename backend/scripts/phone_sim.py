"""A fake Android phone in your terminal. Lets you talk to the real backend + real LLM today,
before the Kotlin app exists.

    python scripts/phone_sim.py                      # ws://localhost:8000/ws
    python scripts/phone_sim.py --token change-me

Type messages. Device tool calls are 'executed' (printed) and succeed. When a confirmation
card appears, answer y or n.
"""
import argparse
import asyncio
import json
import random

import websockets

TOOLS = [
    {"name": "create_reminder", "description": "Create a reminder on the phone. Use trigger 'on_leaving_home' for things to do before going out, or 'at_time' with due_date/time.",
     "risk": "WRITE",
     "parameters": {"type": "object", "properties": {
         "title": {"type": "string"},
         "trigger": {"type": "string", "enum": ["on_leaving_home", "at_time"]},
         "due_date": {"type": "string", "description": "YYYY-MM-DD"},
         "time": {"type": "string", "description": "HH:MM, 24h"}},
         "required": ["title"], "additionalProperties": False}},
    {"name": "create_calendar_event", "description": "Add an event to the user's calendar.",
     "risk": "WRITE",
     "parameters": {"type": "object", "properties": {
         "title": {"type": "string"}, "date": {"type": "string", "description": "YYYY-MM-DD"},
         "start_time": {"type": "string"}, "end_time": {"type": "string"}},
         "required": ["title", "date"], "additionalProperties": False}},
    {"name": "set_alarm", "description": "Set an alarm.", "risk": "WRITE",
     "parameters": {"type": "object", "properties": {"time": {"type": "string", "description": "HH:MM, 24h"}, "label": {"type": "string"}},
                    "required": ["time"], "additionalProperties": False}},
    {"name": "set_alarm_series", "description": "Set a series of alarms starting at base_time, repeated every interval_minutes.",
     "risk": "WRITE",
     "parameters": {"type": "object", "properties": {
         "base_time": {"type": "string", "description": "HH:MM, 24h"},
         "count": {"type": "integer", "description": "number of alarms"},
         "interval_minutes": {"type": "integer", "description": "minutes between alarms"},
         "label": {"type": "string"}},
         "required": ["base_time", "count", "interval_minutes"], "additionalProperties": False}},
    {"name": "update_calendar_event", "description": "Update an existing calendar event by event_id.",
     "risk": "WRITE",
     "parameters": {"type": "object", "properties": {
         "event_id": {"type": "string", "description": "ID of the event to update"},
         "title": {"type": "string"}, "date": {"type": "string", "description": "YYYY-MM-DD"},
         "start_time": {"type": "string"}, "end_time": {"type": "string"}},
         "required": ["event_id"], "additionalProperties": False}},
    {"name": "send_message", "description": "Send a message to a contact as the user.", "risk": "HIGH_STAKES",
     "parameters": {"type": "object", "properties": {"to": {"type": "string"}, "text": {"type": "string"}, "app": {"type": "string", "enum": ["sms", "whatsapp"]}},
                    "required": ["to", "text"], "additionalProperties": False}},
    {"name": "add_to_list", "description": "Add items to a named list.", "risk": "WRITE",
     "parameters": {"type": "object", "properties": {"list_name": {"type": "string"}, "items": {"type": "array", "items": {"type": "string"}}},
                    "required": ["list_name", "items"], "additionalProperties": False}},
    {"name": "add_task", "description": "Save a task to do with no specific time.", "risk": "WRITE",
     "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "project": {"type": "string"}, "notes": {"type": "string"}},
                    "required": ["title"], "additionalProperties": False}},
    {"name": "save_message_draft", "description": "Save a message draft for later.", "risk": "WRITE",
     "parameters": {"type": "object", "properties": {"to": {"type": "string"}, "text": {"type": "string"}, "app": {"type": "string"}},
                    "required": ["to", "text"], "additionalProperties": False}},
    {"name": "propose_handoff", "description": "Prepare a prompt for ChatGPT/Claude.", "risk": "WRITE",
     "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "prompt": {"type": "string"}, "documents": {"type": "array", "items": {"type": "string"}}},
                    "required": ["title", "prompt"], "additionalProperties": False}},
]

TIMETABLE = [{"day": "Friday", "start": "10:00", "end": "12:00", "title": "Data Science"},
             {"day": "Monday", "start": "14:00", "end": "16:00", "title": "Machine Learning"}]


async def main(url: str, token: str | None) -> None:
    if token:
        url += f"?token={token}"
    pending_confirm: dict = {"id": None}

    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"type": "hello", "payload": {
            "device_id": "terminal-sim", "timezone": "Asia/Kolkata",
            "tools": TOOLS, "context": {"timetable": TIMETABLE}}}))

        async def reader() -> None:
            async for raw in ws:
                m = json.loads(raw)
                t, p = m["type"], m["payload"]
                if t == "hello.ack":
                    print(f"[connected] tools: {p['tools']}")
                elif t == "tool.call":
                    print(f"  [phone] executing {p['tool']} {json.dumps(p['args'])}")
                    await ws.send(json.dumps({"type": "tool.result", "reply_to": m["id"],
                                              "payload": {"ok": True, "result": {"id": random.randint(1, 999)}}}))
                elif t == "response.card" and p["card"] == "confirm":
                    pending_confirm["id"] = m["id"]
                    print(f"  [confirm] {p['summary']}  -> y/n?")
                elif t == "response.card" and p["card"] == "ask":
                    print(f"zibi> {p['question']}  options: {[o['label'] for o in p['options']]}  (type your answer)")
                elif t == "response.card":
                    print(f"zibi> {p['text']}")
                else:
                    print(f"[{t}] {p}")

        task = asyncio.create_task(reader())
        try:
            while True:
                line = (await asyncio.to_thread(input, "you> ")).strip()
                if not line:
                    continue
                if pending_confirm["id"]:
                    action = "approve" if line.lower().startswith("y") else "deny"
                    await ws.send(json.dumps({"type": "ui.action", "reply_to": pending_confirm["id"],
                                              "payload": {"action_id": action}}))
                    pending_confirm["id"] = None
                else:
                    await ws.send(json.dumps({"type": "input.text", "payload": {"text": line}}))
        except (EOFError, KeyboardInterrupt):
            pass
        finally:
            task.cancel()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="ws://localhost:8000/ws")
    ap.add_argument("--token")
    a = ap.parse_args()
    asyncio.run(main(a.url, a.token))
