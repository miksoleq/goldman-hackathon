CREATE TABLE live_alert_inbox (
    alert_id uuid PRIMARY KEY,
    payload jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
