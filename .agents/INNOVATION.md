# INNOVATION.md — Autonomous Innovation Protocol

Innovation is not optional "activity filler": at the start of every cycle the
agent thinks like an expert product engineer and looks for **real, provable
value** for NitroBoost. Follow these 10 steps at INNOVATE:

1. **Scan the app** and discover its weak points (UX friction, missing
   guidance, unexploited device capabilities, dead complexity).
2. **Find unexploited improvement opportunities** — not features for the sake
   of features.
3. **Discover new features suited to NitroBoost's nature** (game boosting,
   thermal safety, reversible system tuning, per-game profiles, monitoring).
4. **Study suitable modern techniques/methods** (Android APIs per API level,
   sysfs/cpufreq, frame pacing analysis, on-device measurement).
5. **Compare the possible solutions** for each idea (build vs reuse, local vs
   system, cost on battery/thermal).
6. **Propose unconventional solutions only when there is a technical
   justification** — never for novelty alone.
7. **Score every proposal**: Value (user/device impact) × Evidence (how we would
   prove it) − Risk (safety/thermal/privacy) − Cost (complexity, maintenance).
8. **Select the best provable improvements** for this cycle; low-risk ones may
   be implemented autonomously in the same cycle.
9. **Implement low-risk improvements autonomously** — but always through
   PLAN → IMPLEMENT → TEST → REVIEW, never directly.
10. **Test every change before adopting it**; record measurements where the
    proposal is about performance.

## Hard filters (a proposal is rejected if any applies)

- It duplicates an existing feature (check `core/tasks/AllTasks.kt`,
  `assets/game_profiles.json`, UI screens, `CHANGELOG.md`).
- Its only value is cosmetic/activity-like ("to show work").
- It conflicts with thermal safety (RULE-03), privacy (local-only data), or
  security rules.
- It cannot be tested or its effect cannot be measured at all.
- It requires a dependency/Gradle/Kotlin upgrade without justification (RULE-07).

## Proposal template (written into the daily log / plan)

```
ID: INN-YYYY-MM-DD-nn
Title: …
Problem / opportunity: … (with file references)
Proposal: …
Alternatives compared: …
Value / Risk / Cost / Evidence: …
Decision: adopt-this-cycle / defer / reject (reason)
Acceptance criteria + tests: …
Rollback: …
```

Existing roadmaps to mine for ideas (do not re-propose what is done):
`NitroBoost/docs/COMPETITIVE_ROADMAP.md`, `NitroBoost/docs/ENGINEERING_UPDATE_v1.23.0.md`,
`docs/AUTONOMOUS_PROGRESS.md`. Known open directions: per-game session history,
"next safe step" guidance, faster game center, customizable HUD — each still
needs device-level evidence before any performance claim.
