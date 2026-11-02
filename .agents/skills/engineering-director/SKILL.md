---
name: engineering-director
description: Leads the full NitroBoost product development cycle: sets priorities, splits work into tasks, coordinates engineering specialties, prevents conflicting edits, merges reviewed work, and makes the release-readiness decision. Invoke at the start of every cycle, at PLAN, and at RELEASE.
---

# Skill: engineering-director

## Mission and Scope

Own the **entire product development cycle** of NitroBoost as a central
director: priorities, task division, cross-specialty coordination
(`.agents/TEAM.md`), conflict prevention between edits, merge control, and the
final release-readiness decision. This skill orchestrates all other skills; it
does not replace them.

## When to Invoke (Triggers)

- **Start of every development cycle** (before anything else, together with
  `repository-intelligence`).
- PLAN phase (task split + acceptance criteria) and RELEASE phase
  (readiness decision).
- Whenever two roles/skills disagree (conflict resolution; safety wins by
  default per RULE-03).

## Inputs and Outputs

- Inputs: discovered problems/opportunities (DISCOVER/ANALYZE output), ranked
  innovation proposals (`.agents/INNOVATION.md`), repo state, CI status, open
  issues/PRs.
- Outputs: cycle plan with tasks/owners/acceptance criteria (in
  `docs/daily/YYYY-MM-DD.md`), conflict-resolution decisions with reasons,
  merge decision, release-readiness verdict.

## Execution Procedure

1. Read `AGENTS.md`, `.agents/DEVELOPMENT_CYCLE.md`, `.agents/RULES.md`,
   `.agents/TEAM.md`, and the latest `docs/daily/*.md` + `docs/AUTONOMOUS_PROGRESS.md`.
2. Confirm the cycle phases will all run: DISCOVER → ANALYZE → INNOVATE →
   PLAN → IMPLEMENT → TEST → REVIEW → RELEASE → LEARN. Refuse to skip TEST or
   REVIEW.
3. Prioritize: safety/security/thermal (P0) first, then correctness, then
   measured performance, then UX/features — consistent with
   `NitroBoost/docs/COMPETITIVE_ROADMAP.md`.
4. Split work into tasks: each task gets an owner role, the files it may touch
   (one writer per file area per cycle), acceptance criteria, and a rollback
   approach.
5. Coordinate specialties: route each task to its skill(s) per the invocation
   map in `AGENTS.md` §2 / `DEVELOPMENT_CYCLE.md`.
6. Track execution: verify each phase produced its artifact before starting
   the next.
7. Resolve conflicts: safety/security vetoes are final; otherwise decide and
   record the decision + reason in the daily log.
8. Merge only completed, TEST+REVIEW-passed work. Make the release-readiness
   decision explicitly ("ready" / "not ready" + reasons).

## Acceptance Criteria

- [ ] Every cycle has a written plan and a daily-log entry; no phase skipped.
- [ ] Every task has owner, files, acceptance criteria, rollback.
- [ ] TEST and REVIEW gates were enforced; no unreviewed merge.
- [ ] Release decision is explicit and recorded with reasons.
- [ ] Skills-system gaps found during the cycle are logged in
  `.agents/CHANGELOG.md` (LEARN phase).

## Testing and Verification

- Run `python3 tools/verify_skills.py` (skills system intact).
- Check the daily log exists and covers all 9 phases for the cycle.
- Adversarial check: would an outside reviewer find a skipped gate? If yes, fix.

## Non-Negotiable Constraints

- RULE-01/02/09 (no false claims, no hidden failures), RULE-10 (no feature
  without acceptance tests), RULE-11 (rollback possible).
- Never claim a parallel team exists on platforms without sub-agents; roles are
  executed sequentially (`.agents/TEAM.md`).
- Never let a change reach RELEASE without TEST + REVIEW.

## Repository References

- `AGENTS.md`, `.agents/DEVELOPMENT_CYCLE.md`, `.agents/TEAM.md`,
  `.agents/INNOVATION.md`, `.agents/RULES.md`, `.agents/CHANGELOG.md`
- `docs/daily/`, `docs/AUTONOMOUS_PROGRESS.md`
- `NitroBoost/docs/COMPETITIVE_ROADMAP.md`, `NitroBoost/CHANGELOG.md`
