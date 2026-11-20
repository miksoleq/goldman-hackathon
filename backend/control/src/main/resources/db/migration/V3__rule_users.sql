CREATE TABLE rule_users (
    rule_id uuid NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, user_id)
);
