---
name: release-engineering
description: Manages NitroBoost releases: versionName/versionCode bumps, CHANGELOG and reports, APK build via CI, artifact/signature/SHA-256 verification, and GitHub release publication from main only. Invoke at the RELEASE phase after TEST+REVIEW gates pass.
---

# Skill: release-engineering

## Mission and Scope

Ship versions of NitroBoost correctly and verifiably: bump `versionName`/
`versionCode` in `NitroBoost/app/build.gradle.kts`, update `NitroBoost/CHANGELOG.md`
and the README version line, let CI build and verify the APK (badging, minSdk 24,
targetSdk 36, signature, SHA-256), and publish the GitHub release
(tag `nitroboost-vX.Y.Z`) from `main` only — after the TEST and REVIEW gates.

## When to Invoke (Triggers)

- RELEASE phase of a cycle, only after `adversarial-review` approved and tests
  are green (hard gate — never release unreviewed work).
- When preparing changelog/release notes.

## Inputs and Outputs

- Inputs: reviewed diff, CHANGELOG, CI workflow `.github/workflows/build-apk.yml`.
- Outputs: version bump commit, changelog entry, release notes, published
  release with `nitroboost-debug.apk` (+ `.sha256`) and signed release APK when
  secrets are configured; run recorded in the daily log.

## Execution Procedure

1. Versioning: `versionName` = semver (e.g. "1.24.0"), `versionCode` =
   previous + 1 (currently 30 → 31). Update the root `README.md` version line to
   match. One version bump per release.
2. Changelog: add an entry in `NitroBoost/CHANGELOG.md` (AR+EN style as the file
   does) summarizing changes, tests run (with CI run id/link), and explicit
   non-claims (e.g. "no device benchmark performed; no FPS gain claimed").
3. Build & verify via CI on the release branch/PR: `verify-build` must run
   `tools/verify.py`, `security_verify.py`, Python unittests,
   `tools/verify_skills.py`, then `./gradlew test lintDebug assembleDebug`;
   assert badging `package: name='com.nitroboost.app' versionCode/versionName`,
   `targetSdkVersion:'36'`, `min-sdk == 24`, `apksigner verify`, and write
   `nitroboost-debug.apk.sha256`.
4. Release APK: `prepare-release` builds unsigned; `sign-release` signs only in
   the isolated job with secrets (refuses incomplete secrets; rejects
   debuggable release APK); `publish-release` creates the GitHub release
   **only on `main`** with debug APK + sha256 (+ signed APK when available).
5. Locally (no SDK here): verify what is verifiable — `python3 tools/verify_skills.py`,
   `NitroBoost/tools/verify.py`, unittests — and state clearly that APK building
   happened in CI with run link (RULE-01).
6. If signing secrets are not configured, publish debug-only and say so.

## Acceptance Criteria

- [ ] versionName/versionCode bumped consistently (gradle + README).
- [ ] CHANGELOG entry includes changes, tests (with evidence), and non-claims.
- [ ] CI verify-build green with recorded run id; badging/minSdk/targetSdk/signature/sha256 asserted by the workflow.
- [ ] Release published from main only; assets attached; tag format `nitroboost-v<versionName>`.
- [ ] TEST + REVIEW gates passed before release (director sign-off recorded).

## Testing and Verification

- Local: `python3 tools/verify_skills.py && python3 NitroBoost/tools/verify.py && python3 NitroBoost/tools/security_verify.py && python3 -m unittest discover -s NitroBoost/tools -p 'test_*.py' && python3 -m unittest discover -s tools -p 'test_*.py'`.
- CI: check the workflow run for the release commit; record URL in the daily log.
- Post-release: download the APK artifact, verify SHA-256 matches the attached `.sha256`.

## Non-Negotiable Constraints

- RULE-01 (CI/local evidence recorded verbatim), RULE-02 (release notes never
  claim unmeasured performance), RULE-09 (failures block release), RULE-10/11.
- Never publish from a non-main ref; never publish with failing gates.

## Repository References

- `NitroBoost/app/build.gradle.kts` (versionName/versionCode), `NitroBoost/CHANGELOG.md`, `README.md`
- `.github/workflows/build-apk.yml` (verify-build / prepare-release / sign-release / publish-release)
- `NitroBoost/docs/DISTRIBUTION.md`, `dist/README.md`
