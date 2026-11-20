CREATE TABLE rule_created_outbox (
    config_version bigint PRIMARY KEY,
    rule_id uuid NOT NULL,
    payload jsonb NOT NULL
);
