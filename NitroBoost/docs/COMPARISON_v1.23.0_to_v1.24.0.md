# NitroBoost 1.23.0 → 1.24.0 Comparison

| Area | v1.23.0 baseline | v1.24.0 result | User-visible consequence |
|---|---|---|---|
| Release identity | `1.23.0 / 30` | `1.24.0 / 31` | Same package identity; minimum API remains 24 |
| Capability reporting | Supported/pending booleans | Seven explicit states with reason/source/time | Permission, failure, temporary unavailability, and safety rejection are not conflated |
| Thermal override | Legacy task could be selected by old profile path | Rejected compatibility task; old field normalized false; legacy restore retained | Thermal safety cannot be bypassed by profile or UI |
| Journal | Durable ownership-aware journal | Same contract plus explicit thermal mutation guard and task-state integration | Safer restore and no new thermal override command |
| Adaptive loop | Paired quality-gated trials | Adds mandatory inter-trial cooldown and cooldown-aware ETA | Less carry-over/heat pressure; potentially longer trials |
| Profile storage | Built-in/custom JSON with atomic writes | Add versioned export/import envelope, SHA-256, strict limits, conflict confirmation | Profiles can be moved without silent overwrite |
| Dashboard | Nullable monitor values | Source/freshness projection | Stale/unavailable metrics are labeled, not shown as live evidence |
| Home/Profile reads | Some synchronous asset/file/Binder work | Lifecycle-scoped IO coroutines | Fewer UI stalls and no orphaned view callbacks |
| UI | Dark-only palette and basic task status | Day/Night palette, state pills, provenance label, import/export actions | Clearer state and accessibility cues |
| Widget | 60-second periodic update request | 30-minute conservative request | Less background work; tap remains immediate |
| Evidence claims | Existing measured-source safeguards | Additional provenance and safety language | Still no claim of FPS/benchmark improvement without a real benchmark |

## Compatibility decision

The application ID, minimum SDK, profile schema fields, Journal format, and user data locations are preserved. A debug APK is not automatically a valid update: Android signing certificate continuity must be checked against the installed v1.23.0 build or its release key.
