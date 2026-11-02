# AGENTS.md — NitroBoost Autonomous Engineering Skills System (v1.0.0)

> **This file is the mandatory entry point for every agent working on this repository.**
> It is read before any development work starts. It defines the skills system, the
> development cycle, the team-role model, and the non-negotiable rules.

## 1. What this repository is

NitroBoost is a native Android (Kotlin + Material 3) game-booster app rooted at
`NitroBoost/`. It uses Shizuku/root shell channels for system tuning, a reversible
change `Journal`, a hard `ThermalGuard`, and a measurement-gated adaptive A/B engine.
Current version: **v1.23.0 (versionCode 30)**, `minSdk 24` / `targetSdk 36` /
`compileSdk 36`, Java 17. See `README.md` and `NitroBoost/README.md`.

## 2. The skills system (MANDATORY)

All engineering skills live in **`.agents/skills/<skill-name>/SKILL.md`**.
The machine-readable catalog is **`.agents/skills/registry.json`**.

- 13 skills are defined. **Do not start a development cycle without reading the
  skills relevant to that cycle** (see the phase→skill map in
  `.agents/DEVELOPMENT_CYCLE.md`).
- Every `SKILL.md` has YAML frontmatter (`name`, `description`) so platforms that
  support the Agent Skills convention can auto-discover it, plus fixed sections:
  Mission, Triggers, Inputs/Outputs, Execution Procedure, Acceptance Criteria,
  Testing & Verification, Non-Negotiable Constraints, Repository References.
- The structural integrity of the whole system is enforced automatically by
  **`tools/verify_skills.py`** (runs in CI via `.github/workflows/build-apk.yml`
  and can be run locally: `python3 tools/verify_skills.py`). If it fails, the
  skills system is broken and must be fixed **before** any code change.

### Skill invocation map (summary — full detail in `.agents/DEVELOPMENT_CYCLE.md`)

| Cycle phase | Mandatory skills |
|---|---|
| Cycle start | `engineering-director`, `repository-intelligence` |
| DISCOVER / ANALYZE | `repository-intelligence`, `adversarial-review` (findings) |
| INNOVATE | `autonomous-feature-inventor` |
| PLAN | `engineering-director`, `android-architecture` |
| IMPLEMENT | `android-architecture`, `system-integration-engineering`, `thermal-safety-engineering`, `reliability-and-recovery`, `security-engineering` |
| TEST | `automated-testing`, `performance-engineering` (only for perf claims), `adaptive-engineering` (only for A/B changes) |
| REVIEW | `adversarial-review`, `security-engineering` |
| RELEASE | `release-engineering`, `engineering-director` (readiness decision) |
| LEARN | `engineering-director` (updates skills/changelog when a real gap is found) |

## 3. The development cycle (MANDATORY)

Every change follows the cycle defined in **`.agents/DEVELOPMENT_CYCLE.md`**:

```
DISCOVER → ANALYZE → INNOVATE → PLAN → IMPLEMENT → TEST → REVIEW → RELEASE → LEARN
```

- **No phase may be skipped.** TEST and REVIEW are hard gates: a change that has
  not passed them is not merged and not released.
- Autonomous innovation is expected at INNOVATE (`.agents/INNOVATION.md`): scan
  the app for weaknesses, find unexploited opportunities, study suitable
  techniques, compare solutions, score proposals by value/risk/cost, implement
  only low-risk evidence-backed improvements autonomously, and test before
  adopting. Do not duplicate existing features and do not add features just to
  show activity.
- Each cycle writes a log to `docs/daily/YYYY-MM-DD.md` following the existing
  convention.

## 4. Team model

`.agents/TEAM.md` defines 9 engineering roles and a single central orchestrator
(`engineering-director`) responsible for distributing tasks, tracking execution,
reviewing results, resolving conflicts, merging changes, and making the
release-readiness decision.

**Honest platform constraint:** this platform (Arena Agent Mode) does **not**
provide sub-agents in this session. Therefore the roles are executed **sequentially
by the same agent, in the documented order** — and no claim of a parallel team is
ever made. If sub-agents become available, the same role definitions apply and
may be delegated.

## 5. Non-negotiable rules

`.agents/RULES.md` lists the 11 rules that cannot be traded away (RULE-01 …
RULE-11), including: never claim an unrun test or an unmeasured performance gain;
never sacrifice thermal protection; never restore system settings over external
changes; never run dangerous root commands without explicit approval; never
delete existing functionality without proof it is unneeded; never upgrade
Gradle/Kotlin/dependencies without justification; never confuse "command sent"
with "command executed"; never hide errors or failing tests; never adopt a new
feature without acceptance tests; always keep the ability to roll back.

## 6. Known constraints / limitations (documented, not hidden)

1. **Auto-discovery is not guaranteed.** Whether the platform automatically loads
   `.agents/skills/*/SKILL.md` at runtime could not be verified from inside the
   sandbox (no platform docs reachable). Therefore this `AGENTS.md` is the
   **guaranteed** discovery path: it is at the repository root, it names the
   skills directory, the registry, and the invocation map explicitly. If the
   platform supports Agent-Skills-style frontmatter discovery, the same files are
   valid for it (standard `name`/`description` frontmatter).
2. **No sub-agents** → sequential role execution (see §4).
3. **No local Android SDK/Gradle in this sandbox** → JVM tests, lint and APK
   builds run in CI (`.github/workflows/build-apk.yml`); local verification is
   limited to `tools/verify.py`, `tools/security_verify.py`, Python unittests,
   and `tools/verify_skills.py`. Device benchmarks require a real device and are
   never claimed from CI alone.
4. Skills system changes are versioned in `.agents/CHANGELOG.md`; the system must
   persist across releases and is **updated, not rebuilt from scratch**.

## 7. How to update this system

- Add/remove/rename a skill → update the directory, `registry.json`,
  `.agents/skills/README.md`, this file's invocation map, and
  `.agents/CHANGELOG.md`; then run `python3 tools/verify_skills.py` (also run by
  CI) — all must pass before merging.
- A real gap discovered during a cycle (LEARN phase) → open an entry in
  `.agents/CHANGELOG.md` with the reason and the test that proved the gap.
