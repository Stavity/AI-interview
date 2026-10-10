-- High-frequency query indexes and constraints for learning and chat domains

-- Chat: ensure message order uniqueness (already enforced in V20260821, add covering index)
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_completed_order
  ON chat_messages (session_id, completed, message_order);

-- Learning: plan task due-date lookups for reminder scanning
CREATE INDEX IF NOT EXISTS idx_learning_plan_tasks_due_status
  ON learning_plan_tasks (due_date, status);

-- Learning: plan task list ordering
CREATE INDEX IF NOT EXISTS idx_learning_plan_tasks_plan_due_sort
  ON learning_plan_tasks (plan_id, due_date, sort_order);
