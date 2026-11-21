# Match events by JSON structure

Control accepts a structural JSON Schema in a `jsonSchema` condition through the existing YAML rule creation endpoint. Both services use the same rule contract. The existing JSONB definition, version history and rule-created envelope carry the schema without a database migration.

For example, POST this document to `/api/rules` as `application/yaml`, using an existing team name:

```yaml
name: Payment structure
title: Payment received
route: {teams: [payments]}
match:
  path: payload
  op: jsonSchema
  value:
    type: object
    required: [amount, currency]
    properties:
      amount: {type: number}
      currency: {type: string}
    additionalProperties: false
```

Sending `{"amount":42,"currency":"PLN"}` to Engine's `POST /ingest` matches. A missing currency, a quoted amount or an extra property fails this rule. In either case ingestion publishes the envelope to the internal Redis stream with `matchedRuleIds`. HTTP returns the existing event ID. Set `RULES_REDIS_ENABLED=true` and configure both services to use the same Redis instance and channel.

Each schema node requires a single JSON Schema `type`. The supported structural keywords are `properties`, `required`, boolean `additionalProperties` and `items`. Extra fields are allowed by default. Properties are optional unless listed in `required`. A missing or null target path never matches; nested nulls can be described by `{type: "null"}`. Unknown keywords are rejected. Schema conditions work inside `all`, `any` and `not`. Combine one with an equality condition under `all` to narrow candidate selection.

Run both modules and their shared dependency with `docker compose --profile test run --rm backend-tests`.
