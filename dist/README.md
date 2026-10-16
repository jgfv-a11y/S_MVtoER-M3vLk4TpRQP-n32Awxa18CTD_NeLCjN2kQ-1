# APK distribution

Verified APK files are published as immutable assets on the repository's
[GitHub Releases](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/releases) page.

The current testing build is `nitroboost-debug.apk` under the
[`nitroboost-v1.21.0`](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/releases/tag/nitroboost-v1.21.0)
release. Verify it with the adjacent `nitroboost-debug.apk.sha256` file before
installation. A signed release APK appears only when the repository's original
signing secrets are configured; never replace that key merely to create an
artifact.

Generated APK binaries are intentionally not duplicated in Git history: release
assets retain version, provenance, checksum, and CI linkage without making every
source clone download old binaries.
