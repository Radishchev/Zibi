import sys
import types
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from zibi.config import Settings
from zibi.llm.fake import FakeLlm
from zibi.server import create_app
from zibi.stt import MAX_AUDIO_BYTES

H = {"X-Zibi-Device": "phone1"}


async def fake_stt(model: str, name: str, data: bytes) -> str:
    assert model == "groq/whisper-large-v3-turbo"
    return "remind me to call Sam tomorrow"


def client(tmp_path, token=None, stt=fake_stt):
    app = create_app(llm=FakeLlm([]), settings=Settings(dev_token=token), stt_fn=stt)
    return TestClient(app)


def test_transcribe_returns_text(tmp_path):
    c = client(tmp_path)
    with c:
        r = c.post("/transcribe", params={"name": "voice.m4a"}, content=b"\x00fake-audio", headers=H)
        assert r.status_code == 200, r.text
        assert r.json() == {"text": "remind me to call Sam tomorrow"}


def test_transcribe_rejects_non_audio_and_oversize(tmp_path):
    c = client(tmp_path)
    with c:
        r = c.post("/transcribe", params={"name": "notes.txt"}, content=b"hello", headers=H)
        assert r.status_code == 422 and "audio" in r.json()["detail"]
        r = c.post("/transcribe", params={"name": "long.m4a"}, content=b"\x00" * (MAX_AUDIO_BYTES + 1), headers=H)
        assert r.status_code == 413


def test_transcribe_enforces_token_and_reports_stt_failure(tmp_path):
    from zibi.stt import SttProblem

    async def broken(model, name, data):
        raise SttProblem("I couldn't transcribe that voice message. Please try again.")

    c = client(tmp_path, token="secret", stt=broken)
    with c:
        r = c.post("/transcribe", params={"name": "voice.m4a"}, content=b"\x00x", headers=H)
        assert r.status_code == 401
        r = c.post(
            "/transcribe", params={"name": "voice.m4a"}, content=b"\x00x",
            headers={**H, "X-Zibi-Token": "secret"},
        )
        assert r.status_code == 422 and "transcribe" in r.json()["detail"]


@pytest.mark.asyncio
async def test_transcribe_pins_language_to_english(monkeypatch):
    """Whisper otherwise guesses the language (hindi, russian, ...); Zibi only supports English."""
    sent: list[dict] = []
    mod = types.ModuleType("litellm")

    async def atranscription(**kwargs):
        sent.append(kwargs)
        return SimpleNamespace(text="hello in english")

    mod.atranscription = atranscription
    monkeypatch.setitem(sys.modules, "litellm", mod)

    from zibi.stt import transcribe

    text = await transcribe("groq/whisper-large-v3-turbo", "voice.m4a", b"\x00audio")
    assert text == "hello in english"
    assert sent[0]["language"] == "en"
