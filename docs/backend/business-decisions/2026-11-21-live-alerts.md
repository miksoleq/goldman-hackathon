# Live alert creation and recipients

Control resolves `route.teams` by team name and `route.users` by username in the rule creation transaction. Either list can be omitted; at least one recipient is required. Specifying both targets the union. For a personal alert, use only users:

```yaml
name: Payment timeout
title: Timeout w payments
body: "Workflow {{payload.workflow}} przekroczył limit czasu."
severity: high
route: {users: [alice]}
match: {path: payload.code, op: eq, value: TIMEOUT}
```

The user must already exist. Team-only definitions continue to work. Migration V3 stores explicit user assignments in `rule_users`. Both arrays of resolved IDs travel in the rule creation outbox record. An older record without `userIds` means an empty list.

For every incoming event, Engine emits one `alert.created` per matched published rule. Each has a new alert UUID, the ingestion event UUID, both recipient arrays, a rendered title, body and severity, count 1, and the event receipt time in UTC. The all-zero UUID in `runbookId` is reserved for unassigned runbooks. Rule creation uses Kafka as before. Live delivery uses Redis Pub/Sub channel `alerts.live`. The stream append and alert publications execute in one Redis script.

Control subscribes to this channel and exposes received messages as typed Spring application events. Redis Pub/Sub delivers to connected consumers only. This change covers live reception; alert history, notifications and runbook assignment are separate flows.

Verify with `docker compose --profile test run --rm backend-tests`. Enable the Kafka rule feed with `RULES_KAFKA_ENABLED=true` when running the services.
