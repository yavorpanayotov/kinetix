# ADR-0034: Regime Classifier Behaviour on Degraded Inputs

## Status
Accepted (2026-05-01) — code already enforced the always-hold-on-degraded policy in `regime_detector.py:298-312`. Spec promoted the policy from buried @guidance into a first-class invariant `HoldOnDegradedInputs` in `regime.allium`. Closes audit item A-17.

## Context

Spec `regime.allium:373-387` (`HandleDegradedSignals`):

> When VIX proxy or credit spread data is unavailable, the classifier falls back to a two-factor model: (realised_vol, cross_asset_correlation) only.
> The regime state is marked with `degraded_inputs = true`. The UI shows a staleness indicator on the regime badge. **The classifier does NOT change regime on degraded inputs (stays in last known state) unless both available signals independently indicate a transition.**

Implementation in `risk-engine/.../regime_detector.py:298-312`:

```python
if degraded:
    # Hold confirmed regime — do not escalate or de-escalate on incomplete signals.
    self._pending_regime = None
    self._pending_count = 0
    confidence = _classify_confidence(self._confirmed_regime, signals, self._thresholds)
    return RegimeClassification(
        regime=self._confirmed_regime,
        confidence=confidence,
        ...
        degraded_inputs=True,
    )
```

The code is stricter than the spec: it always holds the previous regime under degraded inputs, even if both available signals concur on a transition. The spec carves out an exception that the code does not honour.

The strict-hold policy has a clear safety motivation: a stale signal during data outages avoids spurious regime flips that would propagate into VaR parameter selection (`regime.allium:400-403`: `crisis -> monte_carlo, cl_99`). Calling a regime change because we briefly lost the credit-spread feed is a known failure mode in regime-detection literature.

The spec carve-out also has merit: the dangerous case is *transition during stress*. Data outages are correlated with stress (vendor outages, exchange halts, inter-bank quote drying up). If both available signals — realised vol and cross-asset correlation — say "we are in CRISIS now", refusing to escalate because we are missing the credit spread is the *worst* time to be conservative.

This is a methodology call. Either policy is defensible.

## Decision (proposed)

**Align code to spec.** Allow a regime transition under degraded inputs IFF both available signals (realised vol and cross-asset correlation) independently indicate the same target regime.

Concretely in `regime_detector.py`:

- When `degraded` is true, do not unconditionally return the confirmed regime.
- Compute the regime classification using only the available signals.
- If both available signals agree (i.e. the partial classification is unambiguous and matches across the two-factor classifier), treat the observation as a real transition signal and run the normal debounce / pending-transition logic.
- If the available signals are ambiguous or disagree, hold the confirmed regime.
- In all degraded paths, set `degraded_inputs = true` and reduce `confidence` (probably one tier — VERY_HIGH → HIGH, HIGH → MEDIUM, etc.).

## Applies when
- Touching `regime_detector.py` or the regime-classification pipeline.
- Adding a new input signal to the regime classifier.
- Adding alert payloads or VaR parameter selection that branches on regime.

## Rules
- **DO** allow a regime transition under degraded inputs **iff** both available signals (realised vol and cross-asset correlation) independently indicate the same target regime.
- **DO** set `degraded_inputs = true` and reduce `confidence` by one tier on any classification derived from a partial signal set.
- **DO** run the normal `escalation_debounce` / `de_escalation_debounce` logic for degraded-input transitions — the existing debouncers must apply consistently.
- **DO** distinguish "transition under degraded inputs" in alert payloads so on-call can apply extra scrutiny.
- **DON'T** unconditionally hold the prior regime under degraded inputs. The carve-out exists because outages correlate with stress — exactly when escalation is most needed.
- **DON'T** allow a transition on a single available signal. The rule is *both* signals must agree.
- **DON'T** ship a regime-related code change without a backtest against the last 12 months — feed results into the Q3 model-committee paper.

## Trade-offs

### Positive
- Spec compliance.
- Avoids the failure mode where data outages during stress prevent timely regime escalation — exactly when the regime signal is most operationally valuable.
- Confidence reduction during degraded inputs preserves the safety intent: even a transition fires with a lower confidence label, so downstream consumers (VaR parameter selection, alerts) can apply their own gating.

### Negative
- Higher false-positive rate during routine vendor outages.
- Risk of a "flicker" pattern: degraded → escalate → restored → de-escalate. Mitigated by the existing debounce counters (`escalation_debounce`, `de_escalation_debounce`); we should verify those counters apply consistently to degraded-input transitions.
- Adds a partial-classifier code path that needs explicit testing.

### Alternatives considered

- **Keep stricter "always hold"; soften spec.** Reject — the spec carve-out exists for a reason and crisis-detection during outages is a real operational concern. Trading desks have asked for this behaviour explicitly per Q1 retro.
- **Hold by default but expose an admin override.** Reject — opaque, hard to reason about, hard to backtest.
- **Require *triple* confirmation under degraded inputs.** Spec only has two signals available when degraded; this is mathematically the same as "both signals must agree", which is what the spec already says.

## Open questions for the user

1. Confidence tier reduction — is one tier the right amount, or should degraded-input transitions cap at MEDIUM regardless of input strength?
2. Debounce — should the escalation-debounce counter be the same under degraded inputs or longer? Argument for longer: each observation carries less information. Argument for same: lengthening the debounce defeats the purpose of allowing transition at all.
3. Should the alert payload distinguish "transition under degraded inputs" from a normal transition, so PagerDuty / on-call can apply extra scrutiny?

## Consequences if accepted

- Code change in `regime_detector.py:298-312` (the degraded branch).
- New unit tests covering: degraded + both-signals-agree → transition; degraded + signals-disagree → hold; degraded + only-one-signal-changed → hold.
- Backtest the new policy against the last 12 months of regime data to quantify the false-positive delta — feed result into the Q3 model committee paper.
- No spec change — spec already specifies this behaviour.
