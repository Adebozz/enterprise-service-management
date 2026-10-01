# ADR-003: Spring Boot 4.x on Java 21

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

The original specification asked for "current stable Spring Boot 3.x". When the project was
bootstrapped (October 2026), Spring Initializr offered only Spring Boot 4.0.x and 4.1.x (default
4.1.1). The 3.x line is no longer offered for new projects. Starting a new codebase on an
unsupported line would mean an early forced migration and would look out of date to reviewers.

## Decision

Use **Spring Boot 4.1.x** (Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3,
Testcontainers 2) on **Java 21 LTS**.

## Alternatives considered

- **Spring Boot 3.5.x.** It has more tutorials and Q&A material, but it is outside open-source
  support for a brand-new project.
- **Java 25 (also LTS).** Newer, but Java 21 is the requested baseline and the most widely
  supported runtime across AWS base images and tooling. Upgrading later is low-risk.

## Consequences

- Boot 4 uses modular starters (for example `spring-boot-starter-webmvc`,
  `spring-boot-starter-flyway`) and moved some test packages
  (`org.springframework.boot.webmvc.test.autoconfigure`). Many online examples use the 3.x names.
- Jackson 3 lives in the `tools.jackson` package, not `com.fasterxml.jackson`.
- Tests use `MockMvcTester`, the AssertJ-based MockMvc API.
