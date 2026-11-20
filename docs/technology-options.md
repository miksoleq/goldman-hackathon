# Technology Options

The tools below are examples to consider once the challenge is known. Choose any language, framework, storage, or deployment approach that fits the brief and helps both teammates deliver and verify the demo.

| Area | Example options | When they might help |
| --- | --- | --- |
| Language and build | Java with Maven Wrapper or Gradle Wrapper as the starting point. Kotlin, Python, and TypeScript are also options. | Use a language both teammates can run and debug quickly. A small script can be enough for data processing or a command-line task. |
| Application and API | Spring Boot with Spring MVC or WebFlux, Jackson for JSON, and OpenAPI with optional Swagger UI. | Use HTTP when components or users need an API. Consider WebFlux if reactive streaming or I/O is part of the solution and the team is comfortable with it. |
| Demo interface | A CLI, HTML with Thymeleaf, or a React UI. | Match the interface to the task. A form, a table, or command output can show the result with minimal setup. |
| Storage and migrations | In-memory data or local files, PostgreSQL with Spring JDBC or jOOQ and Flyway, or MongoDB with Spring Data. | Use PostgreSQL when relational queries, constraints, or transactions matter. Consider MongoDB for document-shaped data that is usually read together. |
| Data exchange | CSV, JSON with Jackson, or Excel with Apache POI. | Match the format required by the brief or the people consuming the output. |
| Search | Queries in the primary data store, Apache Lucene, or Elasticsearch. | Consider a dedicated index for full-text search, relevance ranking, or indexing needs beyond the primary store. |
| Background work and messaging | An in-process job, RabbitMQ, or Apache Kafka. JSON or Avro for messages. | Consider a queue for background tasks that need reliable delivery or Kafka when the task benefits from a replayable event stream. |
| External integrations | Spring RestClient, WebClient, or OkHttp. Resilience4j for circuit breakers or retry policies. | Use a client the team knows. Add resilience measures when the integration's failure modes call for them. |
| Authentication | Spring Security, with Keycloak or another available identity provider when applicable. | Use the authentication approach required by the brief or deployment environment. |
| Testing | JUnit and Mockito for Java. Kotest and MockK for Kotlin. Testcontainers, WireMock, or MockServer for integration tests. | Pick a unit test framework for the core rules. Use containers when real database or broker behavior matters, and HTTP stubs for external services. |
| Observability | Structured logs, Spring Boot Actuator, Micrometer, Prometheus, or Grafana. | Start with enough logging to debug the demo. Add health checks or metrics when they help verify a requirement. |
| Local run and delivery | A local process, Docker or Podman, Compose for multi-service setups, and GitHub Actions for CI. | Choose a workflow that runs on both laptops. Add automation when it saves time or the submission needs a reproducible build. |

Start with the smallest combination that supports one end-to-end flow. Add a service or dependency when it solves a specific challenge, integration, or demo need. Once both teammates can run the chosen stack, record the verified commands in the relevant repository's `AGENTS.md`.
