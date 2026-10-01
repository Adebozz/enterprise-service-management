# ADR-001: Modular monolith

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

The platform covers several related domains: incidents, service requests, problems, changes, SLAs,
approvals, catalogue, audit and reporting. They share a lot of data. Comments, audit, SLA and
assignment all attach to the same work items, and many operations must be atomic. For example,
changing a ticket's status must update the SLA clock and write an audit event in one transaction.
The team is one developer, and the expected load is that of a medium-sized organisation's internal
tool.

## Decision

Build **one deployable Spring Boot application** organised into **domain modules** (`auth`, `user`,
`team`, `ticket`, `audit`, later `sla`, `catalogue`, `approval`, ...), packaged by feature, with
package-private internals and an acyclic dependency structure. Modules that would otherwise depend
on each other circularly communicate through in-process Spring application events.

## Alternatives considered

- **Microservices.** These would bring independent scaling and deployment, but at the cost of
  network failure modes, distributed transactions (or sagas) for operations that are naturally one
  database transaction, and separate pipelines, observability and data stores for each service.
  None of those costs buys anything at this scale or team size.
- **Traditional layered monolith** (global `controller`/`service`/`repository` packages). Simple
  at first, but nothing stops any class from calling any other, and features end up spread across
  the whole codebase.
- **Strict hexagonal / clean architecture.** Good isolation, but at this size the ports, adapters
  and duplicated models are mostly ceremony.

## Consequences

- Single transaction boundary, simple deployment, and easy local development and testing.
- Module boundaries depend on discipline plus package-private visibility. An automated boundary
  check (Spring Modulith or ArchUnit) is planned so this becomes enforced rather than claimed.
- If one module ever needed independent scaling (reporting is the likeliest candidate), clear module
  boundaries make extracting it possible later.
