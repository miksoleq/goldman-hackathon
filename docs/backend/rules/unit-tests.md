# Unit Tests

- Test observable behavior through the unit's public interface. Use a descriptive name and a clear arrange, act, and assert structure with one scenario per test.
- Cover the path that delivers the expected result and the edge or failure cases that can break it. For a bug fix, add a focused regression case when practical and verify that it fails before the fix.
- Use the established test framework and helpers. Stub external dependencies at their boundary. Keep clocks, randomness, and input fixtures controlled where they affect assertions.
- Keep unit tests deterministic and independent of the network, running services, test order, and shared mutable state. Use [integration tests](integration-tests.md) when correctness depends on framework wiring or real infrastructure.
