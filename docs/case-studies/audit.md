# Vignette: Audit trail — tamper-evidence you can verify

> The richest arc in the codebase: a hash-chained, tamper-evident audit
> trail, recorded as both an architecture decision
> ([ADR-0017](../../docs/adr/0017-hash-chained-audit-trail.md)) and a
> behavioural spec
> ([`specs/audit.allium`](../../specs/audit.allium), 527 lines). It is the
> clearest example of the division this project runs on: the *idea* —
> "make the audit trail tamper-evident, and provably so" — is an
> architecture call I made and wrote down in an ADR; the AI then built the
> hashing, the chain verification, the gap detection, and the
> dead-letter-replay idempotency around it.

## The decision (ADR-0017)

A plain append-only table can be edited after the fact by anyone with
database access. Regulations require an immutable, tamper-*evident* trail.
The decision: hash-chain every audit event. Each record carries a
`record_hash` = SHA-256 of the event fields concatenated with the previous
record's `previous_hash` — a blockchain-like chain where altering any
record breaks every hash after it.

`AuditHasher` provides `computeHash(event, previousHash)`,
`verifyChain(events)` (from genesis), and `verifyChainIncremental(...)`
(a segment, so verification paginates). That last method is a judgement
call: tamper-evidence is worthless if you can't *afford* to check it, so
the design made verification incremental from the start.

## Where the spec earned its keep

Two examples of the spec catching drift the code would otherwise have
shipped:

- **The `portfolio → book` rename (2026-04-07).** A cross-cutting rename
  touched the hash input itself (`portfolioId` → `bookId`). The hash input
  field list is *load-bearing* — change it silently and you either break
  every existing chain or, worse, verify successfully against the wrong
  fields. Because the field set is pinned in the spec and the ADR, the
  rename was a deliberate, recorded migration, not an accident.

- **A missing event type.** `RISK_CALCULATION_FAILED` was absent from the
  `AuditEvent` event-type enum — surfaced by a `/weed` divergence sweep and
  added. A risk calculation that fails is exactly the kind of event a
  regulator expects to see in the trail; the spec is what makes "is every
  auditable event actually enumerated?" a checkable question.

## Proven at the boundary

The behaviour is held by acceptance tests against real infrastructure
(Postgres + Kafka via Testcontainers), not mocks:

- `AuditHashChainAcceptanceTest` — the chain verifies from genesis.
- `AuditHashChainConcurrencyAcceptanceTest` — the chain stays valid under
  concurrent appends (sequence-number monotonicity).
- `AuditGapDetectionAcceptanceTest` — a missing sequence number is detected.
- `DlqReplayAcceptanceTest` — replaying a dead-lettered event is idempotent,
  so recovery can't double-write the chain.

That last one is the subtle, human call: a tamper-evident chain must also be
*recoverable* without corrupting itself, so DLQ replay had to be idempotent
by design.

## What stayed human

- The decision to hash-chain at all, and to make verification incremental
  so it scales (ADR-0017).
- The hash *input field set* as a deliberately pinned contract — the thing
  the rename had to be careful with.
- That DLQ replay must be idempotent against the chain.

The cryptography and the test scaffolding are mechanical once those calls
are made. The calls are the engineering.

## Trace it yourself

```bash
allium check specs/audit.allium
./gradlew :audit-service:acceptanceTest --tests "*AuditHashChain*"
```

See also the [case-study index](README.md), the
[counterparty-risk flagship](counterparty-risk.md), and the
[limits vignette](limits.md).
