import pytest

from zibi.privacy import find_sensitive, luhn, redact, redact_json

# The SAME cases are in the Android test (RedactorTest.kt). If you change one, change both.
CASES = [
    ("call me on +91 98765 43210 tomorrow", "call me on [PHONE] tomorrow"),
    ("my number is 9876543210", "my number is [PHONE]"),
    ("ring (555) 123-4567 please", "ring [PHONE] please"),
    ("email sam@example.com now", "email [EMAIL] now"),
    ("card 4111 1111 1111 1111 expires soon", "card [CARD] expires soon"),
    ("aadhaar 1234 5678 9012 ok", "aadhaar [ID] ok"),
    ("PAN is ABCDE1234F", "PAN is [ID]"),
    ("ssn 123-45-6789", "ssn [ID]"),
    ("my password is hunter2 ok", "my password is [SECRET] ok"),
    ("password: correct-horse", "password: [SECRET]"),
    ("my pin 4821", "my pin [SECRET]"),
    ("key AbCdEfGh1234567890AbCdEfGh1234567890 here", "key [SECRET] here"),
    # things that must NOT be hidden
    ("pin the note to the top", "pin the note to the top"),
    ("alarms every minute from 5:45 to 6:20, 36 alarms", "alarms every minute from 5:45 to 6:20, 36 alarms"),
    ("due 2026-10-30 at 14:30", "due 2026-10-30 at 14:30"),
    ("meet 10:30-11:30 tomorrow", "meet 10:30-11:30 tomorrow"),
    ("set 3 5 7 9 11 13 15 alarms", "set 3 5 7 9 11 13 15 alarms"),
    ("pi is 3.14159265 roughly", "pi is 3.14159265 roughly"),
    ("short number 555-1234", "short number 555-1234"),
    ("order 1234 5678 9012 3456 shipped", "order 1234 5678 9012 3456 shipped"),  # 16 digits but not a valid card
    ("Deep_Learning_Unit_1_notes_final.pdf", "Deep_Learning_Unit_1_notes_final.pdf"),
    ("see Lab_Report_2026_10_30_Final_Version_v2.docx", "see Lab_Report_2026_10_30_Final_Version_v2.docx"),
    ("key AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY ok", "key [SECRET] ok"),
    ("card 4111 1111 1111 1111 and 1234 5678 9012", "card [CARD] and [ID]"),
    ("text [PHONE_1] and [EMAIL_2] now", "text [PHONE_1] and [EMAIL_2] now"),  # the phone's placeholders are left alone
]


@pytest.mark.parametrize("text,expected", CASES)
def test_redaction_cases(text, expected):
    assert redact(text)[0] == expected


def test_luhn():
    assert luhn("4111111111111111") and not luhn("4111111111111112")


def test_counts_never_include_values():
    masked, counts = redact("a@b.co and c@d.io, call 9876543210")
    assert counts == {"EMAIL": 2, "PHONE": 1}
    assert "9876543210" not in masked and "@" not in masked


def test_json_is_masked_everywhere_but_keys_are_untouched():
    data = {"name": "Sam", "phone": "call 9876543210", "nested": [{"mail": "x@y.com"}], "n": 5}
    out = redact_json(data)
    assert out == {"name": "Sam", "phone": "call [PHONE]", "nested": [{"mail": "[EMAIL]"}], "n": 5}


def test_spans_never_overlap():
    spans = find_sensitive("mail 9876543210@example.com")  # a phone-like string inside an e-mail address
    assert [s.kind for s in spans] == ["EMAIL"]
