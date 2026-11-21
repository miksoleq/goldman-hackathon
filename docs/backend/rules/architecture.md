# Architecture

- Start with the smallest project layout that lets both teammates own distinct areas and deliver the first end-to-end flow. Split code into packages or modules when the boundary helps ownership, testing, or deployment.
- Keep challenge rules and decisions independent of HTTP, storage, queues, and framework types where that separation helps. Let entry points translate inputs and call application logic. Keep SDK types, generated records, and storage models inside the code that uses them.
- Introduce an interface at an external boundary when it enables independent work, a focused test, or an actual second implementation. Reuse existing project conventions when adding dependencies and adapters.
- After the first integration, document the actual entry points, package or module responsibilities, and dependency direction here. Record any setup or demo gotchas in the [configuration and demo guide](configuration-and-demo.md).

## Current backend modules

- `control` is the Spring Boot owner of PostgreSQL persistence and schema migrations. Flyway validates and applies migrations when it starts, and `GET /internal/migrations` reports the schema version. jOOQ persists rules, routing, version history, audit data, and the rule outbox. Rule creation is available through `POST /api/rules`.
- `engine` owns the HTTP, Redis, and Kafka event ingestion adapters, plus the transport-independent rule AST, path compiler, immutable `RuleIndex` snapshots, and atomic `RuleIndexManager`. It accepts schema-less JSON and has no PostgreSQL dependency. When enabled, `RedisRuleCreatedAdapter` consumes rule-created events from `rules.created.v1` and passes enabled v1 definitions to `RuleIndexManager.accept`.
- `control` and `engine` are separate runnable modules. Control is the only component that persists application data. The Control outbox publisher sends each created rule to `rules.created.v1`; Engine builds its active rule buckets in memory. Every Engine replica subscribes via Redis Pub/Sub and recovers missed creations from the Redis hash `<channel>:snapshot` after subscription.
