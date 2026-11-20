# Architecture

- Start with the smallest project layout that lets both teammates own distinct areas and deliver the first end-to-end flow. Split code into packages or modules when the boundary helps ownership, testing, or deployment.
- Keep challenge rules and decisions independent of HTTP, storage, queues, and framework types where that separation helps. Let entry points translate inputs and call application logic. Keep SDK types, generated records, and storage models inside the code that uses them.
- Introduce an interface at an external boundary when it enables independent work, a focused test, or an actual second implementation. Reuse existing project conventions when adding dependencies and adapters.
- After the first integration, document the actual entry points, package or module responsibilities, and dependency direction here. Record any setup or demo gotchas in the [configuration and demo guide](configuration-and-demo.md).
