# Non-Negotiable Rules — NitroBoost (RULE-01 … RULE-11)

These rules are absolute. They are not trade-offs, not style preferences, and not
waivable by deadlines. Every skill in `.agents/skills/` enforces the rules that
fall in its scope; `adversarial-review` checks all of them before RELEASE.
Violating any rule blocks the merge/release.

- **RULE-01** — Never claim a test passed that was not actually run. Record the
  command, the environment (local/CI/device), and the real output.
- **RULE-02** — Never claim a performance improvement (FPS, frame time, RAM,
  thermal) without real measurements on the actual device. CI success is not a
  device benchmark. Unmeasured values stay `null`, never `0` or a guess.
- **RULE-03** — Never sacrifice thermal protection. `ThermalGuard` limits are
  hard and non-disableable; any optimization conflicting with device protection
  is rejected outright.
- **RULE-04** — Never restore system settings over changes made externally
  (by the user, another app, or the OEM) since the journal entry was recorded.
- **RULE-05** — Never run dangerous root/shell commands without explicit user
  approval. Shizuku/root channels are used only for allow-listed, reversible
  operations.
- **RULE-06** — Never delete existing functionality without proof that it is
  unneeded (usage evidence, tests, and docs must all agree).
- **RULE-07** — Never upgrade Gradle, Kotlin, or any dependency without a
  concrete, documented justification (security fix, required API, build break).
- **RULE-08** — Never confuse "command sent" with "command executed". Verify
  the effect (read-back or exit code) before recording success in the journal.
- **RULE-09** — Never hide errors or failing tests. Failures are reported
  verbatim, with logs, and block the release until fixed or explicitly waived by
  the director with a written reason.
- **RULE-10** — Never adopt a new feature without acceptance tests covering it
  (unit +, where applicable, integration), defined in the feature's plan.
- **RULE-11** — Always keep the ability to roll back any change (git revert +
  journal rollback for system settings). No irreversible system action is taken
  by the app without a recorded undo path.

## Enforcement map

| Rule | Primary enforcing skill | Automated check |
|---|---|---|
| RULE-01, RULE-09 | `automated-testing`, `adversarial-review` | CI must run tests; `tools/verify.py`, `security_verify.py`, unittests, `verify_skills.py` |
| RULE-02 | `performance-engineering` | `tools/stress_test.py` (stress/safety only), null-semantics checks in tests |
| RULE-03 | `thermal-safety-engineering` | `ThermalGuard` tests, CI unit tests |
| RULE-04 | `reliability-and-recovery` | `Journal` tests |
| RULE-05 | `system-integration-engineering`, `security-engineering` | `tools/security_verify.py` |
| RULE-06 | `adversarial-review` | diff review checklist |
| RULE-07 | `android-architecture`, `release-engineering` | `tools/verify.py` dependency scan |
| RULE-08 | `system-integration-engineering` | `JournaledSystemExecutor` tests |
| RULE-10 | `automated-testing` | CI `test` task must pass |
| RULE-11 | `reliability-and-recovery`, `release-engineering` | journal coverage tests; git history |
