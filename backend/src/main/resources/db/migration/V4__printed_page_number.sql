-- The page number printed in the book, as read off the photo by the vision
-- model. Uploads are frequently out of book order (photographed back to front,
-- or named so that 10.jpg sorts before 2.jpg), and this is what lets the
-- rulebook text be reassembled in reading order. NULL when no number is visible.
ALTER TABLE game_page ADD COLUMN printed_page_number INTEGER;
