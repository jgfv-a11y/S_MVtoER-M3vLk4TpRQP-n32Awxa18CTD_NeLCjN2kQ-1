---
name: system-integration-engineering
description: Engineers NitroBoost's Shizuku/root/shell/sysfs integration: verifies permissions and sessions, handles command failure and timeouts (incl. Android 7), prevents command injection, and blocks unsafe changes. Invoke for any change under platform/ or core/tasks/ or the Shizuku AIDL service.
---

# Skill: system-integration-engineering

## Mission and Scope

Own everything that talks to the system: `platform/ShizukuShell.kt`,
`platform/RootShell.kt`, `platform/ShellOutputReader.kt`, `core/SystemExecutor.kt`,
`core/JournaledSystemExecutor.kt`, `core/ShellInput.kt`, the AIDL service
`INitroService.aidl` + `service/NitroUserService.kt`, and all `core/tasks/*`
command builders. Guarantee: permissions and sessions checked, command failure
and timeouts handled (including Android 7 behavior), and **no unsafe or
injectable command ever runs**.

## When to Invoke (Triggers)

- Any change in `platform/`, `core/tasks/`, `core/SystemExecutor.kt`,
  `core/JournaledSystemExecutor.kt`, `core/ShellInput.kt`, or the Shizuku AIDL/service.
- Any new sysfs path or shell command.

## Inputs and Outputs

- Inputs: Shizuku binder status (`ShizukuShell`), root availability (`RootShell`),
  task definitions, allow-lists.
- Outputs: safe executor code; every command allow-listed, shell-quoted via
  `ShellInput`, bounded in time, verified by read-back/exit code before being
  recorded as success (RULE-08); journal entry with undo command for reversible
  writes (RULE-11).

## Execution Procedure

1. Channel selection order: Shizuku (preferred, user-granted) → root (only if
   user explicitly enabled) → none (feature degrades gracefully with a clear
   "unavailable" state, never a crash).
2. Before any command: check permission (`Shizuku.checkSelfPermission` /
   binder alive / root shell responsive) and the session is valid; on denial,
   report and skip — never retry-loop.
3. Build commands only through `ShellInput`/allow-listed templates: no string
   concatenation of user-controlled input into shell commands (command
   injection prevention — checked by `tools/security_verify.py`).
4. Every execution is bounded: timeout + output cap (`ShellOutputReader`);
   on Android 7 (API 24) use the compatibility path for process timeouts
   (documented in README) — never assume modern `Process.waitFor(timeout)`.
5. Handle failure explicitly: non-zero exit, timeout, empty output → mark the
   task failed, keep the journal entry, never record success (RULE-08), surface
   the error to UI/log (RULE-09).
6. Writes to sysfs go through `JournaledSystemExecutor` with previous value +
   restore command stored **before** execution.
7. One-way actions (trim, force-stop, cache clean) are never journaled as
   reversible — they are listed as irreversible and require explicit user
   action.

## Acceptance Criteria

- [ ] Every command is allow-listed/quoted; `security_verify.py` passes.
- [ ] Permission/session checked before execution; graceful degradation.
- [ ] Timeouts bounded on all API levels incl. API 24; output capped.
- [ ] Success recorded only after read-back/exit-code verification.
- [ ] Reversible writes journaled before execution; one-way actions not
  journaled.

## Testing and Verification

- JVM tests with `FakeExecutor`: each task's command building, failure paths,
  timeout paths, journal/rollback (`EngineTest`, `*TaskTest`,
  `ShellOutputReaderTest`).
- `python3 NitroBoost/tools/security_verify.py` (injection/static security).
- `python3 NitroBoost/tools/verify.py`.

## Non-Negotiable Constraints

- RULE-05 (no dangerous root commands without explicit user approval),
  RULE-08 (sent ≠ executed), RULE-11 (rollback path), RULE-03 (thermal tasks
  never weaken protection — see `ThermalOverrideTask` constraints).

## Repository References

- `platform/ShizukuShell.kt`, `platform/RootShell.kt`, `platform/ShellOutputReader.kt`
- `core/SystemExecutor.kt`, `core/JournaledSystemExecutor.kt`, `core/ShellInput.kt`
- `core/tasks/*.kt` (allow-lists), `aidl/.../INitroService.aidl`, `service/NitroUserService.kt`
- `NitroBoost/tools/security_verify.py`, `app/src/test/.../FakeExecutor.kt`
