# Frontend project instructions

Read the [frontend guidelines](README.md) before working in this repository. Read the [Git workflow](GIT-WORKFLOW.md) for all Git and PR work. Apply these instructions to generated code too.

## Shared project practices

- Before editing, inspect the relevant code, callers, and existing tests. Check the working tree and preserve teammates' changes. Resolve questions from the brief and code; ask the team when expected behavior or a shared contract remains unclear.\n- Agree on the demo journey, shared interfaces, and an example input and expected output before splitting work. Keep the implementation small and record actual setup and verified build, run, and test commands in this file.
- Keep secrets and sensitive data out of the repository, logs, fixtures, and client code. Use synthetic data for demos and tests.
- Write clear code with meaningful names and simple control flow. Keep business rules separate from external adapters where useful. Verify changed behavior with focused checks and report results and gaps.
- Before adding a function, component, or helper, search for existing code with the same responsibility and reuse or adapt it when it fits without changing behavior unexpectedly.
- During refactoring, remove code, imports, and dependencies made obsolete by the change after checking references and relevant entry points, including dynamic uses and public contracts. Preserve anything whose use is uncertain and flag it for follow-up.
- Record current business behavior and shared contracts in [business rules](business-rules.md) with implementation and focused verification. Coordinate interface changes with the other repositories.
- Rehearse the real demo flow, document required services and configuration, and keep a known-good version available.

## Build, run, and test

After the first successful run, record prerequisites, required environment variable names, and verified commands here. Update them when setup changes.
