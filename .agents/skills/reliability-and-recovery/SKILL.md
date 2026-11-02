---
name: reliability-and-recovery
description: Engineers NitroBoost's Journal and rollback, crash/interruption handling, corrupted-log detection, and prevents restoring values that changed externally. Invoke for any change to core/Journal.kt, AtomicFileOps.kt, JournaledSystemExecutor.kt, or persistence paths.
---

# Skill: reliability-and-recovery

## Mission and Scope

Guarantee that NitroBoost can always **recover** to a consistent state: the
`Journal` records every reversible system change with its undo command *before*
execution and rolls back in reverse order; crashes and interruptions mid-write
never corrupt state (`AtomicFileOps`); values changed externally since the
journal entry are never overwritten by a blind restore (RULE-04); corrupted
journals are detected and handled transparently.

## When to Invoke (Triggers)

- Any change to `core/Journal.kt`, `core/AtomicFileOps.kt`,
  `core/JournaledSystemExecutor.kt`, `data/` persistence, or service
  start/stop recovery (`service/BoosterService.kt`).
- Any new reversible system command.

## Inputs and Outputs

- Inputs: journal file format (`journal.json`), executor paths, prefs/profile
  stores.
- Outputs: robust journal code; recovery on app start after kill/crash;
  explicit handling of corrupted entries; tests for all failure paths.

## Execution Procedure

1. Journal discipline: for every reversible write, store {path, previousValue,
  restoreCommand, timestamp, status} **before** executing; mark applied after
  verified success (RULE-08); on rollback apply in reverse order and keep
  failed entries visible (never silently drop them).
2. Atomicity: all file writes go through `AtomicFileOps` (write temp + rename),
   so a crash mid-write leaves the old file intact.
3. External-change guard (RULE-04): before restoring, read the current value;
   if it differs from the value recorded *after* our change (i.e. someone else
   changed it), skip the restore for that entry and log it — never clobber
   external changes.
4. Crash/interruption recovery: on service/app start, load journal; if a crash
   interrupted a session, roll back entries marked applied-but-unverified;
   automatic restore on exit is bounded and logged.
5. Corrupted journal: parse defensively; on malformed JSON or schema mismatch,
   quarantine the file (rename with timestamp), start a fresh journal, and
   surface a clear warning — never crash, never delete the evidence.
6. One-way actions (trim, force-stop) are excluded from rollback and clearly
   marked.

## Acceptance Criteria

- [ ] Every reversible change is journaled pre-execution with undo command.
- [ ] Rollback is reverse-order; failed restores stay visible in the journal.
- [ ] External-change detection skips blind restores (RULE-04).
- [ ] Writes are atomic; corrupted journal → quarantine + fresh start + warning.
- [ ] Recovery-after-kill path exists and is tested.

## Testing and Verification

- `JournalTest`/`AtomicFileOpsTest`-style JVM tests: ordering, corruption,
  external-change skip, partial failure, interrupted write, malformed JSON.
- Add a test for every new journal field/behavior; backward compatibility with
  existing `journal.json` on users' devices is mandatory.
- `python3 NitroBoost/tools/verify.py` for static sanity.

## Non-Negotiable Constraints

- RULE-04 (never restore over external changes), RULE-08 (verified success
  only), RULE-09 (corruption/failures surfaced, not hidden), RULE-11
  (rollback always possible).

## Repository References

- `core/Journal.kt`, `core/AtomicFileOps.kt`, `core/JournaledSystemExecutor.kt`
- `service/BoosterService.kt`, `data/Prefs.kt`, `data/ProfileStore.kt`, `data/SessionLog.kt`
- `README.md` (journal/rollback semantics), tests under `app/src/test/java/com/nitroboost/app/core/`
