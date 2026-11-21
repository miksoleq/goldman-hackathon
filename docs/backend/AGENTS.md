# Backend project instructions

Read the relevant [backend rules](README.md) before working in this repository. Read the [Git workflow](GIT-WORKFLOW.md) for all Git and PR work. Apply these instructions to generated code too.

## Shared project practices

- Use jOOQ or Hibernate for application access to PostgreSQL. Follow the [persistence guidelines](rules/persistence.md) when writing or modifying queries.

- Before editing, inspect the relevant code, callers, and existing tests. Check the working tree and preserve teammates' changes. Resolve questions from the brief and code; ask the team when expected behavior or a shared contract remains unclear.
- Agree on the demo journey, shared interfaces, and an example input and expected output before splitting work. Keep the implementation small and record actual setup and verified build, run, and test commands in this file.
- Keep secrets and sensitive data out of the repository, logs, fixtures, and client code. Use synthetic data for demos and tests.
- Write clear code with meaningful names and simple control flow. Keep business rules separate from external adapters where useful. Verify changed behavior with focused checks and report results and gaps.
- Before adding a function, component, or helper, search for existing code with the same responsibility and reuse or adapt it when it fits without changing behavior unexpectedly.
- During refactoring, remove code, imports, and dependencies made obsolete by the change after checking references and relevant entry points, including dynamic uses and public contracts. Preserve anything whose use is uncertain and flag it for follow-up.
- Record current business behavior and shared contracts in [business rules](business-rules.md) with implementation and focused verification. Coordinate interface changes with the other repositories.
- Rehearse the real demo flow, document required services and configuration, and keep a known-good version available.

## Build, run, and test

For every feature or bug fix, add or update automated [unit tests](rules/unit-tests.md) for changed logic and [integration tests](rules/integration-tests.md) for affected boundaries. For example, test rule validation in isolation and exercise rule creation through HTTP against PostgreSQL and Redis. Keep the tests alongside the change, run both relevant suites before handoff, and report the commands, results, and any blockers.

After the first successful run, record prerequisites, required environment variable names, and verified commands here. Update them when setup changes.

### Local database and control

- **Prerequisites:** Docker Desktop with Docker Compose v2. PostgreSQL 17 runs in Compose and stores data in the named `postgres_data` volume.
- **Environment:** copy `.env.example` to `.env`; set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` there. The example defaults to database/user `alerting` and host port `55432`. Compose passes `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` to `control`; do not commit `.env`.
- **Start:** `docker compose up --build -d postgres control`.
- **Check migrations:** `Invoke-RestMethod http://localhost:8082/internal/migrations`.
- **Inspect history:** `docker compose exec -T postgres psql -U alerting -d alerting -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"`.
- **Verified in this workspace:** `docker compose build control`, `docker compose up -d --force-recreate control`, the migration endpoint above, and the history query. Existing schema version `1` validated successfully and was not reapplied.

### Control persistence tests

Run `mvn -pl control test` from `backend/` for the unit suite (JDK 25). For the rule transaction integration test, point `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` at a disposable PostgreSQL database and run `mvn -pl control -Dtest=RulePersistenceIT test`. Flyway creates the schema. The test writes fixtures and inspects the pending outbox, so use a database dedicated to the test with no other publisher running.

### Regenerate the control jOOQ model

Control uses the jOOQ DSL and the generated tables in `com.goldman.alerting.control.db`. From `backend/`, run `mvn -pl contracts -am install` to make the shared JAR available to the generator. After a Flyway schema change, apply the migrations to a disposable PostgreSQL database. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` to that database, then run `mvn -pl control -Pjooq-codegen generate-sources` from `backend/`. Include the regenerated Java files with the migration. Ordinary builds use those files directly.

### Runbook action checks

With Java 21, Maven 3.9, a dedicated PostgreSQL 17 database and Redis, run `mvn -pl control,engine -am clean verify` in `backend/`. Set the three datasource variables listed above and `REDIS_PORT` when using a dedicated Redis port. From the repository root run `python3 -m unittest discover -s scripts/workflow-demo` for provider eligibility and idempotency. The Compose workflow is exercised by `./scripts/test-alert-flow.sh`.

### Account and membership checks

Use the isolated PostgreSQL/Redis verification command above. AccountsTest and AccountPermissionsIT exercise login, logout, 401, 403, team membership and server-assigned ownership. The Compose smoke script signs in as the seeded demo member before creating its rules.
