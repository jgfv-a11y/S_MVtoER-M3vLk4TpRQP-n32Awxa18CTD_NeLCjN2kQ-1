# DEVELOPMENT_CYCLE.md — Continuous Development Cycle

```
DISCOVER → ANALYZE → INNOVATE → PLAN → IMPLEMENT → TEST → REVIEW → RELEASE → LEARN
```

Every development cycle (feature, fix, release) runs through all nine phases.
Phases may be small for a bugfix, but they are never skipped. TEST and REVIEW
are hard gates. Artifacts of each cycle: an entry in `docs/daily/YYYY-MM-DD.md`
and, when a release ships, a `NitroBoost/docs/` engineering note + CHANGELOG entry.

## Phase definitions, skills, and exit criteria

### 1. DISCOVER
- **Skills:** `repository-intelligence` (lead), `engineering-director`.
- Do: scan the app and repo for new problems, regressions, user-facing weaknesses,
  unexploited opportunities. Check open issues/PRs, `docs/AUTONOMOUS_PROGRESS.md`,
  and the last daily logs for leftovers.
- Exit: a written list of discovered problems/opportunities with file references.

### 2. ANALYZE
- **Skills:** `repository-intelligence`, `android-architecture`,
  `adversarial-review` (findings triage).
- Do: understand architecture and component relationships affected; identify
  technical debt, dependencies, risks; root-cause each discovered item.
- Exit: analysis note with root causes and affected components
  (`core/`, `platform/`, `data/`, `ui/`, `service/`, `tools/`, CI).

### 3. INNOVATE
- **Skills:** `autonomous-feature-inventor` (protocol: `.agents/INNOVATION.md`).
- Do: proactively propose new features suited to NitroBoost's nature; study
  suitable modern techniques; compare possible solutions; propose unconventional
  ideas only with a technical justification; score each proposal
  (value / risk / cost / evidence); pick the best **provable** improvements.
- Exit: a ranked proposal list; each proposal has value/risk/cost and the
  evidence that would prove it. No feature that merely duplicates an existing one.

### 4. PLAN
- **Skills:** `engineering-director` (lead), `android-architecture`.
- Do: director splits work into tasks with owners (roles in `TEAM.md`), defines
  acceptance criteria per task, sequences them to avoid edit conflicts (one
  writer per file area), and picks the proposals to implement this cycle
  (low-risk, evidence-backed first).
- Exit: a plan (in the daily log) listing tasks, files touched, acceptance
  criteria, and rollback approach for each.

### 5. IMPLEMENT
- **Skills:** `android-architecture`, `system-integration-engineering`,
  `thermal-safety-engineering`, `reliability-and-recovery`,
  `security-engineering`, plus `performance-engineering` / `adaptive-engineering`
  when the change touches those areas.
- Do: implement per plan. Every system-level change goes through the journaled
  executor with a recorded undo path (RULE-11). No dangerous commands without
  explicit approval (RULE-05).
- Exit: code changes complete, self-checked against each skill's constraints.

### 6. TEST (hard gate)
- **Skills:** `automated-testing` (lead); `performance-engineering` for any
  performance claim; `adaptive-engineering` for A/B engine changes.
- Do: run `python3 tools/verify.py`, `python3 tools/security_verify.py`,
  `python3 tools/verify_skills.py`, `python3 -m unittest discover -s tools -p 'test_*.py'`,
  and the JVM suite via CI (`./gradlew test lintDebug assembleDebug`) — locally
  when an SDK exists. Add tests for new behavior: failure cases, edges,
  concurrency/cancellation, API 24–36 compatibility.
- Exit: all runnable checks pass with recorded output; new tests exist and pass;
  no hidden failures (RULE-01, RULE-09).

### 7. REVIEW (hard gate)
- **Skills:** `adversarial-review` (lead), `security-engineering`.
- Do: independent adversarial review of the diff: hunt bugs the implementer
  missed, regressions, safety/security issues, rule violations (RULE-01…11).
- Exit: written review verdict (approve / request changes) in the daily log.
  "Request changes" sends the cycle back to IMPLEMENT.

### 8. RELEASE
- **Skills:** `release-engineering`, `engineering-director` (readiness decision).
- Do: bump `versionName`/`versionCode` only for real releases, update
  `NitroBoost/CHANGELOG.md`, let CI build + verify APK (badging, minSdk 24,
  targetSdk 36, signature, SHA-256), publish GitHub release from `main` only.
- Exit: release published (or explicitly deferred by the director with reason).
  A change that has not passed TEST+REVIEW is never released.

### 9. LEARN
- **Skills:** `engineering-director`, all skills for gaps found.
- Do: document lessons learned; remove unnecessary complexity; improve tests;
  review performance evidence; **update the skills themselves when a real gap is
  found** (record every skills edit, its reason, and its test in
  `.agents/CHANGELOG.md`); never rebuild the skills system from scratch.
- Exit: daily log completed; `.agents/CHANGELOG.md` updated if anything changed.
