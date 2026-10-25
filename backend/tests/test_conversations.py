from zibi.conversations import Conversations, trim_history


def msgs(n_turns):
    out = []
    for i in range(n_turns):
        out += [
            {"role": "user", "content": f"u{i}"},
            {"role": "assistant", "content": None, "tool_calls": [{"id": f"c{i}", "type": "function", "function": {"name": "t", "arguments": "{}"}}]},
            {"role": "tool", "tool_call_id": f"c{i}", "content": "x" * 1000},
            {"role": "assistant", "content": f"a{i}"},
        ]
    return out


def test_history_survives_a_new_connection_and_a_server_restart(tmp_path):
    db = tmp_path / "c.db"
    a = Conversations(db)
    a.history("phone1").append({"role": "user", "content": "I wake up at 7"})
    a.save("phone1")
    assert a.history("phone1") is a.history("phone1")      # one shared list per device (reconnects reuse it)
    b = Conversations(db)                                    # simulates a restart
    assert b.history("phone1") == [{"role": "user", "content": "I wake up at 7"}]
    assert b.history("someone-else") == []


def test_trimming_never_starts_in_the_middle_of_a_tool_exchange():
    h = msgs(20)  # 80 messages
    trim_history(h, max_messages=30)
    assert len(h) <= 30 and h[0]["role"] == "user"
    # every tool message still has its assistant tool_call before it
    ids = set()
    for m in h:
        for c in m.get("tool_calls", []) or []:
            ids.add(c["id"])
        if m["role"] == "tool":
            assert m["tool_call_id"] in ids


def test_old_tool_outputs_are_shortened_but_the_latest_turn_is_kept_whole():
    h = msgs(3)
    trim_history(h, max_messages=100, keep_tool_chars=100)
    tool_msgs = [m for m in h if m["role"] == "tool"]
    assert len(tool_msgs[0]["content"]) <= 101 and len(tool_msgs[1]["content"]) <= 101
    assert len(tool_msgs[2]["content"]) == 1000


def test_clear_empties_the_shared_list_in_place(tmp_path):
    c = Conversations(tmp_path / "c.db")
    live = c.history("d")
    live.append({"role": "user", "content": "hi"})
    c.save("d")
    c.clear("d")
    assert live == [] and Conversations(tmp_path / "c.db").history("d") == []
