# Architecture

- Start with the smallest project layout that lets both teammates own distinct areas and deliver the first end-to-end flow. Split code into packages or modules when the boundary helps ownership, testing, or deployment.
- Keep challenge rules and decisions independent of HTTP, storage, queues, and framework types where that separation helps. Let entry points translate inputs and call application logic. Keep SDK types, generated records, and storage models inside the code that uses them.
- Introduce an interface at an external boundary when it enables independent work, a focused test, or an actual second implementation. Reuse existing project conventions when adding dependencies and adapters.
- After the first integration, document the actual entry points, package or module responsibilities, and dependency direction here. Record any setup or demo gotchas in the [configuration and demo guide](configuration-and-demo.md).

## Current backend modules

- `control` is the Spring Boot owner of PostgreSQL schema migrations. Flyway validates and applies migrations when it starts, and `GET /internal/migrations` reports the schema version. Configuration CRUD and configuration-version updates are future work.
- `engine` owns the HTTP, Redis, and Kafka ingestion adapters. It currently accepts schema-less JSON and has no PostgreSQL dependency.
- `control` and `engine` are separate runnable modules; neither currently calls the other. The database schema is shared infrastructure, but this initial engine slice does not read or write it.
