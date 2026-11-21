ALTER TABLE runbooks ADD COLUMN actions jsonb NOT NULL DEFAULT '[]';
ALTER TYPE action_status ADD VALUE 'unavailable';
ALTER TYPE action_status ADD VALUE 'unknown';

ALTER TABLE action_executions
    ALTER COLUMN alert_id DROP NOT NULL,
    ADD COLUMN live_alert_id uuid REFERENCES live_alert_inbox(alert_id) ON DELETE CASCADE,
    ADD COLUMN kind text,
    ADD COLUMN template jsonb,
    ADD CONSTRAINT action_parent CHECK (num_nonnulls(alert_id, live_alert_id) = 1),
    ADD UNIQUE (live_alert_id, step_no);

INSERT INTO action_types (name, kind, template, description)
VALUES ('check_workflow_status', 'http',
        '{"method":"GET","url":"http://workflow-demo:8080/workflows/{{params.workflowId}}","params":["workflowId"],"timeoutMs":3000}',
        'Check workflow status');

UPDATE action_types SET template =
    '{"method":"POST","url":"http://workflow-demo:8080/workflows/{{params.workflowId}}/retry","params":["workflowId"],"timeoutMs":5000}',
    description = 'Retry workflow'
WHERE name = 'retry_workflow';
UPDATE action_types SET template = template || '{"params":["team"]}', description = 'Escalate alert'
WHERE name = 'escalate';
