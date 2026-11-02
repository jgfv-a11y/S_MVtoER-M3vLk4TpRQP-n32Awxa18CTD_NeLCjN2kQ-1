---
name: thermal-safety-engineering
description: Gives absolute priority to thermal protection in NitroBoost: blocks any optimization that conflicts with device safety, enforces hard non-disableable limits and de-escalation, and sets clear stop limits for experiments. Invoke in EVERY phase; has veto power over all other skills.
---

# Skill: thermal-safety-engineering

## Mission and Scope

**Thermal protection has absolute priority over every performance goal.**
Own `core/ThermalGuard.kt`, `core/adaptive/ThermalTrend.kt`,
`core/tasks/ThermalOverrideTask.kt`, `core/TemperatureMath.kt`. No optimization,
experiment, or feature may conflict with protecting the device. This skill has
**veto power** in every phase and can interrupt any other skill.

## When to Invoke (Triggers)

- **Every cycle, every phase** — always active.
- Any proposed change that increases sustained load (CPU/GPU floors, governors,
  brightness, disable thermal HAL limits).
- Any adaptive experiment design (stop limits).

## Inputs and Outputs

- Inputs: thermal zone readings (best-effort, marked unavailable when not
  readable — null ≠ 0°C), `PowerManager` thermal status, `ThermalTrend` direction.
- Outputs: verdict on any proposed change (safe / conditional / blocked);
  experiment stop limits; de-escalation decisions that preserve what can be
  saved.

## Execution Procedure

1. Know the protection chain: `ThermalGuard` holds **hard, non-disableable**
  limits; `ThermalTrend` gives early direction (rising/falling) for proactive
  de-escalation; `ThermalOverrideTask` may only *reduce* boost, never weaken
  the guard.
2. For every proposed performance change, ask: does it raise sustained
  temperature? If yes or unknown → conditional: it must pass through
   `ThermalGuard` checks at runtime and be de-escalated automatically.
3. Define experiment stop limits explicitly: stop the A/B trial when thermal
   trend approaches the guard limit, when `PowerManager` reports throttling,
   or when the battery/thermal source reports an unsafe state → roll back to
   the safe policy and log it (RULE-09).
4. De-escalation is graceful: reduce boost level step-by-step preserving what
   can be saved (per README feature "تدرّج ذكي"), rather than hard-stopping.
5. Verify no code path can disable or bypass the guard (adversarial check in
   REVIEW; `security_verify.py` + unit tests).

## Acceptance Criteria

- [ ] No code path disables/bypasses `ThermalGuard` limits.
- [ ] Every load-increasing change has a runtime thermal check + de-escalation.
- [ ] Experiments have written thermal stop limits and a rollback to safe policy.
- [ ] Unreadable thermal sources are reported as unavailable, never as cool.

## Testing and Verification

- Thermal tests: `ThermalGuard`-related and `ThermalTrendTest`,
  `ThermalOverrideTask` tests, engine tests that assert boost de-escalation on
  simulated high temperature (`EngineTest` et al.) — all must pass in CI.
- Add a test for every new thermal path (edge: sensor failure, boundary value).

## Non-Negotiable Constraints

- RULE-03 is absolute: never sacrifice thermal protection for performance,
  features, or deadlines. Any conflict → the performance side loses.

## Repository References

- `core/ThermalGuard.kt`, `core/TemperatureMath.kt`, `core/adaptive/ThermalTrend.kt`
- `core/tasks/ThermalOverrideTask.kt`, `core/BoostEngine.kt` (de-escalation)
- `platform/Monitor.kt` (thermal source), `README.md` (de-escalation feature)
- Tests: `app/src/test/java/com/nitroboost/app/core/` (thermal/engine tests)
