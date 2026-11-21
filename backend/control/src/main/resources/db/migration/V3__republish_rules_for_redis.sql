-- Populate the new transport even for rules whose Kafka outbox entries were already sent.
WITH missing AS (
    SELECT r.*, row_number() OVER (ORDER BY r.id) AS seq
    FROM rules r WHERE NOT EXISTS (SELECT 1 FROM rule_created_outbox o WHERE o.rule_id = r.id)
), revision AS (
    UPDATE config_version SET version = version + (SELECT count(*) FROM missing), updated_at = now()
    WHERE id = 1 RETURNING version - (SELECT count(*) FROM missing) AS base
)
INSERT INTO rule_created_outbox (config_version, rule_id, payload)
SELECT base + seq, id, jsonb_build_object(
    'type','rule.created','ruleId',id,'version',version,'configVersion',base + seq,
    'name',name,'enabled',enabled,'priority',priority,'definition',definition,'runbookId',runbook_id,
    'teamIds',coalesce((SELECT jsonb_agg(team_id ORDER BY team_id) FROM rule_teams WHERE rule_id = missing.id),'[]'::jsonb))
FROM missing CROSS JOIN revision;
