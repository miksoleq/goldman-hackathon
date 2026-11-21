-- Ordinary memberships can span teams. A lead membership identifies the team managed by that user.
CREATE UNIQUE INDEX team_members_one_lead_assignment
    ON team_members(user_id) WHERE role = 'lead';

INSERT INTO team_members(team_id, user_id, role)
SELECT t.id, u.id, 'lead'
FROM users u JOIN teams t ON t.name = 'demo-operations'
WHERE u.username = 'demo.leader' AND u.role = 'TEAM_LEADER'
  AND NOT EXISTS (SELECT 1 FROM team_members m WHERE m.user_id = u.id AND m.role = 'lead')
ON CONFLICT (team_id, user_id) DO UPDATE SET role = 'lead';

-- An existing leader with just one membership already has an unambiguous assignment.
UPDATE team_members m SET role = 'lead'
FROM users u
WHERE u.id = m.user_id AND u.role = 'TEAM_LEADER' AND m.role = 'member'
  AND NOT EXISTS (SELECT 1 FROM team_members other WHERE other.user_id = m.user_id AND other.team_id <> m.team_id);
