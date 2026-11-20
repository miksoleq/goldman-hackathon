# Integration Tests

- Exercise the normal entry point for the boundary being tested, such as an HTTP route, a repository call, or a message handler. Assert the observable result, including a meaningful failure case if it affects the contract.
- Run the component whose behavior you are verifying against the real database, broker, or search engine when practical. Stub unrelated external services with controlled responses.
- Use isolated fixtures and clean up test-owned data. Test migrations on the target database when schema behavior matters. For asynchronous effects, wait for the condition with a bounded timeout.
- Prioritize one end-to-end path that supports the demo and integration cases that unit tests cannot prove. Coordinate browser-based E2E coverage with the E2E repository. Document any services and commands needed to run them in [AGENTS.md](../AGENTS.md#build-run-and-test).
