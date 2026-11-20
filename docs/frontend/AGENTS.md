# Frontend project instructions

Read the [frontend guidelines](README.md) before working in this repository. Apply these instructions to generated code too.

## Shared project practices

- Agree on the demo journey, shared interfaces, and an example input and expected output before splitting work. Keep the implementation small and record actual setup and verified build, run, and test commands in this file.
- Keep secrets and sensitive data out of the repository, logs, fixtures, and client code. Use synthetic data for demos and tests.
- Write clear code with meaningful names and simple control flow. Keep business rules separate from external adapters where useful. Verify changed behavior with focused checks and report results and gaps.
- Record current business behavior and shared contracts in [business rules](business-rules.md) with implementation and focused verification. Coordinate interface changes with the other repositories.
- Pull before a batch of work. Push small, working changes; group related edits into coherent, reviewable commits rather than committing each tiny edit. Use short imperative English commit messages. Omit AI authorship trailers.
- Before opening every PR, create a note in `business-decisions/` using the [template](business-decisions/TEMPLATE.md), including rationale and impact; explicitly record when no business decision was needed. Link the note in the PR description.
- Rehearse the real demo flow, document required services and configuration, and keep a known-good version available.

## Build, run, and test

After the first successful run, record prerequisites, required environment variable names, and verified commands here. Update them when setup changes.
