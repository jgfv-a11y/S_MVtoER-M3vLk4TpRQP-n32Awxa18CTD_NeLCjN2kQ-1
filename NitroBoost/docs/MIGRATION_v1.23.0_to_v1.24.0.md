# NitroBoost 1.23.0 → 1.24.0 Migration Guide

## Before installing

1. Export custom profiles from v1.23.0 if they are important.
2. Run Restore and confirm no active Journal changes remain where possible.
3. Preserve the installed APK and its signing certificate if this is a production build.
4. Do not assume the v1.24.0 debug APK can update a release-signed v1.23.0 APK.

## Data migration

- `profiles_custom.json` remains in the app data location and is parsed with safe normalization. Legacy `thermalOverride=true` is converted to `false`.
- `journal.json` remains readable. Existing legacy thermal entries are not applied again; they may be restored through the legacy allow-list if ownership and current-value checks permit it.
- The adaptive ledger remains local context history. Old decisions are not treated as universal performance claims and may become stale when policy/context changes.
- Built-in profiles remain app-owned. Imported custom profiles shadow a built-in only for the exact same package name.

## After installation

1. Open Profiles and verify the active game and modules.
2. If a legacy thermal field appears, leave it disabled; this is intentional and protects Android thermal policy.
3. Open Boost and review each capability state. `Permission Required` means grant the relevant Shizuku/root access or disable that task; it does not mean unsupported hardware.
4. Use the new export action to create a v1.24 envelope. Keep the checksum file with the export.
5. Run a short session, then Restore. If Journal recovery is blocked, do not force another mutation; resolve the indicated durable-state issue first.

## Rollback to v1.23.0

Downgrading over v1.24.0 may be blocked by Android versionCode rules or may not understand future data. Prefer restoring from a verified backup and uninstalling only when data loss is acceptable. Do not manually edit Journal entries or profile JSON to force a thermal override. Keep an original signed APK and backup before attempting a downgrade.

## What migration does not promise

Migration preserves compatible data formats; it does not prove OEM runtime compatibility, signed-update compatibility, game performance improvement, or thermal behavior. Those require the target device and a repeatable benchmark.
