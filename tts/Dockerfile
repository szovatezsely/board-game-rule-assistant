FROM python:3.12-slim

WORKDIR /app

# espeak-ng supplies the Hungarian grapheme-to-phoneme rules Piper relies on.
RUN apt-get update \
    && apt-get install --no-install-recommends -y espeak-ng-data curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the voice into the image so a fresh `docker compose up` needs no
# runtime download and the container works with no outbound network at all.
ARG PIPER_VOICE=hu_HU-anna-medium
ARG PIPER_VOICE_URL=https://huggingface.co/rhasspy/piper-voices/resolve/main/hu/hu_HU/anna/medium
RUN mkdir -p /models \
    && curl -fSL "${PIPER_VOICE_URL}/${PIPER_VOICE}.onnx" -o "/models/${PIPER_VOICE}.onnx" \
    && curl -fSL "${PIPER_VOICE_URL}/${PIPER_VOICE}.onnx.json" -o "/models/${PIPER_VOICE}.onnx.json"

COPY app.py .

ENV PIPER_MODEL=/models/hu_HU-anna-medium.onnx
EXPOSE 8200

# One worker: Piper holds a non-thread-safe ONNX session per process.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8200", "--workers", "1"]
