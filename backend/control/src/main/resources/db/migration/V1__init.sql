-- =====================================================================
-- Flexible Alerting & Notification Service — schemat początkowy
-- Plik: backend/control/src/main/resources/db/migration/V1__init.sql
-- Migracje uruchamia wyłącznie serwis `control` (Flyway).
-- PostgreSQL 17
-- =====================================================================

-- ---------------------------------------------------------------------
-- Typy wyliczeniowe
-- Enum w Postgresie porównuje się w kolejności deklaracji,
-- więc `severity >= 'high'` działa poprawnie.
-- ---------------------------------------------------------------------
CREATE TYPE severity AS ENUM ('info', 'low', 'medium', 'high', 'critical');

CREATE TYPE alert_status AS ENUM ('open', 'acknowledged', 'resolved');

CREATE TYPE notification_status AS ENUM (
    'pending',      -- w kolejce notify:{channel}
    'sent',
    'retrying',     -- w kolejce retry z backoffem
    'failed',       -- wyczerpane próby, trafiło do DLQ
    'throttled',    -- wstrzymane, trafi do digestu
    'digested',     -- wysłane jako część zbiorczego digestu
    'skipped'       -- odfiltrowane przez preferencje (severity / godziny pracy)
);

CREATE TYPE action_status AS ENUM (
    'manual',            -- nieznana akcja → krok do wykonania ręcznie
    'ready',             -- do odpalenia przyciskiem
    'pending_approval',  -- approval: required
    'running',
    'succeeded',
    'failed',
    'rejected',          -- odrzucona przy zatwierdzaniu
    'done'               -- krok manualny oznaczony jako wykonany
);

-- =====================================================================
-- KONFIGURACJA (edytowana w UI, ładowana do snapshotu w engine)
-- =====================================================================

CREATE TABLE teams (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL UNIQUE,
    description text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username     text NOT NULL UNIQUE,
    display_name text NOT NULL,
    email        text,
    phone        text,
    timezone     text NOT NULL DEFAULT 'Europe/Warsaw',
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    team_id uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role    text NOT NULL DEFAULT 'member',          -- member | lead | oncall
    PRIMARY KEY (team_id, user_id)
);

-- Typ kanału celowo jako text, a nie enum:
-- nowy typ kanału (np. 'slack') nie wymaga migracji.
CREATE TABLE channels (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL UNIQUE,
    type       text NOT NULL,                        -- ui | email | sms | phone | webhook | …
    config     jsonb NOT NULL DEFAULT '{}'::jsonb,   -- np. {"url": "...", "bodyTemplate": "..."}
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Preferencje dostarczania: per zespół (user_id NULL) albo per osoba.
CREATE TABLE delivery_prefs (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id             uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id             uuid REFERENCES users(id) ON DELETE CASCADE,
    channel_id          uuid NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    min_severity        severity NOT NULL DEFAULT 'medium',
    -- np. {"mon-fri": "08:00-18:00"}; NULL = zawsze
    working_hours       jsonb,
    -- co zrobić poza godzinami pracy
    outside_hours       text NOT NULL DEFAULT 'skip'
                        CHECK (outside_hours IN ('skip', 'deliver', 'fallback')),
    fallback_channel_id uuid REFERENCES channels(id) ON DELETE SET NULL,
    -- limit anty-spamowy dla tej pary zespół/kanał
    throttle_limit      int NOT NULL DEFAULT 10,
    throttle_window_sec int NOT NULL DEFAULT 300,
    enabled             boolean NOT NULL DEFAULT true
);

CREATE INDEX delivery_prefs_team_idx ON delivery_prefs (team_id);

-- Runbooki w dowolnym formacie. Nieznany format = fallback do tekstu.
CREATE TABLE runbooks (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL UNIQUE,
    format     text NOT NULL,                        -- yaml_steps | markdown | url | text | …
    content    text NOT NULL,
    -- dynamiczne dopasowanie po polach zdarzenia,
    -- np. {"payload.type": "payment_failed", "payload.error.code": "PROVIDER_TIMEOUT"}
    matches    jsonb,
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by text
);

CREATE TABLE rules (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL UNIQUE,
    enabled     boolean NOT NULL DEFAULT true,
    priority    int NOT NULL DEFAULT 100,            -- niższa = ważniejsza
    -- skompilowana postać (match, title, body, dedup, severity) — czyta engine
    definition  jsonb NOT NULL,
    -- oryginalny YAML z edytora, z komentarzami i formatowaniem — czyta UI
    source_yaml text NOT NULL,
    runbook_id  uuid REFERENCES runbooks(id) ON DELETE SET NULL,  -- NULL = dobór dynamiczny
    version     int NOT NULL DEFAULT 1,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text
);

-- Routing reguła → zespoły jako relacja, żeby usunięcie zespołu
-- nie zostawiało „wiszących” odwołań w JSON-ie.
CREATE TABLE rule_teams (
    rule_id uuid NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
    team_id uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, team_id)
);

-- Historia wersji reguł (podgląd zmian i rollback na demo).
CREATE TABLE rule_versions (
    rule_id     uuid NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
    version     int NOT NULL,
    definition  jsonb NOT NULL,
    source_yaml text NOT NULL,
    changed_by  text,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version)
);

-- Typy akcji. kind = 'http' pozwala dodać nową akcję samą konfiguracją.
CREATE TABLE action_types (
    name              text PRIMARY KEY,              -- http_get | retry_workflow | escalate | …
    kind              text NOT NULL,                 -- http | escalate | notify | builtin
    template          jsonb NOT NULL DEFAULT '{}'::jsonb,
    approval_required boolean NOT NULL DEFAULT false,
    auto_execute      boolean NOT NULL DEFAULT false,
    description       text
);

-- Licznik wersji konfiguracji — podstawa hot-reloadu.
-- Każdy zapis konfiguracji w control inkrementuje go w tej samej transakcji.
CREATE TABLE config_version (
    id         smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version    bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO config_version (id, version) VALUES (1, 1);

-- =====================================================================
-- DANE OPERACYJNE (zapisywane przez engine i control)
-- =====================================================================

-- ID alertu nadaje engine (UUIDv7) już w skrypcie Lua deduplikacji,
-- zanim alert trafi do bazy — dlatego brak DEFAULT.
CREATE TABLE alerts (
    id               uuid PRIMARY KEY,
    fingerprint      text NOT NULL,
    rule_id          uuid REFERENCES rules(id) ON DELETE SET NULL,
    rule_name        text NOT NULL,                  -- snapshot: reguła mogła zostać usunięta
    severity         severity NOT NULL,
    status           alert_status NOT NULL DEFAULT 'open',
    title            text NOT NULL,
    body             text,
    team_ids         uuid[] NOT NULL DEFAULT '{}',
    source           text NOT NULL,                  -- np. 'payments-webhook'
    count            int NOT NULL DEFAULT 1,         -- licznik deduplikacji
    first_seen       timestamptz NOT NULL,
    last_seen        timestamptz NOT NULL,
    sample_event     jsonb NOT NULL,                 -- pierwsze zdarzenie (pełna koperta)
    runbook_id       uuid REFERENCES runbooks(id) ON DELETE SET NULL,
    runbook_snapshot jsonb,                          -- wyrenderowany runbook z momentu alertu
    acknowledged_by  text,
    acknowledged_at  timestamptz,
    resolved_by      text,
    resolved_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- Feed alertów: otwarte, od najnowszych
CREATE INDEX alerts_status_last_seen_idx ON alerts (status, last_seen DESC);
-- Filtr „alerty mojego zespołu”
CREATE INDEX alerts_team_ids_idx ON alerts USING gin (team_ids);
-- Historia danego problemu
CREATE INDEX alerts_fingerprint_idx ON alerts (fingerprint, first_seen DESC);
CREATE INDEX alerts_rule_idx ON alerts (rule_id);

CREATE TABLE notifications (
    id              uuid PRIMARY KEY,                -- = klucz idempotencji przy wysyłce
    alert_id        uuid NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    channel_id      uuid REFERENCES channels(id) ON DELETE SET NULL,
    channel_type    text NOT NULL,                   -- snapshot
    recipient       text,                            -- adres e-mail / numer / user
    status          notification_status NOT NULL DEFAULT 'pending',
    attempts        int NOT NULL DEFAULT 0,
    last_error      text,
    digest_id       uuid,                            -- wspólne ID dla alertów z jednego digestu
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_alert_idx ON notifications (alert_id);
CREATE INDEX notifications_failed_idx ON notifications (status)
    WHERE status IN ('retrying', 'failed');

-- Kroki runbooka z akcjami dla konkretnego alertu + kto co zrobił.
CREATE TABLE action_executions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id          uuid NOT NULL REFERENCES alerts(id) ON DELETE CASCADE,
    step_no           int NOT NULL,
    title             text NOT NULL,
    action            text,                          -- NULL = krok czysto opisowy
    params            jsonb NOT NULL DEFAULT '{}'::jsonb,  -- już wyrenderowane
    status            action_status NOT NULL,
    approval_required boolean NOT NULL DEFAULT false,
    approved_by       text,
    approved_at       timestamptz,
    executed_by       text,
    started_at        timestamptz,
    finished_at       timestamptz,
    result            jsonb,                         -- status HTTP, fragment odpowiedzi, błąd
    comment           text,
    UNIQUE (alert_id, step_no)
);

CREATE INDEX action_executions_pending_idx ON action_executions (status)
    WHERE status IN ('pending_approval', 'running');

-- Audyt wszystkiego: zmiany konfiguracji, ack/resolve, akcje.
CREATE TABLE audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,                       -- username albo 'system'
    action      text NOT NULL,                       -- rule.updated | alert.acknowledged | action.approved …
    entity_type text NOT NULL,
    entity_id   text NOT NULL,
    details     jsonb
);

CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, at DESC);
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);

-- =====================================================================
-- Wbudowane typy akcji
-- =====================================================================
INSERT INTO action_types (name, kind, template, approval_required, auto_execute, description) VALUES
('http_get', 'http',
 '{"method": "GET", "url": "{{params.url}}", "timeoutMs": 5000}',
 false, false, 'Wywołanie GET pod wskazany adres'),
('http_post', 'http',
 '{"method": "POST", "url": "{{params.url}}", "body": "{{params.body}}", "timeoutMs": 5000}',
 true, false, 'Wywołanie POST pod wskazany adres'),
('retry_workflow', 'http',
 '{"method": "POST", "url": "http://mock-channels:8080/workflows/{{params.workflowId}}/retry", "timeoutMs": 5000}',
 true, false, 'Ponowienie workflow — zdefiniowane samą konfiguracją'),
('escalate', 'escalate',
 '{"team": "{{params.team}}", "severity": "critical"}',
 false, false, 'Eskalacja alertu do innego zespołu');
