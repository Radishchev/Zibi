from zibi.grounding import is_grounded


def user(text):
    return {"role": "user", "content": text}


def test_exact_quote_is_grounded_even_with_different_case_and_punctuation():
    h = [user("Okay so I started working at Acme Corp last week!")]
    assert is_grounded("I started working at Acme Corp last week", h)
    assert is_grounded("i started working at acme corp last week.", h)


def test_a_paraphrase_or_invention_is_not_grounded():
    h = [user("I started working at Acme Corp last week")]
    assert not is_grounded("The user is employed by Acme", h)
    assert not is_grounded("I work at Google", h)
    assert not is_grounded("", h) and not is_grounded("ok", h)


def test_the_assistants_own_words_never_count():
    h = [user("hi"), {"role": "assistant", "content": "You told me you love hiking in the mountains"}]
    assert not is_grounded("you love hiking in the mountains", h)


def test_answers_to_questions_count_but_other_tool_output_does_not():
    h = [
        user("set an alarm"),
        {"role": "assistant", "content": None, "tool_calls": [
            {"id": "a1", "type": "function", "function": {"name": "ask_user", "arguments": "{}"}},
            {"id": "b1", "type": "function", "function": {"name": "search_documents", "arguments": "{}"}}]},
        {"role": "tool", "tool_call_id": "a1", "content": '{"ok": true, "result": {"answer": "loud, twelve alarms two minutes apart"}}'},
        {"role": "tool", "tool_call_id": "b1", "content": '{"ok": true, "result": {"passages": [{"text": "the professor lives in Paris"}]}}'},
    ]
    assert is_grounded("twelve alarms two minutes apart", h)
    assert not is_grounded("the professor lives in Paris", h)


def test_old_messages_fall_out_of_the_window():
    h = [user("I am allergic to peanuts")] + [user(f"filler {i}") for i in range(20)]
    assert not is_grounded("I am allergic to peanuts", h)


def test_a_small_slip_in_a_long_quote_is_tolerated():
    h = [user("I usually have lunch around one thirty on weekdays at the canteen")]
    assert is_grounded("I usually have lunch around one thirty on weekdays at canteen", h)
