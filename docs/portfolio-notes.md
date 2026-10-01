# Portfolio notes

Running log of **implemented** engineering work, used later for CV bullets and interview STAR
stories. Only things that exist in the code are recorded here. No invented metrics.

---

## M0: Bootstrap (2026-10-01)

**What was built**
- Monorepo with a Spring Boot 4.1 / Java 21 backend, PostgreSQL 17 through docker-compose, and a
  Flyway migration pipeline.
- One error contract for the whole API: RFC 9457 ProblemDetail plus `code`, `timestamp` and
  `correlationId` extensions. It covers business errors, validation, Spring MVC errors (404/405/
  malformed JSON) and Spring Security 401/403.
- Request correlation IDs: a servlet filter puts an ID in the SLF4J MDC, so every log line and
  every error response for a request share it.
- Integration tests against real PostgreSQL via Testcontainers, sharing one cached Spring context
  and container. Unit and integration tests are split between Surefire (`*Test`) and Failsafe
  (`*IT`), with a JaCoCo coverage report.

**Decisions worth discussing**
- *Security errors use the same renderer as everything else.* Spring Security's entry point and
  access-denied handler delegate to MVC's `HandlerExceptionResolver`, so one `@RestControllerAdvice`
  renders every error. There is one source of truth and no duplicated JSON writing.
- *A client-supplied correlation ID is validated* (`[A-Za-z0-9-]{8,64}`), which blocks log injection
  through the `X-Request-Id` header. This is covered by a parameterized test that includes a
  newline-injection case.
- *`open-in-view` is disabled* so lazy-loading problems show up in tests instead of hiding as N+1
  queries in production.
- *Spring Boot 4 instead of 3.x* (ADR-003). The original specification said 3.x, but Initializr no
  longer offers 3.x for new projects.

**Bug found and fixed**
- Spring MVC's 404 (unknown route) and 405 (wrong method) responses were missing the custom `code`
  field. Cause: for these exceptions, `ResponseEntityExceptionHandler.handleExceptionInternal`
  receives `body == null` and creates the ProblemDetail **inside** `super`, so enriching the
  incoming body did nothing. Fix: call `super` first, then enrich the returned body. An integration
  test caught this, and both cases are now covered.

**Tests:** 7 unit tests (correlation filter) and 14 integration tests (boot + migrations + Postgres
version, health probes, actuator exposure, OpenAPI, and the full error contract including the
"no internal details leaked on 500" case).
