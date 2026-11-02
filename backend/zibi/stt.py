"""Speech-to-text via the `stt` role in models.yaml (LiteLLM transcription, e.g. Groq whisper).

Audio goes to the STT provider, the same way chat text goes to the LLM provider. The transcript
is returned to the phone that uploaded it and never stored here; the phone replaces sensitive
values with placeholders before sending it on as input.text.
"""
from __future__ import annotations

import asyncio
import io
import logging
import os
from pathlib import Path

log = logging.getLogger("zibi.stt")

MAX_AUDIO_BYTES = 10 * 1024 * 1024  # ~20 min of 16 kHz AAC; Groq's own limit is 25 MB
TRANSCRIBE_TIMEOUT_SECONDS = 120.0
# Zibi only supports English: pin the language so whisper transcribes (and translates) into English
# instead of guessing Hindi/Russian/Telugu/... Override with ZIBI_STT_LANGUAGE.
STT_LANGUAGE = os.getenv("ZIBI_STT_LANGUAGE", "en")

# what MediaRecorder on the phone produces, plus the formats Groq accepts
AUDIO_MIMES = {
    ".m4a": "audio/mp4",
    ".mp4": "audio/mp4",
    ".webm": "audio/webm",
    ".wav": "audio/wav",
    ".mp3": "audio/mpeg",
    ".mpga": "audio/mpeg",
    ".ogg": "audio/ogg",
    ".flac": "audio/flac",
}


class SttProblem(Exception):
    """The audio couldn't be transcribed. The message is safe to show to the user."""


def mime_for(name: str) -> str | None:
    return AUDIO_MIMES.get(Path(name).suffix.lower())


async def transcribe(model: str, filename: str, data: bytes) -> str:
    import litellm  # imported lazily so tests don't need it installed

    mime = mime_for(filename)
    if mime is None:
        raise SttProblem("Only audio files are supported (m4a, webm, wav, mp3, ogg, flac).")
    try:
        resp = await asyncio.wait_for(
            litellm.atranscription(
                model=model,
                file=(filename, io.BytesIO(data), mime),
                language=STT_LANGUAGE,
                timeout=TRANSCRIBE_TIMEOUT_SECONDS,
            ),
            timeout=TRANSCRIBE_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        raise SttProblem("The voice message took too long to transcribe. Try a shorter one.") from None
    except Exception as e:
        log.warning("stt failed for model %s: %s", model, str(e)[:300])
        raise SttProblem("I couldn't transcribe that voice message. Please try again.") from e
    text = (getattr(resp, "text", None) or "").strip()
    log.info("stt model=%s bytes=%d -> %d chars", model, len(data), len(text))
    return text
