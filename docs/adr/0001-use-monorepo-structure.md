# ADR-0001: Use Monorepo Structure

## Status
Accepted

## Context
Kinetix is a multi-module system with Kotlin services, a Python risk engine, a React frontend, shared Protocol Buffer definitions, and infrastructure configuration. We need to decide between a monorepo (single repository) and polyrepo (one repository per service).

## Decision
Use a single monorepo for all components.

## Applies when
- Proposing a new service, library, or module.
- Making a change that crosses language boundaries (proto + Kotlin + Python + UI).
- Adding shared code in `common/`, `proto/`, or a Gradle convention plugin.

## Rules
- **DO** add new services as new Gradle modules in this repository. Never propose splitting them into a separate repo.
- **DO** land causally related cross-language changes (e.g. a proto change + its Kotlin client + its Python server) in a single commit — that is the whole point of the monorepo.
- **DO** put shared Kotlin code in `common/` only when it is used by ≥2 services. Single-consumer code stays inside that service.
- **DO** use the existing Gradle convention plugins (`kinetix.kotlin-application`, `kinetix.kotlin-testing`, etc.) for new modules. Don't duplicate their behaviour in per-service `build.gradle.kts`.
- **DON'T** introduce a second build tool for Kotlin code. Python (`uv`) and UI (`npm`) are the only sanctioned non-Gradle build environments.

## Consequences

### Positive
- Atomic commits across service boundaries (e.g., proto changes + Kotlin + Python in one commit)
- Shared build logic via Gradle convention plugins — no copy-paste across repos
- Single CI/CD pipeline to maintain
- Easier developer onboarding — clone once, build everything
- Shared `.proto` files generate stubs for both Kotlin and Python from the same source

### Negative
- Repository size will grow over time
- Need disciplined module boundaries to avoid coupling
- Build times may increase (mitigated by Gradle build cache and task avoidance)

### Neutral
- Gradle multi-module project handles Kotlin services naturally
- Python and UI modules live alongside but have independent build tools (uv, npm)
