# TEAM.md — Engineering Team Model & Central Orchestration

## Platform constraint (stated honestly)

This platform (Arena Agent Mode) does **not** expose sub-agents in this session.
Therefore the roles below are **executed sequentially by the single agent, in
the documented order**, each role "wearing its hat" for its phase. We never claim
a parallel team exists. If sub-agent support becomes available, the exact same
role definitions can be delegated to sub-agents without changing this document.

## Roles

- **R-1 مهندس التحليل (Analyst)** — skill: `repository-intelligence`. Scans the
  repo, maps architecture and component relationships, finds debt/dependencies/risks.
- **R-2 المهندس المعماري (Architect)** — skill: `android-architecture`. Owns
  layering, Android compatibility (API 24–36), build constraints.
- **R-3 مهندس الابتكار (Innovator)** — skill: `autonomous-feature-inventor`.
  Proposes genuinely valuable features; scores value/risk/cost; no duplicates.
- **R-4 مهندس Android (Android engineer)** — skills: `android-architecture` +
  `automated-testing`. Implements UI/core changes in idiomatic Kotlin, writes tests.
- **R-5 مهندس الأداء (Performance engineer)** — skill: `performance-engineering`.
  Only measurement-backed performance work; finds bottlenecks.
- **R-6 مهندس الاختبارات (Test engineer)** — skill: `automated-testing`. Designs
  and runs the full test matrix; owns CI test gates.
- **R-7 مهندس الأمان (Security engineer)** — skill: `security-engineering`.
  Permissions, injection prevention, input validation, privacy.
- **R-8 المراجع المستقل (Independent reviewer)** — skill: `adversarial-review`.
  Adversarial, independent of the implementer; blocks unsafe changes.
- **R-9 مهندس الإصدار (Release engineer)** — skill: `release-engineering`.
  Versions, changelog, APK build/verify, release publication.

Supporting roles (always active, not phase-bound): `thermal-safety-engineering`
(veto power over anything touching device safety), `reliability-and-recovery`,
`system-integration-engineering`, `adaptive-engineering`.

## Central authority — the Director (`engineering-director`)

One central role is responsible for:

1. **Task distribution** — maps each cycle phase to roles/skills
   (`.agents/DEVELOPMENT_CYCLE.md`) and assigns work items with acceptance criteria.
2. **Execution tracking** — every cycle writes `docs/daily/YYYY-MM-DD.md`; the
   director checks that each phase produced its artifacts.
3. **Result review** — reads outputs of each role before the next phase starts;
   TEST and REVIEW are hard gates that cannot be bypassed.
4. **Conflict resolution** — when two roles disagree (e.g. performance vs
   thermal safety), safety wins by default (RULE-03); otherwise the director
   decides and records the decision + reason in the daily log.
5. **Merging** — only the director merges completed, reviewed work; one writer
   per file area per cycle to prevent conflicting edits.
6. **Release-readiness decision** — the director alone declares a release ready,
   after `release-engineering` verification and `adversarial-review` sign-off.

## Sequential execution order per cycle

```
R-1 Analyst → R-2 Architect (+R-3 Innovator in INNOVATE) → R-4/R-5/R-7 implement
→ R-6 tests → R-8 adversarial review → R-9 release → Director merges & decides
```

Thermal-safety (supporting role) may interrupt any step with a veto.
