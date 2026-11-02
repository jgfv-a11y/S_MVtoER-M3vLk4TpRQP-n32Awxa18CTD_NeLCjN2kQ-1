---
name: adversarial-review
description: Independent adversarial review of every change before merge/release: hunts bugs the developer missed, regressions, safety/security/privacy risks and rule violations (RULE-01..11), and blocks unsafe changes. Invoke at the REVIEW hard gate of every cycle.
---

# Skill: adversarial-review

## Mission and Scope

Act as an **independent, adversarial reviewer** — assume the implementer missed
something. Hunt bugs, regressions, and risks in the diff before it merges. This
is a **hard gate**: verdict "request changes" sends the cycle back to IMPLEMENT;
only "approve" (possibly with mandatory minor fixes) allows RELEASE. The
reviewer is independent from the implementer role even when the same agent runs
both sequentially.

## When to Invoke (Triggers)

- REVIEW phase of every cycle (mandatory, after TEST).
- Before any release decision by `engineering-director`.
- On any PR diff.

## Inputs and Outputs

- Inputs: full diff, daily log, test results, relevant skills' constraints.
- Outputs: written review in the daily log: findings list (severity, file:line,
  why it matters, suggested fix), rule-violation checklist (RULE-01…11),
  final verdict (approve / request-changes) with reasons.

## Execution Procedure

1. Read the diff first, then the surrounding code (context the diff doesn't show).
2. Hunt actively for: off-by-one/boundary bugs; null/availability confusion
   (null vs 0 — RULE-02); race conditions & cancellation (coroutines, service
   lifecycle, concurrent journal writes); resource leaks; API-level hazards on
   API 24; injection or unsafe command building; permission changes; privacy
   leaks; weakened tests; deleted functionality (RULE-06); hidden failures
   (RULE-09); unmeasured claims (RULE-01/02); rollback gaps (RULE-11); thermal
   bypass (RULE-03); restore-over-external-change (RULE-04).
3. Check regressions against `NitroBoost/CHANGELOG.md` behavior promises
   (journal semantics, thermal guard, null semantics, per-game FPS comparison).
4. Verify tests actually cover the changed paths (not just exist).
5. Run/inspect: `tools/verify_skills.py`, `NitroBoost/tools/verify.py`,
   `security_verify.py`, unittest summaries, CI run.
6. Produce the verdict. Block unsafe changes unconditionally — no deadline
   overrides safety (RULE-03/05).

## Acceptance Criteria

- [ ] Review covers the whole diff plus its context, with file:line findings.
- [ ] RULE-01…11 checklist explicitly checked and recorded.
- [ ] Test adequacy for changed paths assessed.
- [ ] Verdict written (approve/request-changes) with reasons; blockers listed.

## Testing and Verification

- The review itself is verified by the director: no RELEASE without a recorded
  adversarial verdict.
- Spot-check: re-run the local verification battery and compare with claims.

## Non-Negotiable Constraints

- Never approve a change with an unresolved safety/security/thermal finding.
- Never approve on "it probably works" — only on evidence (RULE-01/02).
- The reviewer never merges its own fixes silently; fixes go back through
  IMPLEMENT+TEST.

## Repository References

- `.agents/RULES.md`, `.agents/DEVELOPMENT_CYCLE.md`
- `NitroBoost/CHANGELOG.md` (behavioral promises), `README.md` (safety semantics)
- `NitroBoost/docs/REVERSE_ENGINEERING_REVIEW_v1.22.0.md`, `REPOSITORY_AUDIT_v1.21.0.md` (review style examples)
- `tools/verify_skills.py`, `NitroBoost/tools/verify.py`, `security_verify.py`
