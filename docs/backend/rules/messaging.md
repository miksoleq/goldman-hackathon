# Messaging and Background Jobs

- Add asynchronous processing when the challenge needs it. Define the payload, who produces and consumes it, and how failures are observed. Map transport payloads to application inputs at the boundary.
- If delivery can repeat, use a stable event ID or business key to make the effect idempotent. Handle delayed or reordered messages if ordering is not guaranteed and affects the result.
- Use bounded retries for transient failures. Make permanent failures visible through the project's existing failure path and preserve enough context to diagnose them safely.
- If saving state and publishing an event must succeed together, define how publication recovers after a crash. Use a transactional outbox when that guarantee is needed and the chosen store can persist both changes atomically.
- Verify the main handler path and test duplicate delivery or failed processing when it could corrupt state or break the demo.
