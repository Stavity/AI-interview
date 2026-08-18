ALTER TABLE algorithm_submissions
  ADD CONSTRAINT algorithm_submissions_question_unique UNIQUE (question_id);
