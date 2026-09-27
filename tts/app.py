"""
Hungarian text-to-speech microservice.

Two engines behind one small HTTP API, so the Kotlin backend can turn rulebook
summaries into speech without shipping a Python runtime of its own:

- **Gemini TTS** (gemini-3.8-flash-tts, voice "Kore") when GEMINI_API_KEY is
  set. Natural, fluent Hungarian narration on the Gemini free tier.
- **Piper** (hu_HU-anna-medium) otherwise, and as the fallback when Gemini is
  unavailable. Fully offline and free, but its Hungarian voices are "medium"
  quality and sound noticeably synthetic.

A recording always uses one engine throughout: if any part fails on Gemini, the
whole text is read by Piper instead of mixing voices. The engine that actually
produced the audio is reported in the X-TTS-Engine header, so the backend can
cache a fallback recording as Piper's and try Gemini again next time.
"""

import base64
import io
import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
import wave
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

from piper import PiperVoice, SynthesisConfig

LOG = logging.getLogger("tts")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

MODEL_PATH = os.environ.get("PIPER_MODEL", "/models/hu_HU-anna-medium.onnx")
# Piper is not thread safe, and a single synthesis already saturates the cores it
# is given, so requests are serialised by uvicorn running a single worker.
MAX_CHARS = int(os.environ.get("TTS_MAX_CHARS", "40000"))

GEMINI_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GEMINI_MODEL = os.environ.get("GEMINI_TTS_MODEL", "").strip() or "gemini-3.8-flash-tts"
GEMINI_VOICE = os.environ.get("GEMINI_TTS_VOICE", "").strip() or "Kore"
GEMINI_ENABLED = bool(GEMINI_KEY)
GEMINI_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent"
# The free tier allows only TEN requests per day for a TTS model
# (GenerateRequestsPerDayPerProjectPerModel-FreeTier), so a summary is sent in as
# few requests as possible: one, normally. A request can produce roughly ten
# minutes of audio and 5,000 characters came out as 5.4 minutes, so parts of up
# to 6,000 characters stay inside the output limit; a part that still runs out
# is split in two and retried.
GEMINI_BATCH_CHARS = 6000
GEMINI_PARALLEL = 2
GEMINI_ATTEMPTS = 3
GEMINI_RETRY_SECONDS = 15
# Only the text itself is sent. A reading direction in front of it ("Olvasd fel
# ... hangon:") was spoken aloud as part of the narration.

# When the daily quota runs out, Gemini is not asked again until it resets:
# retrying only fails, and reporting Piper as the engine in /health lets the
# backend serve the cached Piper recording instead of re-rendering it on every
# play. Free-tier daily quotas reset at midnight Pacific time; UTC-8 is used
# year-round, which during daylight saving waits one hour longer than needed.
_gemini_blocked_until = 0.0
PACIFIC_OFFSET_HOURS = 8

app = FastAPI(title="Board Game Rule Assistant TTS", docs_url=None, redoc_url=None)

_voice: PiperVoice | None = None


def voice() -> PiperVoice:
    global _voice
    if _voice is None:
        LOG.info("Loading Piper voice from %s", MODEL_PATH)
        _voice = PiperVoice.load(MODEL_PATH)
        LOG.info("Voice loaded (sample rate %s Hz)", _voice.config.sample_rate)
    return _voice


class SynthesizeRequest(BaseModel):
    text: str = Field(min_length=1)
    # 1.0 is the voice's natural pace; >1 slows down, which helps for rules text.
    length_scale: float = Field(default=1.06, ge=0.5, le=2.0)


PIPER_ID = f"piper:{os.path.basename(MODEL_PATH)}"
GEMINI_ID = f"gemini:{GEMINI_MODEL}:{GEMINI_VOICE}"


def gemini_available() -> bool:
    return GEMINI_ENABLED and time.time() >= _gemini_blocked_until


def engine_id() -> str:
    """The engine a synthesis would use right now. The backend keys cached audio
    by it: when it changes (voice switched, or Gemini's quota reset after a Piper
    fallback), the summary is re-recorded; while it stays Piper because the quota
    is spent, the cached Piper recording is served as it is."""
    return GEMINI_ID if gemini_available() else PIPER_ID


BULLET = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+(.*)$")


def merge_list_runs(text: str) -> str:
    """Join each run of consecutive list items into one flowing sentence.

    Piper synthesises sentence by sentence, so a bullet list becomes a series of
    two-word utterances — "16 udvaronckártya." / "4 referenciakártya." — which is
    what makes the narration sound clipped and robotic. Merging a run into
    "16 udvaronckártya, 4 referenciakártya, 13 kegyjelző." gives the voice a
    single natural phrase to work with instead.

    This lives here rather than in the prompt because speakability is the TTS
    layer's concern: the summary is also rendered on screen, where lists are
    perfectly readable, and the model cannot be relied on never to emit one.
    """
    output: list[str] = []
    run: list[str] = []

    def flush() -> None:
        if not run:
            return
        # Strip each item's own terminator so the joined phrase reads cleanly.
        items = [item.rstrip().rstrip(".;,") for item in run if item.strip()]
        if items:
            output.append(", ".join(items) + ".")
        run.clear()

    for line in text.split("\n"):
        match = BULLET.match(line)
        if match:
            run.append(match.group(1))
        else:
            flush()
            output.append(line)
    flush()
    return "\n".join(output)


def clean_for_speech(text: str) -> str:
    """Strip Markdown scaffolding that would otherwise be read out literally.

    The summary is generated as Markdown for the web view, so bullets, hashes
    and emphasis markers have to go before it reaches the voice.
    """
    text = re.sub(r"```.*?```", " ", text, flags=re.DOTALL)
    # Markdown hard breaks (two trailing spaces) would otherwise survive as
    # line splits and fragment a sentence across two utterances.
    text = re.sub(r"[ \t]+$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^\s*[-–—]{3,}\s*$", "", text, flags=re.MULTILINE)
    # Before headings are stripped, so a heading is never merged into a list run.
    text = re.sub(r"^\s{0,3}#{1,6}\s*", "", text, flags=re.MULTILINE)
    text = merge_list_runs(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"(?<!\w)[*_]([^*_\n]+)[*_](?!\w)", r"\1", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    # Collapse blank runs into a single break so the voice inserts one clean pause.
    text = re.sub(r"\n{2,}", "\n", text)
    return text.strip()


def join_wavs(chunks: list[bytes]) -> bytes:
    """Concatenates same-format WAV files into one."""
    output = io.BytesIO()
    with wave.open(output, "wb") as target:
        for index, chunk in enumerate(chunks):
            with wave.open(io.BytesIO(chunk), "rb") as source:
                if index == 0:
                    target.setparams(source.getparams())
                target.writeframes(source.readframes(source.getnframes()))
    return output.getvalue()


# -------------------------------------------------------------------- Gemini


class GeminiUnavailable(Exception):
    """Gemini could not produce this part; the caller falls back to Piper."""


class PartTooLong(Exception):
    """The audio for a part hit the output limit; it has to be split."""


def batches(text: str, limit: int = GEMINI_BATCH_CHARS) -> list[str]:
    """Splits cleaned text into parts of bounded size, only ever between lines,
    so every join falls between sentences and is inaudible."""
    parts: list[str] = []
    current: list[str] = []
    size = 0
    for line in (l.strip() for l in text.split("\n")):
        if not line:
            continue
        if current and size + len(line) > limit:
            parts.append("\n".join(current))
            current, size = [], 0
        current.append(line)
        size += len(line) + 1
    if current:
        parts.append("\n".join(current))
    return parts


def halves(text: str) -> list[str]:
    """Splits a part into two at the line boundary nearest its middle."""
    lines = text.split("\n")
    if len(lines) < 2:
        raise GeminiUnavailable("a single line is too long for one request")
    total, running, cut = len(text), 0, 1
    for index, line in enumerate(lines[:-1], start=1):
        running += len(line) + 1
        cut = index
        if running >= total / 2:
            break
    return ["\n".join(lines[:cut]), "\n".join(lines[cut:])]


def pcm_to_wav(pcm: bytes, rate: int) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(rate)
        target.writeframes(pcm)
    return buffer.getvalue()


def audio_from_response(data: dict) -> bytes:
    """Returns WAV bytes. Gemini answers either with a complete WAV file (seen
    with gemini-3.8-flash-tts) or with raw 16-bit PCM labelled `audio/L16;rate=N`."""
    try:
        candidate = data["candidates"][0]
        part = candidate["content"]["parts"][0]["inlineData"]
    except (KeyError, IndexError) as error:
        raise GeminiUnavailable(f"no audio in response: {str(data)[:200]}") from error
    if candidate.get("finishReason") == "MAX_TOKENS":
        raise PartTooLong()
    audio = base64.b64decode(part["data"])
    if audio[:4] == b"RIFF":
        return audio
    rate = re.search(r"rate=(\d+)", part.get("mimeType", ""))
    return pcm_to_wav(audio, int(rate.group(1)) if rate else 24000)


def next_quota_reset() -> float:
    """Epoch seconds of the next midnight in Pacific time (as UTC-8)."""
    now = time.time()
    day = 86400
    shifted = now - PACIFIC_OFFSET_HOURS * 3600
    return (shifted // day + 1) * day + PACIFIC_OFFSET_HOURS * 3600


def quota_block(body: str) -> float | None:
    """For a 429, returns until when Gemini must not be asked again: the next
    daily reset when a per-day quota is spent, None for a per-minute limit that
    a short wait will clear."""
    try:
        details = json.loads(body).get("error", {}).get("details", [])
    except ValueError:
        details = []
    quota_ids = [v.get("quotaId", "") for d in details for v in d.get("violations", [])]
    if any("PerDay" in q for q in quota_ids) or "limit: 0" in body:
        return next_quota_reset()
    return None


def gemini_request(text: str) -> bytes:
    global _gemini_blocked_until
    body = json.dumps({
        "contents": [{"parts": [{"text": text}]}],
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": GEMINI_VOICE}}},
        },
    }).encode("utf-8")
    for attempt in range(1, GEMINI_ATTEMPTS + 1):
        if not gemini_available():
            raise GeminiUnavailable("daily quota spent")
        request = urllib.request.Request(
            GEMINI_URL, data=body, method="POST",
            headers={"x-goog-api-key": GEMINI_KEY, "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                return audio_from_response(json.load(response))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            if error.code == 429:
                blocked = quota_block(detail)
                if blocked:
                    _gemini_blocked_until = max(_gemini_blocked_until, blocked)
                    LOG.warning("Gemini TTS daily quota spent; using Piper until %s UTC",
                                time.strftime("%Y-%m-%d %H:%M", time.gmtime(blocked)))
                    raise GeminiUnavailable("daily quota spent") from error
            LOG.warning("Gemini TTS returned %s (attempt %d/%d): %s",
                        error.code, attempt, GEMINI_ATTEMPTS, detail[:200])
            # Overload and per-minute limits pass; anything else (a bad key) will not.
            if error.code not in (429, 500, 503):
                raise GeminiUnavailable(f"HTTP {error.code}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            LOG.warning("Gemini TTS unreachable (attempt %d/%d): %s", attempt, GEMINI_ATTEMPTS, error)
        if attempt < GEMINI_ATTEMPTS:
            time.sleep(GEMINI_RETRY_SECONDS * attempt)
    raise GeminiUnavailable(f"still failing after {GEMINI_ATTEMPTS} attempts")


def speak_part(text: str, depth: int = 0) -> bytes:
    """One request for the part; if its audio would not fit, two for its halves."""
    try:
        return gemini_request(text)
    except PartTooLong:
        if depth >= 2:
            raise GeminiUnavailable("part still too long after splitting")
        LOG.info("A %d-character part hit the output limit; splitting it", len(text))
        return join_wavs([speak_part(half, depth + 1) for half in halves(text)])


def synthesize_gemini(text: str) -> bytes:
    parts = batches(text)
    LOG.info("Synthesizing %d characters with Gemini %s/%s in %d request(s)",
             len(text), GEMINI_MODEL, GEMINI_VOICE, len(parts))
    if len(parts) == 1:
        return speak_part(parts[0])
    with ThreadPoolExecutor(max_workers=GEMINI_PARALLEL) as pool:
        chunks = list(pool.map(speak_part, parts))
    return join_wavs(chunks)


# --------------------------------------------------------------------- Piper


def synthesize_piper(text: str, length_scale: float) -> bytes:
    LOG.info("Synthesizing %d characters with Piper", len(text))
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav_file:
        voice().synthesize_wav(
            text,
            wav_file,
            syn_config=SynthesisConfig(length_scale=length_scale),
        )
    return buffer.getvalue()


# ----------------------------------------------------------------------- API


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "engine": engine_id()}


@app.post("/synthesize")
def synthesize(request: SynthesizeRequest) -> Response:
    text = clean_for_speech(request.text)
    if not text:
        raise HTTPException(status_code=400, detail="Nothing left to speak after cleanup")
    if len(text) > MAX_CHARS:
        raise HTTPException(status_code=413, detail=f"Text longer than {MAX_CHARS} characters")

    engine = PIPER_ID
    audio = None
    if gemini_available():
        try:
            audio = synthesize_gemini(text)
            engine = GEMINI_ID
        except GeminiUnavailable as error:
            LOG.warning("Gemini TTS unavailable (%s); reading the whole text with Piper instead", error)
    if audio is None:
        audio = synthesize_piper(text, request.length_scale)
    LOG.info("Produced %d bytes of WAV audio with %s", len(audio), engine)
    return Response(content=audio, media_type="audio/wav", headers={"X-TTS-Engine": engine})
