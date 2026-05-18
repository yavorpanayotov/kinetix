# ADR-0017: Hash-Chained Audit Trail

## Status
Accepted

## Context
Financial regulations require an immutable, tamper-evident audit trail for all trade events. A simple append-only table can be modified after the fact by anyone with database access. We need a mechanism to detect unauthorized changes.

## Decision
Implement a hash-chained audit trail in the audit-service. Each audit event record includes a `record_hash` (SHA-256 of the event data concatenated with the previous record's hash) and a `previous_hash` (link to the prior record), forming a blockchain-like chain.

`AuditHasher` (`audit-service/.../persistence/AuditHasher.kt`) provides:
- `computeHash(event, previousHash)` — SHA-256 of all event fields (receivedAt, tradeId, bookId, instrumentId, assetClass, side, quantity, priceAmount, priceCurrency, tradedAt, userId, userRole, eventType) concatenated with the previous hash

**Note (updated 2026-04-07):** Field names updated to reflect the portfolio→book rename (V34). The field `portfolioId` was renamed to `bookId` in the hash input — existing chains use the new name.
- `verifyChain(events)` — validates the entire chain from genesis
- `verifyChainIncremental(events, startingPreviousHash)` — validates a segment of the chain, enabling pagination

A REST endpoint (`/api/v1/audit/verify`) allows on-demand chain verification.

## Applies when
- Adding a field to `audit_events` or to any payload that gets hashed into the chain.
- Writing code that inserts into `audit_events`.
- Touching `AuditHasher`, the verification endpoint, or the chain-link logic.

## Rules
- **DO** include every hash-relevant field in `AuditHasher.computeHash` in a deterministic order. Adding a field requires updating the hash input and recording the schema change.
- **DO** insert audit events serially. The chain has no parallel-write semantics — concurrent inserts will produce out-of-order `previous_hash` references.
- **DO** verify the chain incrementally for routine checks (`verifyChainIncremental`). Full verification is O(n) and should be rare.
- **DO** store audit values as `VARCHAR` exactly as received. Don't normalise enums or trim whitespace in flight.
- **DON'T** issue `UPDATE` or `DELETE` against `audit_events`. Corrections are appended, not mutated. The chain cannot self-heal.
- **DON'T** silently change which fields participate in the hash. That breaks historical verification — coordinate any change explicitly and document it (see the 2026-04-07 `portfolio→book` note inline).
- **DON'T** swap SHA-256 for another hash without an ADR superseding this one.

## Consequences

### Positive
- Tamper detection: any modification to a historical record breaks the chain from that point forward
- Incremental verification supports large audit trails without loading the entire history
- SHA-256 is a well-understood, collision-resistant hash function
- Audit data is stored as VARCHAR (not typed columns) to preserve values exactly as received

### Negative
- Sequential hash chaining means audit events must be inserted in order — no parallel writes
- Verification is O(n) for the full chain; incremental mode mitigates this for routine checks
- Chain cannot self-heal — if a record is corrupted, verification fails permanently from that point

### Alternatives Considered
- **Append-only table with database constraints**: Simpler, but database administrators can still modify records without detection.
- **External blockchain (Hyperledger)**: Provides decentralised immutability, but adds significant infrastructure complexity for an internal audit trail.
- **WORM storage**: Write-once storage at the filesystem level. Effective but less granular — we need per-record verification, not per-file.
