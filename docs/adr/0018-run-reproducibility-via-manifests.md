# ADR-0018: Run Reproducibility via Manifests

## Status
Accepted

## Context
Risk managers and auditors need to understand exactly what inputs produced a given VaR number. Without a snapshot of inputs, a risk result cannot be independently verified or reproduced. Regulatory frameworks (FRTB, Basel) require model reproducibility.

## Decision
Capture a `RunManifest` for every risk calculation run. The manifest records all inputs and outputs needed to reproduce the result:

**Input capture (before calculation):**
- `jobId`, `bookId`, `valuationDate`

**Note (updated 2026-04-07):** Field names updated to reflect the portfolio→book rename (V34). The manifest field was originally `portfolioId`; it is now `bookId`.
- `calculationType`, `confidenceLevel`, `timeHorizonDays`, `numSimulations`
- `monteCarloSeed` — deterministic seed for MC runs (0 = unseeded)
- `positionCount`, `positionDigest` — SHA-256 hash of serialized positions
- `marketDataDigest` — SHA-256 hash of all market data inputs
- `inputDigest` — combined hash of positions + market data + parameters

**Output capture (after calculation):**
- `modelVersion` — risk-engine version string (e.g., "0.1.0-abc1234")
- `varValue`, `expectedShortfall`
- `outputDigest` — SHA-256 hash of the full result
- `status` — `CAPTURED` → `COMPLETED` or `FAILED`

The `RunManifestCapture` service (`risk-orchestrator`) is integrated into `VaRCalculationService` as an optional collaborator, called between position fetch and valuation phases.

## Applies when
- Adding a new risk calculation type or modifying `VaRCalculationService`.
- Adding inputs that affect the computed result (a new market data type, a new parameter).
- Touching `RunManifestCapture` or the `RunManifest` schema.

## Rules
- **DO** capture a `RunManifest` for every risk calculation. Manifest capture is part of the contract, not optional — null-safety in code is a defensive backstop, not an escape hatch.
- **DO** include any new result-affecting input in the `inputDigest`. If you add a field that changes the output, the digest must change.
- **DO** record the `monteCarloSeed` whenever Monte Carlo is used. Seed 0 = unseeded/non-deterministic; non-zero = reproducible.
- **DO** record the `modelVersion` from the risk-engine response — this is how a result is linked to the exact engine commit.
- **DO** compute `positionDigest` and `marketDataDigest` from the same canonical serialization used to send to the engine, otherwise reproducibility fails silently.
- **DON'T** persist a risk result without an associated manifest. Even a failed run captures `status=FAILED` and the input digests.
- **DON'T** mutate a `RunManifest` after capture. It is an immutable witness.

## Consequences

### Positive
- Any risk result can be independently verified by replaying the manifest's inputs through the same model version
- Input digests enable quick comparison: same inputs + same model = same outputs (deterministic MC with seed)
- Audit trail links a VaR number to the exact market data and positions used
- Manifest capture is optional (null-safe) — does not block calculation if capture fails

### Negative
- Additional storage for manifest records per run
- Digest computation adds latency (SHA-256 of serialized positions and market data)
- True reproducibility requires archiving the exact model version, not just the version string

### Alternatives Considered
- **Log-based reconstruction**: Parse logs to reconstruct inputs. Fragile, incomplete, and difficult to verify programmatically.
- **Full input archival**: Store the complete position and market data snapshots per run. More thorough but significantly more storage. Digests provide a compromise — verify sameness without storing duplicates.
- **No manifest**: Rely on the VaR result alone. Fails regulatory reproducibility requirements.
