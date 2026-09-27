# RevBoost 1.8.1-debug — مرجع تحليل (NOT part of NitroBoost)

This folder contains the **decompiled/extracted APK of the reference app
(RevBoost 1.8.1-debug, package `com.revboost.app.debug`)** that NitroBoost
was designed to outperform. It is kept **for competitive analysis only**.

- It is **not** a NitroBoost build — do not install, distribute or
  audit it as ours. Its `AndroidManifest.xml` (binary AXML), DEX files,
  `res/`, `kotlin/`, `assets/game_profiles.json` and `META-INF/` belong to
  the reference app.
- NitroBoost's real source is in `../NitroBoost/`; the distributable APK
  is the GitHub **release asset** `nitroboost-debug.apk` (also mirrored in
  `../../dist/`).
- Historical note: this dump originally sat at the repository root, which
  caused a security review to analyze the reference app instead of
  NitroBoost (see the 2026-09-26 report). It was moved here on 2026-09-26.
