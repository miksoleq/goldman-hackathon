# E2E project instructions

Read the [E2E guidelines](README.md) before working in this repository. Read the [Git workflow](GIT-WORKFLOW.md) for all Git and PR work. Apply these instructions to generated code too.

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

Keep unit and integration coverage for application changes in the owning backend or frontend repository. When changing reusable test tooling here, add or update unit tests for its logic and integration tests for affected boundaries such as API fixture setup. Run the relevant suites before handoff and report the commands, results, and any blockers.

After the first successful run, record prerequisites, required environment variable names, and verified commands here. Update them when setup changes.

### Isolated Compose end-to-end suite

From the repository root, install the Control Panel dependencies with `npm --prefix control-panel ci`. With Docker Compose, Python 3, Node.js and Chrome available, set `E2E_USERNAME` and `E2E_PASSWORD` for a seeded demo account and run `scripts/test-e2e.sh`. Set `CHROME_BIN` if Chrome is not in its standard location. The suite uses disposable Compose volumes and chooses local ports automatically. See [scenarios](SCENARIOS.md) for coverage, including SMS and email.
