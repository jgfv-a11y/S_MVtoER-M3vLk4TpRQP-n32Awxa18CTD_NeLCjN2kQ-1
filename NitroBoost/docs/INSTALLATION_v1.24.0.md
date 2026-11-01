# NitroBoost 1.24.0 — Installation and Verification

## What is delivered

- `NitroBoost-v1.24.0-debug.apk` — debug/testing APK, not a signed release update.
- `NitroBoost-v1.24.0-source.zip` — source snapshot for this release.
- `SHA256SUMS` — SHA-256 checksums for the delivered files.

The files are generated only after CI build and metadata checks succeed. A debug APK does not prove performance improvement or signed update compatibility with v1.23.0.

## Device requirements

- Android 7.0 / API 24 or newer.
- Package identity: `com.nitroboost.app`.
- Optional Shizuku or root for privileged, device-dependent tasks.
- A real device is required for Shizuku, OEM Game Space, thermal behavior, and repeatable game benchmarks.

## Install a debug build

```bash
sha256sum -c SHA256SUMS
adb install -r NitroBoost-v1.24.0-debug.apk
```

`adb install -r` is not evidence that an upgrade is valid. Android accepts an update only when the package identity and signing certificate are compatible. This workspace has no confirmed v1.23.0 release key, so verify the APK certificate before treating it as an update:

```bash
apksigner verify --verbose NitroBoost-v1.24.0-debug.apk
apkanalyzer manifest application-id NitroBoost-v1.24.0-debug.apk
apkanalyzer manifest min-sdk NitroBoost-v1.24.0-debug.apk
```

A debug certificate is normally different from a production/release certificate. Do not uninstall a working production build merely to test a debug artifact if preserving its data matters.

## First run

1. Launch NitroBoost and select a game profile.
2. Keep thermal protection enabled; the legacy thermal override is deliberately unavailable.
3. Grant only the permissions and Shizuku/root access needed for the selected tasks.
4. Start with a moderate boost level and observe the source/freshness labels.
5. Use Restore after testing. Journal ownership checks prevent overwriting changes made by another actor.

## Build from source

The repository CI uses JDK 17, Gradle 8.14.3, Android API 36/build-tools 36.0.0, and runs:

```bash
cd NitroBoost
gradle wrapper --gradle-version 8.14.3
./gradlew test lintDebug assembleDebug --no-daemon
```

Then run the repository gates:

```bash
python3 tools/verify.py
python3 tools/security_verify.py
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

No release keystore is included. A release APK must be signed with the established application key outside this source snapshot and independently verified.
