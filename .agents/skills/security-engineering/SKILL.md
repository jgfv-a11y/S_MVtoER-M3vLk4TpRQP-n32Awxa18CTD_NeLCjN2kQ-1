---
name: security-engineering
description: Security engineering for NitroBoost: reviews permissions, prevents command injection, validates all inputs (profiles, journal, shell), protects data and privacy (local-only, no cloud), and keeps CI/secrets safe. Invoke at IMPLEMENT and REVIEW of every cycle.
---

# Skill: security-engineering

## Mission and Scope

Keep NitroBoost safe and private by default: minimal permissions, no command
injection, strict input validation, local-only data (no cloud, no personal data
collection, no big AI model), safe CI (least-privilege permissions, SHA-pinned
actions, signing secrets only in the dedicated signing job).

## When to Invoke (Triggers)

- IMPLEMENT and REVIEW of every cycle; any change touching shell command
  construction, input parsing (profiles, journal, intents), manifest
  permissions, network, or CI workflow files.

## Inputs and Outputs

- Inputs: diff, `AndroidManifest.xml`, `core/ShellInput.kt`, task allow-lists,
  `core/ProfileValidation.kt`, `data/`, `.github/workflows/build-apk.yml`,
  `NitroBoost/docs/DISTRIBUTION.md`.
- Outputs: security review verdict per change; fixes; updated
  `tools/security_verify.py` checks when a new pattern class appears.

## Execution Procedure

1. Permissions review: every `<uses-permission>` and every manifest component
   must be justified; foreground service types declared correctly
   (SPECIAL_USE etc.); no exported component without need.
2. Command injection prevention: shell commands built only via `ShellInput`
   quoting + allow-lists; reject any concatenation of external input into
   commands; verify with `python3 NitroBoost/tools/security_verify.py`.
3. Input validation: `ProfileValidation` for user/imported profiles (schema,
   ranges, package-name format); defensive parsing of `journal.json`,
   `game_profiles.json`, prefs — malformed input is rejected/quarantined, never
   crashes, never executes.
4. Data & privacy: all data stays on device (`data/`, journal, session logs);
   no analytics SDK, no cloud upload, no network calls except the optional
   TCP ping probe to a fixed endpoint (failure → null); no personal data.
5. Secrets/CI: workflow `permissions:` minimal (`contents: read` default,
   `contents: write` only in publish-release); all actions SHA-pinned; signing
   secrets only in the isolated `sign-release` job (no build scripts there);
   `persist-credentials: false`; debuggable check on release APK.
6. Dangerous operations: root/shell privileged ops only via allow-listed
   reversible tasks; anything destructive requires explicit user action
   (RULE-05).

## Acceptance Criteria

- [ ] `security_verify.py` + `verify.py` pass; new risky pattern classes added
  to the verifier when discovered.
- [ ] No user-controlled string reaches a shell unquoted (code review +
  verifier).
- [ ] All parsed inputs validated; malformed → safe rejection.
- [ ] No new permission/network/analytics without justification in the review.
- [ ] CI permissions unchanged-or-tighter; secrets isolated.

## Testing and Verification

- `python3 NitroBoost/tools/security_verify.py`
- `python3 -m unittest discover -s NitroBoost/tools -p 'test_*.py'` (incl.
  security tests)
- `ProfileValidation`/journal parser unit tests for injection/overflow/malformed
  payloads.
- Manual adversarial pass in REVIEW (see `adversarial-review`).

## Non-Negotiable Constraints

- RULE-05 (no dangerous root commands without explicit approval), RULE-09
  (findings reported), privacy: local-only data is a product invariant.

## Repository References

- `core/ShellInput.kt`, `core/tasks/*.kt` (allow-lists), `core/ProfileValidation.kt`
- `NitroBoost/tools/security_verify.py`, `AndroidManifest.xml`
- `.github/workflows/build-apk.yml`, `NitroBoost/docs/DISTRIBUTION.md`
- `platform/NetSampler.kt` (only network probe), `data/`
