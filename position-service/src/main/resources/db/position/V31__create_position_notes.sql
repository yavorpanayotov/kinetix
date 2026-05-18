-- UI overhaul plan §7.3.1: persistent free-text notes attached to a
-- (book, instrument) pair. Append-only from the UI's perspective —
-- a note is never edited in place; users add a new note instead, and
-- old notes can be deleted by id.
--
-- The composite index supports the hot query the UI makes:
--   "give me the notes for this (book, instrument), newest first"
-- and also serves the looser "all notes for this book" query without
-- a second index, because the leading column is book_id.
CREATE TABLE IF NOT EXISTS position_notes (
    id              UUID         PRIMARY KEY,
    book_id         TEXT         NOT NULL,
    instrument_id   TEXT         NOT NULL,
    note            TEXT         NOT NULL,
    author          TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_position_notes_book_instrument
    ON position_notes (book_id, instrument_id, created_at DESC);
