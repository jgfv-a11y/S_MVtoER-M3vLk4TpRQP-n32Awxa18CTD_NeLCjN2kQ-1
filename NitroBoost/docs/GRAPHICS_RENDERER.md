# Vulkan/OpenGL renderer scope

## What NitroBoost adds

The Settings screen now includes a manual, confirmation-gated experiment modeled on the approach documented by [GAMA](https://github.com/palincat/gama): set Android's `debug.hwui.renderer` property to `skiavk` (request Skia/Vulkan) or `opengl` (request OpenGL). GAMA's renderer-switch transaction-state pattern was adapted under its MIT license; see [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

This is **not** part of the automatic game-boost profile and is not applied when a game starts. Its property writes are narrowly allow-listed, recorded in the separate `renderer_journal.json` before mutation, verified by read-back, and recoverable with **Restore original property**. A damaged rollback journal blocks further writes. NitroBoost does not force-stop System UI, restart launchers, or silently reapply the choice after reboot.

The app remains installable on Android 7 (`minSdk=24`) and continues to target API 36. The experimental control itself is gated to Android 10/API 29 or newer, requires Shizuku or root, and enables the Vulkan request only when Android reports a Vulkan hardware feature.

## Important limitation

`debug.hwui.renderer` selects a renderer for Android's HWUI/Skia UI pipeline. It does **not** convert a Unity/Unreal/native game from OpenGL ES to Vulkan, nor does it prove that HWUI actually accepted or uses the requested backend. A successful `getprop` read-back only confirms the property value. Android builds, OEM policy, driver support, and process startup timing may change or ignore the behavior; the system UI could become unstable. The control is therefore opt-in, warns before changes, and exposes a separate rollback action. Use the game's own graphics setting or the device manufacturer's supported game-driver controls for a game-specific choice.

No FPS, latency, stability, or battery improvement is claimed. This branch has no physical-device or per-game benchmark evidence.

## Suggested next update

A suitable next feature release is **v1.23.0 (versionCode 30)**, focused first on a *per-game graphics compatibility report* rather than an unconditional Vulkan switch. It should inspect only supported Android/OEM interfaces for a game's own renderer choice or an installed ANGLE/driver profile, show when the path is unavailable, and require a per-game opt-in with a tested fallback. ANGLE is a possible route for some OpenGL ES apps on some Android builds; it must not be presented as universal Vulkan forcing. Validate that follow-up on multiple OEM/Android versions and with the selected game before enabling any automatic behavior.
