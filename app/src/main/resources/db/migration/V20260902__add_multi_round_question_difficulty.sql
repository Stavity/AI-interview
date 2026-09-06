ALTER TABLE multi_round_questions
  ADD COLUMN difficulty VARCHAR(16);

CREATE INDEX idx_multi_round_questions_round_difficulty
  ON multi_round_questions(round_id, difficulty);
