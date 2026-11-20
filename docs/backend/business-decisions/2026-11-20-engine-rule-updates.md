# Business decision note

- **Date:** 2026-11-20
- **Related PR:** Not opened yet.
- **Context:** Control needs to notify Engine when a rule is created so Engine can compile its condition and add it to the in-memory matching index without database access.
- **Decision:** Use Kafka topic `rules.created.v1`. The message key is the rule id; the JSON value follows Control's outbox event: it has `type: "rule.created"`, `ruleId`, `version`, `configVersion`, `name`, `enabled`, `priority`, `teamIds`, `runbookId`, and `definition`. `definition` is the v1 JSON document described by `rule-definition.v1.schema.json`. Engine ignores disabled rules and invalid messages, and publishes a new immutable index snapshot for each accepted enabled rule. The consumer is controlled by `RULES_KAFKA_ENABLED` and defaults to disabled.
- **Rationale:** The v1 definition schema intentionally has no database id or enabled flag. Keeping those columns in the event envelope lets the nested `definition` stay schema-compatible and lets Engine avoid indexing disabled rows. Kafka separates the Control publisher from Engine's matcher.
- **Impact:** Control's outbox publisher and Engine's Kafka consumer use the same versioned topic. Both sides have integration tests. On rule update or deletion, Control will need to publish a corresponding event contract or send a full rule snapshot; this topic currently covers creation events and duplicate delivery by id.
