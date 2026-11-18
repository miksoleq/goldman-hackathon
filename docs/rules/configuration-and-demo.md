# Configuration and Demo

## Local setup

- Document required runtime versions, services, ports, and environment variable names alongside the verified commands in [AGENTS.md](../../AGENTS.md#build-run--test-commands). Use placeholder values in tracked examples and load secrets from the local environment or the configured secret store.
- Keep local startup repeatable. Start the same service configuration on both laptops and make demo data safe to load repeatedly.

## Rehearsal

- Use synthetic data and run the demo through its normal entry point. A smoke check should exercise one complete path and verify an observable result.
- Rehearse from a fresh checkout on both laptops. Note any setup quirks here, then tag a rehearsed commit when you need a known-good demo version.
- If the demo relies on an external service, rehearse a labeled fixture mode when the flow can run with recorded sample data. Document how to select that mode and what it simulates.
