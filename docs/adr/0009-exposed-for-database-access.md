# ADR-0009: Use Exposed for Database Access

## Status
Accepted

## Context
Kotlin services need a database access layer for PostgreSQL and TimescaleDB. Options: Exposed (JetBrains), jOOQ, Hibernate/JPA, plain JDBC with coroutines.

## Decision
Use Exposed 0.58.0 (JetBrains Kotlin SQL framework) with its DSL API.

## Applies when
- Writing a new database query in a Kotlin service.
- Designing a new repository class.
- Tempted to reach for JPA annotations (`@Entity`, `@Id`, `@OneToMany`), jOOQ, or raw JDBC.

## Rules
- **DO** define tables as `object Foo : Table("foo") { val id = uuid("id"); … }` and access via the Exposed DSL.
- **DO** wrap every query in `transaction { … }` (blocking) or `newSuspendedTransaction(Dispatchers.IO) { … }` (coroutines). Repositories own the transaction boundary.
- **DO** expose persistence behind interfaces (`FooRepository`) with an `ExposedFooRepository` implementation. Tests substitute in-memory fakes.
- **DO** prefer the DSL API. Use DAO only for trivial CRUD where the boilerplate saving is worth it.
- **DO** validate inputs **before** entering `newSuspendedTransaction` — exceptions thrown inside cannot be caught by Kotest `shouldThrow` (CLAUDE.md gotcha).
- **DON'T** add Hibernate/JPA, Spring Data, or jOOQ as dependencies.
- **DON'T** use Exposed's DAO entity-graph traversal for cross-aggregate reads — issue explicit queries. Lazy loading bites in coroutines.
- **DON'T** run migrations through Exposed's `SchemaUtils.create()`. Flyway owns schema (ADR-0027).

## Consequences

### Positive
- Kotlin-native: maintained by JetBrains, designed for Kotlin idioms
- Type-safe SQL DSL — compile-time query validation without annotation processing
- Lightweight: no proxy objects, no lazy loading traps, no session management complexity
- Aligns with the Kotlin-native stack (Ktor, Koin, kotlinx.serialization)
- Supports both DSL (for complex queries) and DAO (for simple CRUD) APIs

### Negative
- Smaller community than Hibernate or jOOQ
- Less powerful for very complex SQL compared to jOOQ (which generates from schema)
- No built-in migration tooling (mitigated by using Flyway separately)

### Alternatives Considered
- **Hibernate/JPA**: Industry standard ORM, but heavyweight. Proxy objects, lazy loading exceptions, and session management are error-prone. Annotation-driven model is less idiomatic in Kotlin.
- **jOOQ**: Excellent for complex SQL, generates type-safe code from database schema. But it's Java-first, and the code generation step adds build complexity. Better suited for legacy database integration.
- **Plain JDBC**: Maximum control but excessive boilerplate for common operations.
