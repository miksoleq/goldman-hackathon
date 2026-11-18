# Goldman Sachs Hackathon

## Project Overview

- When the brief is released, record the judging criteria, the demo scenario, and the smallest end-to-end flow that delivers a result the judges can see. Build that flow first and prioritize additions against the judging criteria.
- Start with the JVM if it fits the brief and both teammates can work quickly with it. Use Docker when it simplifies starting local services. If the project needs a database, start with either PostgreSQL or MongoDB and choose based on the data model and the queries the demo needs.

## Build, Run & Test Commands

- After the first successful local run, add the required tool versions and services, any required environment variable names, and the verified commands to build, run, test, and reproduce the demo. Check that both teammates can follow those steps from a fresh checkout.
- For a JVM project, use the selected build tool's repository wrapper. Update this section when prerequisites or commands change.

## Agent Workflow

- Before editing, read the relevant entry point, its callers, and the existing tests. Use the brief and the code to resolve questions first. Ask the team when an open decision materially affects expected behavior, a shared interface, or the demo.
- Check the working tree before editing and preserve your teammate's changes. For work that spans both areas, agree on the shared contract and how to verify it.
- Verify behavior changes with focused checks before handing them off. If the demo flow changed, run its smoke check. Report what changed, which checks ran and their results, any checks you could not run, and any setup changes your teammate needs.
- The team may use AI tools throughout the hackathon. Keep secrets outside the repository.

## Project Rules

Open each relevant guide before editing in that area. Apply technology-specific guidance when the project uses that technology.

| Rule file | Read it when you work on |
| --- | --- |
| [Architecture](docs/rules/architecture.md) | Project layout, package or module boundaries, dependency direction, external adapters |
| [Clean code](docs/rules/clean-code.md) | Writing or refactoring application or test code, naming, error-prone control flow |
| [REST API](docs/rules/rest-api.md) | Endpoints, request and response models, HTTP status codes, shared API contracts |
| [Persistence](docs/rules/persistence.md) | Repositories, PostgreSQL or MongoDB, queries, transactions, migrations |
| [Search](docs/rules/search.md) | Search queries, pagination, indexing, or search projections |
| [Messaging](docs/rules/messaging.md) | Events, queues, background jobs, retries, duplicate delivery |
| [Configuration and demo](docs/rules/configuration-and-demo.md) | Environment variables, Docker, sample data, smoke checks, demo fallback |
| [Error handling and security](docs/rules/error-handling-and-security.md) | Validation, exceptions, authorization, logging, secrets, external HTTP calls |
| [Unit tests](docs/rules/unit-tests.md) | Testing challenge logic, regressions, edge cases, or test doubles |
| [Integration tests](docs/rules/integration-tests.md) | Testing real infrastructure, public entry points, or contracts across components |
| [Git workflow](docs/rules/git-workflow.md) | Frequent pushes to `main`, parallel work, optional reviews and CI |
