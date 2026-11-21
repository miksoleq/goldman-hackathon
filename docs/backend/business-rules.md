# Business Rules

### Recipient directory

- **Expected behavior** — Control returns team names at `GET /api/teams` and usernames at `GET /api/users`, ordered by name. These names match the identifiers accepted when creating a rule.
- **Exceptions and boundaries** — Empty directories return empty arrays. Directory responses exclude contact information.
- **Verification** — `RecipientControllerTest` covers controller delegation; `RecipientControllerIT` checks persisted records through HTTP.

Use this file for current business behavior and agreed contracts between components. Update an entry when expected behavior or the contract changes.

Document a change in the same commit as the implementation and focused tests. For a bug fix, add a regression case when practical.

## Current rules

### Rule matching uses immutable indexed snapshots

Engine orders matches by ascending priority and then by name. It includes the first matching rule with `stopProcessing` and omits subsequent matches from both alerts and the event envelope.

Each rule groups matching events by its v1 SHA-256 fingerprint in Redis. The first occurrence starts a fixed window (60 seconds by default) and publishes `alert.created`. Subsequent occurrences publish `alert.counted` with the original alert ID and cumulative count. Control updates the count in the original alert and preserves its instructions and recipients, including when stream entries arrive out of order. The next occurrence after expiry starts a new alert.

The external Redis ingest consumer reads up to `INGEST_REDIS_BATCH_SIZE` records per batch and drains up to `INGEST_REDIS_MAX_BATCHES_PER_POLL` full batches before the polling delay. It acknowledges completed and invalid records together after each batch; processing failures remain pending and are reclaimed after `INGEST_REDIS_RECLAIM_IDLE_MS`. For valid records, the Redis Lua publisher checks that the source record is still pending, writes the event and alerts, then ACKs it in the same atomic operation. Kafka retries use a bounded Redis offset checkpoint per topic-partition (`KAFKA_OFFSET_TTL_SECONDS`, seven days by default), so replay does not append the event or increment an alert twice without a per-message marker key. `RedisExternalStreamAdapterTest`, `RedisExternalStreamAdapterIT`, and `AlertGroupingIT` cover these boundaries.

Engine rule indexes are private immutable snapshots in each replica's RAM. Control publishes each configuration update once; Redis Pub/Sub distributes it to every live replica, and the companion snapshot hash restores it after a reconnect. Redis alert deduplication keys are shared by all replicas and updated atomically. HTTP clients use the HAProxy `engine-lb`; Kafka and Redis Streams distribute records through their shared consumer groups.

Numeric equality and membership compare exact decimal values. For example, an indexed condition `{path: payload.amount, op: eq, value: 1}` matches the JSON numbers `1` and `1.0`. The JSON string `"1"` remains a separate value. `NumericRuleIngestIT` verifies decimal precision across Redis rule updates, HTTP ingest and alert publication.

- **Expected behavior** - Engine parses the `match` subtree from rule-definition v1 into an immutable condition tree. It evaluates `all`, `any`, and `not`, plus leaf operators `exists`, `eq`, `ne`, `in`, `nin`, numeric comparisons, `contains`, `startsWith`, `endsWith`, and `regex`. `CompiledPath` supports `payload` and `meta` roots, dotted keys, quoted keys, array indexes, and `[*]`. Each rule uses one best equality bucket for `all`, one bucket per indexable `any` branch, or the always-check list; every candidate still runs its full predicate.
- **Exceptions and boundaries** - Missing paths and JSON `null` produce no resolved values; leaf operators return false when no values resolve, while `exists:false` returns true. Wildcard paths use existential matching. Numeric comparisons accept numbers and strings parseable as `BigDecimal`; equality preserves JSON types and compares numbers numerically. Regex uses RE2/J `find` semantics. A new rule is compiled into a new immutable snapshot through `RuleIndexManager.accept`; the current and old snapshots remain safe for concurrent readers. Engine consumes enabled rule events from `rules.created.v1`; invalid messages are logged and ignored. The Control publisher writes the outbox as part of the creation transaction.
- **Reason** - Schema-less payloads need flexible conditions, while event evaluation should inspect only likely candidates and remain safe during rule updates.
- **Verification** - `RuleIndexTest` covers indexed `all`, indexable and fallback `any`, `in` bucket deduplication, wildcard paths, missing and wrong-type values, numeric and text operators, RE2/J, immutable hot updates, and 5,000 rules with one evaluated candidate. `RuleIndexIntegrationIT` covers parser-to-index matching and concurrent readers. `RedisRuleCreatedAdapterIT` covers event consumption, bucket creation, and snapshot recovery against Redis. Run all backend checks with `mvn -B -pl control,engine -am clean verify` inside the Java 21 Maven Docker image.

### Control owns database persistence

- **Expected behavior** - Control is the only service that reads or writes application data in PostgreSQL. It uses jOOQ for queries and writes, and Flyway for versioned schema migrations. Creating a rule stores the rule, user and team links, version history, audit entry, configuration revision, and Redis outbox event in one database transaction.
- **Exceptions and boundaries** - Invalid rule documents or unknown team/runbook references reject the request; duplicate names return `409`. Redis publishing is controlled by `RULES_REDIS_ENABLED`. If Redis is unavailable after the database transaction commits, the outbox retains the event for retry. Engine has no database connection; it maintains rule buckets only in memory.
- **Reason** - Control owns durable configuration and schema versions. Engine consumes the resulting rule definition without coupling matching to PostgreSQL or jOOQ.
- **Verification** - Control rule creation tests exercise validation, persistence, and outbox behavior. The engine Redis integration test verifies the consumer contract; the full Control-to-Engine smoke flow is documented in the root `README.md`.

### Create a rule in Control

A valid editor document creates the rule, user and team links, version 1, audit entry, configuration revision, and Redis outbox entry in one PostgreSQL transaction. Unknown references or invalid definitions reject the whole request. The original YAML is retained for editing. Rule creation succeeds independently of the engine. The root README contains the request example, response contract, and Redis setup.

### Rule-created Redis contract

- **Expected behavior** - Control publishes each created rule to `rules.created.v1`, using Redis Pub/Sub with the latest envelope per rule id in the companion hash. The JSON value contains `type`, `ruleId`, `version`, `configVersion`, `name`, `enabled`, `priority`, `teamIds`, `userIds`, `runbookId`, and `definition`. `definition` is the normalized v1 JSON document from the rule-definition schema. Engine uses `ruleId`, `enabled`, and `definition` to build matching buckets. Engine includes the configured recipients in each matched alert on `alerts.live`.
- **Exceptions and boundaries** - A newer disabled version removes the rule from the active index. Engine remembers that version even when the rule was already absent. Duplicates and older versions are ignored. Only a newer enabled version can reactivate the rule. Invalid events do not alter the current snapshot.
- **Reason** - The v1 definition schema excludes database identity and enabled state; those fields are supplied by Control in the event envelope.
- **Verification** - `RedisRuleCreatedAdapterTest` covers accepted, disabled, and malformed events. `RedisRuleCreatedAdapterIT` verifies the real Redis listener against Redis.

### Ingest accepts schema-less JSON

- **Expected behavior** - `POST /ingest` accepts any JSON value and returns `202` with an `eventId` after publishing an event envelope to `events:ingest`. HTTP envelopes have `source: null` and `adapter: "http"`. Business source fields are optional and remain in the original payload. Redis Stream entries on `ingest:external` use a `payload` field containing JSON text and an optional `source` field. Kafka values contain JSON text. Both adapters publish to the same internal stream.
- **Exceptions and boundaries** - HTTP requests must use `application/json`; malformed or empty bodies return `400`, and messages over the configured limit return `413`. The default limit is 1 MiB across the adapters. Invalid Redis and Kafka JSON records are dropped. The envelope records `eventId`, `source`, `adapter`, `receivedAt`, `contentType`, and the untouched JSON value in `payload`.
- **Reason** - The first engine slice accepts messages from the three specified transports without constraining producer payloads.
- **Verification** - Manual smoke flow is documented in the root `README.md`; automated ingest tests have not been added in this initial skeleton.

### Durable alert reception

Engine appends one JSON field `alert` per matched rule to stream `alerts.live`. Control reads the backlog, persists one inbox row per alert UUID, and acknowledges after commit. A duplicate delivery produces one stored row and one application event on successful synchronous processing. `LiveAlertSubscriptionIT` exercises backlog delivery and reclaim of a pending duplicate with PostgreSQL and Redis.

### Event Monitor read API

- **Expected behavior** — Control exposes GET `/api/monitor/messages`, `/rules`, `/runbooks`, and `/alerts`. Messages come from the configured ingest Redis stream; rules and runbooks come from PostgreSQL; alerts come from the persisted `live_alert_inbox`. Each endpoint returns at most the newest 100 records.
- **Exceptions and boundaries** — These endpoints are read-only. Missing streams and empty tables return empty arrays. Control remains the only service that queries PostgreSQL.
- **Reason** — The demo dashboard needs to inspect current Engine and Control state without bypassing Control's data ownership.
- **Verification** — `MonitorControllerIT` covers the four endpoints with Redis and PostgreSQL.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** - State when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** - Describe relevant edge cases and their exact outcomes.
- **Reason** - Record the brief requirement or team decision behind the rule.
- **Verification** - Link to the test or name the test case that checks it.

### Attached runbook instructions

Control accepts an existing active runbook name or an inline object with name, format and content on rule creation. Inline creation and outbox insertion share the rule transaction. Control accepts text, Markdown and a single valid YAML document as instruction formats. Engine renders these templates using the matched event and continues to read historical static links. The alert inbox preserves the rendered snapshot. Unassigned rules (including auto in this release) produce alerts without instructions. Flyway V6 republishes previously attached instructions with a higher rule version.

### Automatic runbook actions

An inline or named runbook can carry ordered actions with a catalog type and named string parameters. Engine renders the parameters against the triggering event. On first receipt, Control stores the rendered steps and their integration settings in the same transaction as the alert. Missing parameters mark the step unavailable and skip the rest of the sequence. A redelivery preserves the original executions.

New alerts execute configured steps in order after receipt commits. The three built-in actions and newly registered action types default to automatic execution. Explicit catalog approvals or manual types pause the sequence. Failed, uncertain or unavailable outcomes skip subsequent steps. Historical alert snapshots retain their original operator-driven behavior.

Each worker claims a step in PostgreSQL, then executes under a row lock. An expired claim can be resumed after 30 seconds using the same execution UUID as the provider idempotency key. Results and audit records commit together. Escalation assigns the alert to the configured team and sets critical severity. RunbookActionsIT and the Compose smoke script verify automatic execution, ordering and persisted results.

### Identity, memberships and rule owners

Control validates demo credentials with bcrypt and binds the user UUID to an HTTP-only server session. Each protected request resolves the current database role. Both roles can create rules, and the rule, owner ID, version and outbox record commit together. Missing sessions return 401. A MEMBER can read the team directory. A TEAM_LEADER sees and manages the team identified by their unique lead membership. Ordinary memberships grant no management rights. The membership API preserves database-assigned lead memberships. Duplicate assignments and removals are idempotent. Rule list, detail and creation responses expose the owner's ID and username. Legacy rules display System. AccountsTest and AccountPermissionsIT cover the roles, session lifecycle and HTTP boundaries.

### Python diagnostics in runbooks

Python steps pause for a signed-in operator by default. The execution snapshot records the exact source for review. A confirmed step records the username from the server session. An explicitly automatic script records `system`. The next step receives the preceding HTTP response body, Python return value, or escalation result. Each failed or timed-out execution stops the sequence and commits its bounded output to PostgreSQL. The [Python action contract](rules/python-actions.md) includes the YAML and alert-detail formats.

### Editing shared runbooks

Any signed-in user can read a full definition with `GET /api/runbooks/{id}` and save the same `kind: Runbook` resource used by `POST /api/apply` with `PUT /api/runbooks/{id}`. Send the ETag from the detail response in If-Match. An outdated tag returns 412, a missing tag returns 428 and a duplicate name returns 409. Each edit records the actor and republishes every linked rule with its new runbook snapshot in the same transaction. Alerts already in the inbox retain their instructions and action executions. RunbookEditorIT covers the HTTP and outbox contracts.

### Outbound alert delivery

Control records one delivery per new alert, recipient and enabled channel. Personal preferences override team settings. Email and SMS require opt-in and a matching severity. Missing contact details produce a skipped delivery. Control snapshots the address and body before sending, and retries transient provider errors up to five times. The default quota is ten deliveries per recipient and channel per five minutes. SMS retries reuse the delivery UUID. SMTP acceptance confirms receipt by the relay. See DeliveryQueueIT, DeliveryPolicyTest and scripts/test-notification-flow.py.

### Per-rule notification channels

A rule definition may include a nonempty, unique `channels` array containing `sms`, `email` or `ui`. Engine copies the selection into each new live alert. Control delivers through selected, enabled channels subject to the existing recipient preferences, severity thresholds and transport rules. Omitting the field preserves existing routing for older rules and pending alerts.
