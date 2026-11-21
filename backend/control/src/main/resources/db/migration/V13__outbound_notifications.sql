ALTER TABLE delivery_prefs ALTER COLUMN team_id DROP NOT NULL;
ALTER TABLE delivery_prefs ADD CHECK (team_id IS NOT NULL OR user_id IS NOT NULL);
CREATE UNIQUE INDEX delivery_prefs_personal_idx ON delivery_prefs(user_id, channel_id) WHERE team_id IS NULL;

CREATE TABLE notification_deliveries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id uuid NOT NULL REFERENCES live_alert_inbox(alert_id) ON DELETE CASCADE,
    event_id text NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_type text NOT NULL,
    transport text NOT NULL,
    transport_options jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL CHECK (status IN ('pending','sent','retrying','failed','skipped','throttled')),
    title text NOT NULL,
    severity severity NOT NULL,
    recipient text,
    message text NOT NULL,
    attempts int NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (alert_id,user_id,channel_type)
);
CREATE INDEX notification_feed_idx ON notification_deliveries(user_id, created_at DESC, id DESC)
    WHERE channel_type = 'ui' AND status = 'sent';
CREATE INDEX notification_event_idx ON notification_deliveries(user_id, event_id);
CREATE INDEX notification_queue_idx ON notification_deliveries(available_at, id)
    WHERE status IN ('pending','retrying');
CREATE INDEX notification_throttle_idx ON notification_deliveries(user_id, channel_type, created_at);
ALTER TABLE users ADD COLUMN contacts jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(contacts) = 'object');


INSERT INTO channels(name, type, config) VALUES
    ('ui', 'ui', '{}'),
    ('email', 'smtp', '{"recipientField":"email"}'),
    ('sms', 'http', '{"recipientField":"sms","endpoint":"sms","idempotencyHeader":"Idempotency-Key","body":{"to":"{{recipient}}","text":"{{alert.shortTitle}} {{alert.link}}"}}')
ON CONFLICT (name) DO NOTHING;
