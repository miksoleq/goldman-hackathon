-- NULL identifies executions created by the earlier, operator-driven release.
ALTER TABLE action_executions ADD COLUMN automatic boolean;
ALTER TYPE action_status ADD VALUE 'skipped';
ALTER TABLE action_types ALTER COLUMN auto_execute SET DEFAULT true;
UPDATE action_types SET auto_execute = true, approval_required = false
WHERE name IN ('check_workflow_status', 'retry_workflow', 'escalate');

CREATE INDEX action_executions_automatic_queue ON action_executions (live_alert_id, step_no)
WHERE automatic AND status IN ('ready', 'running');
