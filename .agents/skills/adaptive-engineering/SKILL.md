---
name: adaptive-engineering
description: Develops NitroBoost's adaptive optimization engine and repeatable A/B experiments: paired measurement windows, quality gates, family-wise correction, decisions from measurements before applying optimizations, and hard stop conditions on risk. Invoke for any change in core/adaptive/.
---

# Skill: adaptive-engineering

## Mission and Scope

Own `core/adaptive/` — the adaptive optimization engine: `AdaptiveLoop`,
`AdaptivePolicy`, `DecisionLedger`, `SessionQualityGate`, `BottleneckDetector`,
`ThermalTrend`, `TrialModels`, `Models`. Build **repeatable A/B experiments**,
measure results **before** applying optimizations, and **stop experiments the
moment risk appears** (thermal trend, quality gate failure).

## When to Invoke (Triggers)

- Any change to files under `core/adaptive/` or to A/B experiment behavior.
- When adding a new signal to the decision process.
- TEST phase for adaptive changes (quality gates, ledger compatibility).

## Inputs and Outputs

- Inputs: telemetry snapshots (`MonitorSnapshot`/`PerformanceSnapshot`),
  frame-time analysis, thermal trend, `DecisionLedger` history.
- Outputs: experiment design (paired windows, objective identity, sample size,
  metrics, stop conditions), decision record in `DecisionLedger`, compatibility
  guarantee for old ledger JSON.

## Execution Procedure

1. Design experiments as **paired windows** (baseline vs candidate) bound to a
   context (game, device, thermal band) — never compare across different games.
2. Enforce quality gates (`SessionQualityGate`): minimum sample size, valid
   sources only (null = excluded, not zero), apply family-wise correction for
   multi-comparison sweeps.
3. Record every window + decision in `DecisionLedger` with evidence; new fields
   must be optional and backward-compatible with existing ledger JSON.
4. Decide from measurements: adopt a policy change only if the measured effect
   is positive beyond noise **and** quality gates passed **and** no thermal
   limit was approached.
5. Stop conditions (hard): thermal trend rising toward `ThermalGuard` limit,
   quality gate failure, crash/ANR, journal corruption → stop experiment, roll
   back to the last safe policy, log the reason.
6. Never present session FPS comparison as a controlled A/B result (it is
   observational only).

## Acceptance Criteria

- [ ] Every experiment has paired windows, pre-defined metrics, sample size,
  and stop conditions.
- [ ] Decisions recorded in ledger; old ledgers still parse (compat test).
- [ ] Quality gates and family-wise correction applied; invalid samples
  excluded, not zeroed.
- [ ] Stop-on-risk behavior tested (thermal trend case).

## Testing and Verification

- `AdaptiveLoopTest`, `AdaptivePolicyTest`, `DecisionLedgerTest`,
  `SessionQualityGate`-related tests, `ThermalTrendTest`, `BottleneckDetectorTest`
  must pass (`./gradlew test` in CI; JVM tests locally when possible).
- Add tests for any new decision path: edge cases, cancellation, malformed
  ledger input.

## Non-Negotiable Constraints

- RULE-02 (measured-only claims), RULE-03 (thermal stop is absolute — the
  adaptive engine never overrides `ThermalGuard`), RULE-09 (failed experiments
  are reported, not hidden).

## Repository References

- `core/adaptive/AdaptiveLoop.kt`, `AdaptivePolicy.kt`, `DecisionLedger.kt`,
  `SessionQualityGate.kt`, `BottleneckDetector.kt`, `ThermalTrend.kt`,
  `TrialModels.kt`, `Models.kt`
- `core/ThermalGuard.kt`, `core/telemetry/`, `AppStore.kt` (integration)
- Tests: `app/src/test/java/com/nitroboost/app/core/Adaptive*Test.kt`, `DecisionLedgerTest.kt`
