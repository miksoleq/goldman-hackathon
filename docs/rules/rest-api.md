# REST API

## Contract

- Agree on the method, path, request, response, and expected errors before splitting client and server work. Share one example request and response in the project's contract or pull request.
- Use explicit request and response models at the HTTP boundary. Coordinate breaking payload or status changes with the caller.
- Use consistent status codes and a stable error body. Use `400` for invalid input, `404` for a missing resource, and `409` for a state conflict when applicable.

## Implementation

- Keep handlers focused on validation, calling the use case, and mapping the result. Apply [error handling and security](error-handling-and-security.md) at the boundary.
- Verify the status and response shape through the real route for the path the demo uses and for an important failure case. Bound page sizes for list endpoints that can return many records.
