# .agents — Skills System Changelog

Every change to the skills system (skills, rules, team model, cycle, registry) is
recorded here with its reason and the test/verification that proved it. The
system is **updated incrementally across releases — never rebuilt from scratch**.

## v1.0.0 — 2026-11-02 (initial establishment)

- Created `.agents/skills/` with 13 skills, each with a `SKILL.md`
  (frontmatter `name`/`description` + 8 fixed sections).
- Created `registry.json` (machine-readable catalog), `skills/README.md` (index).
- Created root `AGENTS.md` as the guaranteed discovery/entry point with the
  phase→skill invocation map and documented platform constraints.
- Created `.agents/TEAM.md` (9 roles + central director; sequential execution —
  no sub-agents on this platform, documented honestly).
- Created `.agents/DEVELOPMENT_CYCLE.md` (9-phase cycle DISCOVER→…→LEARN with
  hard TEST/REVIEW gates).
- Created `.agents/INNOVATION.md` (10-step autonomous innovation protocol).
- Created `.agents/RULES.md` (RULE-01 … RULE-11, non-negotiable).
- Added `tools/verify_skills.py` (structural verifier, wired into CI) and
  `tools/test_verify_skills.py` (its unittest).
- Reason: mission requirement — an autonomous, persistent, discoverable
  engineering-skills system bound to the development cycle.
- Verification: `python3 tools/verify_skills.py` + unittest + repo `verify.py` —
  see `.agents/VERIFICATION.md` and `.agents/REPORT.md`.
