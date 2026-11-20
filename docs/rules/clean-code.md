# Clean Code

Use Robert C. Martin's *Clean Code* as a guide for application and test code.

- Name variables and functions after their role or intent. Give each function one clear responsibility and keep control flow easy to follow. Use comments to explain the reason for a decision the code cannot express.
- Name values that carry business meaning. Prefer immutable values and make state changes explicit. For JVM code, use types that reflect the domain, such as `BigDecimal` with explicit rounding for money.
- Follow the surrounding code and use the project's formatter and checks when configured. Remove code made obsolete by the change and keep unrelated edits out of the diff.
- Before adding a function, component, or helper, search for existing code with the same responsibility and reuse or adapt it when it fits without changing behavior unexpectedly.
- During refactoring, remove code, imports, and dependencies made obsolete by the change after checking references and relevant entry points, including dynamic uses and public contracts. Preserve anything whose use is uncertain and flag it for follow-up.
- Prefer straightforward implementations. Extract an abstraction when it makes an existing use case simpler. Preserve observable behavior during refactors and flag unrelated bugs in the handoff.
