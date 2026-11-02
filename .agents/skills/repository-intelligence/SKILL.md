---
name: repository-intelligence
description: Analyzes the whole NitroBoost repository: maps architecture and component relationships, finds technical debt, dependencies and risks, and produces audit documents in NitroBoost/docs/. Invoke at DISCOVER and ANALYZE of every cycle.
---

# Skill: repository-intelligence

## Mission and Scope

Achieve **complete, current understanding of the repository** before any change:
map the architecture and the relationships between components, and surface
technical debt, dependencies, and risks — with file-level evidence.

## When to Invoke (Triggers)

- DISCOVER and ANALYZE phases of every cycle (mandatory at cycle start).
- Before touching any unfamiliar area; before planning refactors.
- When a bug's root cause is unclear.

## Inputs and Outputs

- Inputs: the repo tree, `AGENTS.md`, prior audits in `NitroBoost/docs/`
  (`REPOSITORY_AUDIT_v1.21.0.md`, `ARCHITECTURE_AUDIT_v1.13.0.md`,
  `ENGINEERING_UPDATE_v1.23.0.md`), `docs/AUTONOMOUS_PROGRESS.md`.
- Outputs: an audit/analysis note (in the daily log, or a dated file under
  `NitroBoost/docs/` for major audits) containing: component map, data flow,
  dependency list with versions, debt list with severity, risk list.

## Execution Procedure

1. Map the layout: `NitroBoost/app/src/main/java/com/nitroboost/app/`
   (`core/` engine+journal+thermal+adaptive+tasks+telemetry,
   `platform/` shell/Shizuku/monitor/samplers, `data/` prefs/profiles/session,
   `ui/`, `service/`), `res/` (incl. `values-ar`), `assets/game_profiles.json`,
   `tools/` (Python verifiers), `.github/workflows/build-apk.yml`, `docs/`,
   `reference/` (not part of the app).
2. Trace key flows: boost session start (`BoostEngine` → `AllTasks` →
   `JournaledSystemExecutor` → `ShizukuShell`/`RootShell` → sysfs), rollback
   (`Journal`), monitoring (`MonitorHub`/`Monitor` → `FpsSampler`,
   `NetSampler`, telemetry), adaptive A/B (`core/adaptive/`), UI state (`AppStore`).
3. List dependencies with exact versions from `NitroBoost/app/build.gradle.kts`
   and `settings.gradle.kts`; flag unpinned/unjustified ones (RULE-07).
4. Find debt/risks: duplicated logic, dead code, TODO/FIXME, unsafe string-built
   commands, missing null-semantics, API-level hazards (e.g. `java.nio.file`
   needs API 26 — banned for minSdk 24), CI permission scope, secrets handling.
5. Run `python3 NitroBoost/tools/verify.py` for a static baseline (resources,
   manifest classes, Kotlin balance) and record the counts.
6. Write the analysis note with file:line references and a severity ranking.

## Acceptance Criteria

- [ ] Component map covers all top-level packages and their relationships.
- [ ] Every claim (debt/risk/dependency) cites a file (and line where possible).
- [ ] Baseline verifier output recorded (errors must be 0 or explained).
- [ ] Output distinguishes "confirmed in code" from "assumed".

## Testing and Verification

- `python3 NitroBoost/tools/verify.py` exits 0.
- `python3 tools/security_verify.py` exits 0.
- Cross-check at least 3 traced flows by reading the actual code, not docs only.

## Non-Negotiable Constraints

- Never claim understanding of a component without reading its code.
- `reference/` is review-only material — never treated as app code.
- RULE-02: repo analysis never substitutes for device measurement.

## Repository References

- `NitroBoost/app/src/main/java/com/nitroboost/app/{core,platform,data,ui,service}`
- `NitroBoost/app/build.gradle.kts`, `NitroBoost/settings.gradle.kts`
- `NitroBoost/tools/verify.py`, `NitroBoost/tools/security_verify.py`
- `NitroBoost/docs/*.md`, `docs/AUTONOMOUS_PROGRESS.md`, `.github/workflows/build-apk.yml`
