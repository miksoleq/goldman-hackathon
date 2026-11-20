# Error Handling and Security

## Errors

- Distinguish invalid input, expected business failures, and unexpected service failures. Translate them at the entry point into consistent, actionable errors and keep the underlying cause available for debugging.
- Handle errors where recovery or translation is possible. Log once at that boundary with enough context to diagnose the issue and omit credentials, sensitive payloads, and internal details from user-facing responses.

## Trust boundaries

- Validate and bound external inputs. When access depends on identity or ownership, enforce it on the server using the authenticated caller and the requested resource.
- Store secrets outside the repository and use synthetic data for local tests and demos. Keep credentials and sensitive personal data out of logs and fixtures.
- Set explicit timeouts for outbound requests. When input can influence an outbound destination, restrict allowed destinations and validate any redirects.
