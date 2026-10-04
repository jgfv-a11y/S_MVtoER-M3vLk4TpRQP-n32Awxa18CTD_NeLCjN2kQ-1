# GitHub Actions — Build APK

The workflow is `.github/workflows/build-apk.yml`.

On every push to `main` or `arena/**` (and on manual dispatch) it:

1. Reads `versionName` / `versionCode` from `NitroBoost/app/build.gradle.kts`
2. Runs the static verifier, JVM unit tests, lint, and debug APK build
3. Optionally builds a **signed** release APK if the `NITRO_*` secrets exist
4. Refuses to publish a release APK that is `debuggable=true`
5. Uploads the APK as a workflow artifact and publishes it as an asset on the
   GitHub Release tagged `nitroboost-v{versionName}`

The workflow only publishes a GitHub Release; it does not push build artifacts
or failure logs to secondary branches. On failure, the build log is summarized
in the job and uploaded as the `build-fail-log` artifact.

## Signing secrets (optional)

`Settings → Secrets and variables → Actions`:

| Secret | Value |
|---|---|
| `NITRO_KEYSTORE_BASE64` | `base64 -w0 nitroboost-release.jks` |
| `NITRO_KEYSTORE_PASSWORD` | keystore password |
| `NITRO_KEY_ALIAS` | `nitroboost` |
| `NITRO_KEY_PASSWORD` | key password |

See `NitroBoost/docs/DISTRIBUTION.md` for keystore generation.

## Local build

```bash
cd NitroBoost
gradle wrapper --gradle-version 8.7   # once, if gradlew is missing
./gradlew test assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

- **SDK not found** — the workflow installs `platforms;android-34` and
  `build-tools;34.0.0` itself. Confirm the runner is `ubuntu-latest`.
- **Release APK not produced** — signing secrets are missing; debug APK
  still publishes.
- **Unit test failure** — inspect the job summary and download the
  `build-fail-log` workflow artifact.
