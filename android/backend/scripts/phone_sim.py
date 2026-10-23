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
    {"name": "send_message", "description": "Send a message to a contact as the user.", "risk": "HIGH_STAKES",
     "parameters": {"type": "object", "properties": {"to": {"type": "string"}, "text": {"type": "string"}},
                    "required": ["to", "text"], "additionalProperties": False}},
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
