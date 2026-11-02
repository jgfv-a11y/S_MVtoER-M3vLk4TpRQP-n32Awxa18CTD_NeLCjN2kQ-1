---
name: automated-testing
description: Designs and runs NitroBoost's comprehensive automated tests: JVM unit tests (engine, journal, thermal, adaptive, tasks via FakeExecutor), Python tool tests, static/security verifiers, and Android API 24-36 compatibility checks; enforces the TEST hard gate in CI. Invoke at every TEST phase.
---

# Skill: automated-testing

## Mission and Scope

Own the **TEST hard gate**: design comprehensive automated tests — normal paths,
failure cases, edge cases, concurrency and cancellation, and Android-version
compatibility — and make them run in CI on every change. A change without passing
tests is not adoptable (RULE-10) and failures are never hidden (RULE-09).

## When to Invoke (Triggers)

- TEST phase of **every** cycle (mandatory hard gate).
- When adding/changing any behavior (new tests required in the same change).

## Inputs and Outputs

- Inputs: the diff under test, existing suites:
  - JVM: `app/src/test/java/com/nitroboost/app/**` (JUnit 4; `FakeExecutor`
    for engine/task tests) — currently ~50 files.
  - Python: `NitroBoost/tools/test_*.py` for `verify.py`, `security_verify.py`,
    `stress_test.py`, and repo-level `tools/test_verify_skills.py`.
  - Static/security: `NitroBoost/tools/verify.py` (XML/resources/manifest/
    Kotlin balance), `security_verify.py`.
  - CI: `.github/workflows/build-apk.yml` runs all of the above plus
    `./gradlew test lintDebug assembleDebug` and APK metadata/signature checks.
- Outputs: test results recorded verbatim in the daily log (command + summary);
  new tests for new behavior; a green-or-explained TEST gate.

## Execution Procedure

1. Map the change to affected components (`repository-intelligence` output) and
   list required test types: unit (pure logic), task-level (FakeExecutor:
   commands, failure, timeout, journal), integration-ish (service/executor),
   resource/manifest (verify.py), security (security_verify.py).
2. Write tests for: happy path, **failure paths** (denied permission, timeout,
   non-zero exit, missing sysfs), **edges** (empty input, malformed JSON,
   boundary temperatures/levels, null sources), **concurrency/cancellation**
   (coroutines cancelled mid-command, concurrent journal writes, service
   kill/restart), **API compatibility** (gates for API 24 vs newer; no API-26+
   APIs on minSdk paths).
3. Run locally what the sandbox allows: `python3 tools/verify_skills.py`,
   `python3 NitroBoost/tools/verify.py`, `python3 NitroBoost/tools/security_verify.py`,
   `python3 -m unittest discover -s NitroBoost/tools -p 'test_*.py'` and
   `... -s tools -p 'test_*.py'`; JVM `./gradlew test lintDebug` runs in CI
   (no local SDK in this environment — say so explicitly, RULE-01).
4. Verify CI actually ran them (check the workflow run); record run URL/ID.
5. Any failure → fix or report verbatim; never mark green without a real run.

## Acceptance Criteria

- [ ] New/changed behavior has tests covering success, failure, edge,
  concurrency/cancellation, and (where relevant) API-level compatibility.
- [ ] All locally runnable checks pass; CI run recorded with link/id.
- [ ] Test count and coverage of the changed area reported honestly.
- [ ] No test is deleted/weakened to make the gate pass (RULE-06/09).

## Testing and Verification

- The verifier suite itself is tested: `tools/test_verify_skills.py`.
- Run the full local battery listed above and paste summaries in the daily log.

## Non-Negotiable Constraints

- RULE-01 (never claim an unrun test), RULE-09 (never hide failures),
  RULE-10 (no feature without acceptance tests).

## Repository References

- `NitroBoost/app/src/test/java/com/nitroboost/app/**` (JUnit4, `core/FakeExecutor.kt`)
- `NitroBoost/tools/verify.py`, `security_verify.py`, `stress_test.py`, `test_*.py`
- `tools/verify_skills.py`, `tools/test_verify_skills.py` (repo-level)
- `.github/workflows/build-apk.yml` (test/lint/assemble + APK checks)
