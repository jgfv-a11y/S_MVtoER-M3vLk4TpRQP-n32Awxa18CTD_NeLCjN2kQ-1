# GitHub Actions Workflow Setup Guide

## Overview

The `build-release.yml` workflow automatically:
1. Builds debug APK on every push/PR
2. Runs lint and unit tests
3. Builds unsigned release APK
4. Signs release APK (if secrets configured)
5. Creates GitHub releases with signed APK

## Setup Instructions

### 1. Generate a Signing Keystore (One-time setup)

```bash
# Generate a new keystore (valid for 10 years)
keytool -genkey -v \
  -keystore nitroboost-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -alias nitroboost \
  -storepass YOUR_KEYSTORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD

# Verify the keystore
keytool -list -v -keystore nitroboost-release.jks -storepass YOUR_KEYSTORE_PASSWORD
```

### 2. Encode Keystore to Base64

```bash
# Convert keystore to base64 (macOS)
base64 -i nitroboost-release.jks | pbcopy

# Convert keystore to base64 (Linux)
base64 nitroboost-release.jks

# Convert keystore to base64 (Windows)
certutil -encode nitroboost-release.jks nitroboost-release.jks.b64
```

### 3. Add Secrets to GitHub

Go to: `Settings → Secrets and variables → Actions`

Add the following secrets:

| Secret Name | Value |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded keystore content (from step 2) |
| `KEYSTORE_PASSWORD` | Keystore password (e.g., `YOUR_KEYSTORE_PASSWORD`) |
| `KEY_ALIAS` | Key alias (e.g., `nitroboost`) |
| `KEY_PASSWORD` | Key password (e.g., `YOUR_KEY_PASSWORD`) |

### 4. Update build.gradle.kts (Already Done)

The `build.gradle.kts` is already configured to use these environment variables:

```kotlin
if (System.getenv("KEYSTORE_BASE64") != null) {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

## Workflow Triggers

The workflow runs automatically on:

1. **Push to main branch**
   - Builds debug APK
   - Updates `dist/nitroboost-debug.apk`
   - Runs tests and lint

2. **Pull requests to main**
   - Builds debug APK
   - Runs tests and lint
   - Does NOT create release

3. **Tag creation** (e.g., `git tag v1.6.0`)
   - Builds debug AND release APKs
   - Signs release APK (if secrets available)
   - Creates GitHub release with both APKs

4. **Manual trigger**
   - Via GitHub UI: `Actions → Build and Release → Run workflow`

## How to Create a Release

### Method 1: Via Git Tag (Recommended)

```bash
# Tag the current commit
git tag -a v1.6.0 -m "Release v1.6.0"

# Push tag to GitHub
git push origin v1.6.0
```

The workflow will automatically:
- Build both debug and release APKs
- Sign the release APK
- Create a GitHub release with both files

### Method 2: Via GitHub UI

1. Go to `Releases` → `Draft a new release`
2. Enter tag name (e.g., `v1.6.0`)
3. Fill in release notes
4. Click "Publish release"

The workflow is already triggered, and will publish APK files automatically.

## Artifact Locations

After a successful build:

- **Debug APK**: `dist/nitroboost-debug.apk` (updated on every main push)
- **Release APK**: Attached to GitHub releases (tagged versions only)
- **Build artifacts**: Available in "Actions → [Run] → Artifacts" for 30 days

## Troubleshooting

### Build fails with "SDK not found"
- The workflow auto-installs Android SDK.
- Ensure you're running on `ubuntu-latest`.

### Release APK signing fails
- Check that all 4 secrets are configured correctly.
- Verify keystore password and key password match.
- Ensure key alias exists in keystore.

### No release APK created
- Signing only happens on tag pushes (refs/tags/v*).
- Check that the tag matches the pattern `v*`.

### APK not in dist folder
- Only debug APK updates `dist/` (on main branch).
- Release APKs are available in GitHub releases.

## Manual Local Build

If you want to build locally without CI:

```bash
cd NitroBoost

# Build debug
./gradlew assembleDebug

# Build release (unsigned)
./gradlew assembleRelease

# Run tests
./gradlew test
```

APKs will be in `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

## Security Notes

- Keystore and passwords are stored securely in GitHub Secrets.
- They are never printed to workflow logs.
- Release APK is signed with SHA256withRSA (industry standard).
- Debug APK is unsigned (for development only).

## Next Steps

1. Generate and secure your keystore
2. Add secrets to GitHub
3. Push a tag to trigger the release
4. Download APK from GitHub releases
