# Frontend project instructions

Read the [frontend guidelines](README.md) before working in this repository. Read the [Git workflow](GIT-WORKFLOW.md) for all Git and PR work. Apply these instructions to generated code too.

## Shared project practices

- Before editing, inspect the relevant code, callers, and existing tests. Check the working tree and preserve teammates' changes. Resolve questions from the brief and code; ask the team when expected behavior or a shared contract remains unclear.
- Agree on the demo journey, shared interfaces, and an example input and expected output before splitting work. Keep the implementation small and record actual setup and verified build, run, and test commands in this file.
- Keep secrets and sensitive data out of the repository, logs, fixtures, and client code. Use synthetic data for demos and tests.
- Write clear code with meaningful names and simple control flow. Keep business rules separate from external adapters where useful. Verify changed behavior with focused checks and report results and gaps.
- Before adding a function, component, or helper, search for existing code with the same responsibility and reuse or adapt it when it fits without changing behavior unexpectedly.
- During refactoring, remove code, imports, and dependencies made obsolete by the change after checking references and relevant entry points, including dynamic uses and public contracts. Preserve anything whose use is uncertain and flag it for follow-up.
- Record current business behavior and shared contracts in [business rules](business-rules.md) with implementation and focused verification. Coordinate interface changes with the other repositories.
- Rehearse the real demo flow, document required services and configuration, and keep a known-good version available.

## Build, run, and test

For features and bug fixes, add or update automated unit tests for changed UI logic and integration tests for interactions between components, forms, and the API client. Keep the tests alongside the change, run the relevant suites before handoff, and report the commands, results, and any blockers.

### Event Monitor

- **Prerequisites:** Node.js 24 and npm 11. The Control service, PostgreSQL, and Redis provide monitor data. Docker Compose reads local settings from .env.
- **Install:** Run npm ci from frontend/.
- **Unit tests:** Run npm test from frontend/.
- **Production build:** Run npm run build from frontend/.
- **Browser development:** Start Control with docker compose up --build -d control, then run npm run dev from frontend/. Vite serves the UI on port 5173 and proxies /api requests to Control on port 8082.
- **Full demo:** Run docker compose up --build -d. The frontend is served on http://localhost:8080 by default. FRONTEND_PORT changes its host port.
- **Browser verification:** Use playwright-cli open http://localhost:8080, navigate the Messages, Rules, Runbooks, and Alerts tabs, inspect an item, then resize the page to a narrow viewport.

The npm unit and build commands and the full backend Compose test suite passed before the latest rebase. Full Compose services are running, and `playwright-cli open http://localhost:8080` loaded the Event Monitor page. Cross-section interaction, mobile layout, console, and network checks were not completed after the rebase.
