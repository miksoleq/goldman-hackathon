UPDATE rules SET version = version + 1 WHERE runbook_id IS NOT NULL;
INSERT INTO rule_versions(rule_id, version, definition, source_yaml, changed_by)
SELECT id, version, definition, source_yaml, 'runbook-migration' FROM rules WHERE runbook_id IS NOT NULL;

WITH missing AS (
    SELECT r.*, row_number() OVER (ORDER BY r.id) AS seq
    FROM rules r WHERE r.runbook_id IS NOT NULL
), revision AS (
    UPDATE config_version SET version = version + (SELECT count(*) FROM missing), updated_at = now()
    WHERE id = 1 RETURNING version - (SELECT count(*) FROM missing) AS base
)
INSERT INTO rule_created_outbox (config_version, rule_id, payload)
SELECT base + seq, id, jsonb_build_object(
    'type','rule.created','ruleId',id,'version',version,'configVersion',base + seq,
    'name',name,'enabled',enabled,'priority',priority,'definition',definition,'runbookId',runbook_id,
    'runbook',(SELECT jsonb_build_object('id',b.id,'name',b.name,'format',b.format,'content',b.content) FROM runbooks b WHERE b.id = missing.runbook_id),
    'userIds',coalesce((SELECT jsonb_agg(user_id ORDER BY user_id) FROM rule_users WHERE rule_id = missing.id),'[]'::jsonb),
    'teamIds',coalesce((SELECT jsonb_agg(team_id ORDER BY team_id) FROM rule_teams WHERE rule_id = missing.id),'[]'::jsonb))
FROM missing CROSS JOIN revision;
