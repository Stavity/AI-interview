-- Add summary column for chat session context compression (TRD C-03)
ALTER TABLE chat_sessions
  ADD COLUMN IF NOT EXISTS summary TEXT;
