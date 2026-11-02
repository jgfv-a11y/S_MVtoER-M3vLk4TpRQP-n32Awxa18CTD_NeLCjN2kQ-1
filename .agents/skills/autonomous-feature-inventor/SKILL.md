---
name: autonomous-feature-inventor
description: Proactively invents genuinely valuable features for NitroBoost: analyzes user needs, studies suitable modern techniques, compares solutions, scores proposals by value/risk/cost, and selects provable improvements. Never duplicates existing features or adds cosmetic-only changes. Invoke at INNOVATE of every cycle.
---

# Skill: autonomous-feature-inventor

## Mission and Scope

Do not wait to be told what to build. At the start of every cycle, think like an
expert product engineer: find weak points, find unexploited opportunities,
invent features with **real value** for NitroBoost's nature (game boosting,
safety, reversible tuning, monitoring), and select only the **provable** ones.

## When to Invoke (Triggers)

- INNOVATE phase of every development cycle (mandatory).
- Whenever DISCOVER/ANALYZE surfaces a user-facing weakness.

## Inputs and Outputs

- Inputs: full app scan, `NitroBoost/docs/COMPETITIVE_ROADMAP.md`,
  `ENGINEERING_UPDATE_v1.23.0.md`, `assets/game_profiles.json`,
  `core/tasks/AllTasks.kt`, UI screens, user feedback in issues.
- Outputs: ranked proposal list using the template in `.agents/INNOVATION.md`
  (ID, problem, proposal, alternatives, value/risk/cost/evidence, decision,
  acceptance criteria, rollback).

## Execution Procedure

Follow the 10-step protocol in `.agents/INNOVATION.md` exactly:
1. Scan the app for weak points. 2. Find unexploited opportunities. 3. Invent
suitable new features. 4. Study appropriate modern techniques. 5. Compare
solutions. 6. Unconventional ideas only with technical justification. 7. Score
value/risk/cost/evidence. 8. Select the best provable improvements. 9. Implement
low-risk ones autonomously (still via PLAN→IMPLEMENT→TEST→REVIEW). 10. Test
before adopting.

Apply the hard filters: no duplicates of existing features; nothing cosmetic
only; nothing conflicting with thermal safety/privacy/security; everything must
be testable or measurable; no dependency upgrades without justification.

## Acceptance Criteria

- [ ] At least one scan of the app for weaknesses/opportunities per cycle,
  with file references.
- [ ] Every proposal has value/risk/cost/evidence scores and a decision
  (adopt/defer/reject + reason).
- [ ] No proposal duplicates an existing feature (checked against
  `AllTasks.kt`, profiles, UI, CHANGELOG).
- [ ] Adopted proposals have acceptance criteria + tests defined before
  implementation (RULE-10).

## Testing and Verification

- Verify "not a duplicate" by grepping the codebase and CHANGELOG for the idea.
- For performance-related proposals: define the measurement protocol up front
  (device, scene, repetitions) — adoption requires measured evidence (RULE-02).
- Director reviews the proposal list at PLAN.

## Non-Negotiable Constraints

- RULE-02 (no unmeasured performance claims), RULE-03 (thermal), RULE-10
  (acceptance tests), RULE-07 (no unjustified upgrades).
- Autonomy means engineering initiative — never overriding safety or user
  authority.

## Repository References

- `.agents/INNOVATION.md` (protocol + template)
- `NitroBoost/docs/COMPETITIVE_ROADMAP.md`, `NitroBoost/docs/ENGINEERING_UPDATE_v1.23.0.md`
- `NitroBoost/app/src/main/assets/game_profiles.json`, `core/tasks/AllTasks.kt`, `ui/`
- `NitroBoost/CHANGELOG.md`
