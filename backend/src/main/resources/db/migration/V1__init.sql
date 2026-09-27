-- Core aggregate: one row per scanned rulebook.
CREATE TABLE game (
    id              UUID PRIMARY KEY,
    title           TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    status_detail   TEXT,
    error_message   TEXT,
    page_count      INTEGER     NOT NULL DEFAULT 0,
    pages_processed INTEGER     NOT NULL DEFAULT 0,
    summary         TEXT,
    cover_image     BYTEA,
    cover_mime      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_game_created_at ON game (created_at DESC);

-- One row per uploaded rulebook photo, plus the text the vision model read
-- from it. Transcripts are kept so a page is never sent to Groq twice.
CREATE TABLE game_page (
    id          UUID PRIMARY KEY,
    game_id     UUID    NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL,
    image_data  BYTEA   NOT NULL,
    image_mime  TEXT    NOT NULL,
    transcript  TEXT,
    CONSTRAINT uq_game_page_number UNIQUE (game_id, page_number)
);

CREATE INDEX idx_game_page_game ON game_page (game_id, page_number);

-- Rendered speech for the current summary. summary_hash lets us serve cached
-- audio for repeat plays and re-synthesise only when the summary changes.
CREATE TABLE game_audio (
    game_id      UUID PRIMARY KEY REFERENCES game (id) ON DELETE CASCADE,
    summary_hash TEXT        NOT NULL,
    audio_data   BYTEA       NOT NULL,
    mime_type    TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id         UUID PRIMARY KEY,
    game_id    UUID        NOT NULL REFERENCES game (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_game ON chat_message (game_id, created_at);
