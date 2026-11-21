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

For every incoming event, Engine emits one `alert.created` per matched published rule. Each has a new alert UUID, the ingestion event UUID, both recipient arrays, a rendered title, body and severity, count 1, and the event receipt time in UTC. The all-zero UUID in `runbookId` is reserved for unassigned runbooks. Rule creation now uses the Redis Pub/Sub channel `rules.created.v1`. Engine appends each JSON alert to the Redis Stream `alerts.live` under field `alert` in the same Lua script as the ingest stream append. Deploy the updated Engine and Control together when switching from Pub/Sub. Previous live publications have no replay history.

Control replicas share consumer group `control`, created at offset `0-0` to include alerts written before startup. Control stores the complete v1 envelope in PostgreSQL table `live_alert_inbox` (Flyway V5). The primary key is `alert_id`. Only the first insert publishes a typed Spring event inside the transaction. After commit Control acknowledges the stream entry. A restart between commit and acknowledgement can replay the entry safely. Pending entries can be claimed by another replica after 30 seconds. Unreadable entries remain pending and their stream IDs are logged for investigation. Use `XPENDING alerts.live control` to inspect pending deliveries. Acknowledged stream entries remain available for inspection.

Verify with `docker compose --profile test run --rm backend-tests`. Compose enables the Redis rule feed by default.
