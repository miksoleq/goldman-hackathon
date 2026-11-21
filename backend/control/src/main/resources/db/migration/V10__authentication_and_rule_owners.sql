ALTER TABLE users
    ADD COLUMN password_hash text,
    ADD COLUMN role text NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('TEAM_LEADER', 'MEMBER'));
CREATE UNIQUE INDEX users_login_email_idx ON users(lower(email)) WHERE password_hash IS NOT NULL;

ALTER TABLE rules ADD COLUMN created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL;
INSERT INTO teams(name) VALUES ('demo-operations') ON CONFLICT (name) DO NOTHING;

-- Existing recipient accounts keep their identities and privileges.
WITH demo_accounts AS (
    INSERT INTO users(username, display_name, email, password_hash, role) VALUES
    ('demo.leader', 'Demo Leader', 'demo.leader@example.test', '$2y$11$0E9ZrjNDhSGJ3VFAQZz0eezu.rIV53/kue7SzabC4QQVrEK1xc09.', 'TEAM_LEADER'),
    ('demo.member', 'Demo Member', 'demo.member@example.test', '$2y$11$j/1jKFjI2ZzfovJDnpO3qufY54W7SOYaukk4ONr3L1ifn4NAfTHjq', 'MEMBER'),
    ('demo.analyst', 'Demo Analyst', 'demo.analyst@example.test', '$2y$11$pGxlwwjhYAWYT6.YqnA1IO7PGnBKuh5lqE6mRPLYCMBu5fUdvPSs6', 'MEMBER')
    ON CONFLICT DO NOTHING RETURNING id, username
)
INSERT INTO team_members(team_id, user_id)
SELECT teams.id, demo_accounts.id FROM teams CROSS JOIN demo_accounts
WHERE teams.name = 'demo-operations' AND demo_accounts.username IN ('demo.leader', 'demo.member');
