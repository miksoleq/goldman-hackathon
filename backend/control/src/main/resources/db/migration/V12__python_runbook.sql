INSERT INTO action_types (name, kind, template, approval_required, auto_execute, description)
VALUES ('python', 'python', '{"params":[]}', true, true, 'Python Script');

-- The original placeholder URL could not satisfy the fixed-destination HTTP policy.
UPDATE action_types SET template = '{"params":[],"method":"GET","url":"http://script-runner:8001/health","timeoutMs":3000}',
    description = 'Script runner health check', auto_execute = true
WHERE name = 'http_get' AND template->>'url' = '{{params.url}}';

INSERT INTO runbooks (name, format, content, actions)
VALUES ('script-runner-diagnostics', 'text', 'Check runner health, then approve its diagnostic summary.',
    jsonb_build_array('{"type":"http_get","params":{}}'::jsonb,
        jsonb_build_object('type', 'python', 'params', '{}'::jsonb, 'code', $code$def run(ctx):
    health = ctx["previousStepResult"]["status"]
    return {"service": "script-runner", "healthy": health == "UP"}
$code$)));
