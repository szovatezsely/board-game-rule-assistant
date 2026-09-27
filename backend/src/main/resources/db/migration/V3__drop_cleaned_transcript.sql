-- Removes the OCR "cleanup" column along with the pass that populated it.
--
-- The idea was to repair recognition noise once so summaries and answers would
-- read well. In practice the repair pass corrupted text that was already
-- correct: "AJÁTEK CÉLJA" (a missing space in "A JÁTÉK CÉLJA") became "AJÁNDÉK
-- CÉLJA", and the already-correct heading "AZ UDVARONCKÁRTYÁK" was split in two.
-- Trading correctness for readability is the wrong trade for a rules assistant,
-- so the raw transcript is once again the single source of truth and answer
-- readability is handled purely in the answering prompt.
ALTER TABLE game_page DROP COLUMN IF EXISTS cleaned_transcript;
