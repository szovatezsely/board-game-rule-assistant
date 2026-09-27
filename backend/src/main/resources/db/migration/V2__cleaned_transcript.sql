-- Cleaned-up rendering of each page's transcript.
--
-- The raw OCR output is kept alongside it, never overwritten: it is the record
-- of what the vision model actually read, and the cleanup pass is a lossy
-- language repair that should remain auditable against its source.
ALTER TABLE game_page ADD COLUMN cleaned_transcript TEXT;

COMMENT ON COLUMN game_page.transcript IS
    'Verbatim vision-model output, including OCR noise. Never rewritten.';
COMMENT ON COLUMN game_page.cleaned_transcript IS
    'Language-repaired transcript used for summaries and answers. NULL until the cleanup pass runs.';
