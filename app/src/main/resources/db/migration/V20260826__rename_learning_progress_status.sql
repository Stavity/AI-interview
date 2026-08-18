UPDATE learning_progress SET status = 'LEARNING' WHERE status = 'IN_PROGRESS';
ALTER TABLE learning_progress DROP CONSTRAINT learning_progress_status_check;
ALTER TABLE learning_progress ADD CONSTRAINT learning_progress_status_check CHECK (status IN ('NOT_STARTED', 'LEARNING', 'MASTERED'));
