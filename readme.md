# MasterDnsVPN Android Client (Community Build)

A community-maintained Android client package based on the original MasterDnsVPN project.

- Original project: https://github.com/masterking32/MasterDnsVPN
- This repository includes Android app source + Go core source + CI workflows.

## Features of this repository

- Android app source code (`android/`)
- Go core source code (`mobile/`, `internal/`, `cmd/`)
- GitHub Actions for:
  - Building Go mobile AAR
  - Building Android APKs
  - Running Go tests
- APK split outputs (like SlipNet-style builds):
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86`
  - `universal`

## Uploading with GitHub website (no terminal required)

1. Create a new empty repository on GitHub.
2. Open the new repository page.
3. Click **Add file** -> **Upload files**.
4. Drag and drop all files/folders from this package (`github_publish_package`) into the browser.
5. Commit the upload to `main` branch.
6. Go to **Actions** tab and wait for workflow completion.
7. Download APK/AAR from workflow artifacts.

## Build artifacts from GitHub Actions

Workflow: `.github/workflows/android-ci.yml`

Artifacts:
- `app-debug-apk-splits` (ABI + universal APKs)
- `masterdnsvpn-aar` (Go mobile AAR)


## Manual release with your own tags

If you want to decide tags yourself, use workflow:

- `.github/workflows/release-manual.yml`

Steps:
1. Go to **Actions** -> **Manual Release**
2. Click **Run workflow**
3. Enter your tag (for example `v1.0.0`)
4. Optionally set release title
5. Run

The workflow builds AAR + APK splits and publishes them to GitHub Releases under your chosen tag.

## Signed release APKs (recommended for safe updates)

To ensure users can install and **update** the app without signature errors, publish **signed Release APKs** using a single, stable keystore.

This repository's manual release workflow expects these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64 of your `*.jks` keystore file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: key alias
- `ANDROID_KEY_PASSWORD`: key password

PowerShell helper to copy Base64 to clipboard:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("masterdnsvpn-release.jks")) | Set-Clipboard
```

Important:

- Never commit your keystore file to the repository.
- Keep `applicationId` stable and increase `versionCode` for each update.

## Local build (optional)

Build Go mobile AAR:

```bash
./android/build_go_mobile.sh
```

Build Android APKs:

```bash
cd android
./gradlew :app:assembleDebug
```

## Notes

- `android/local.properties` is intentionally excluded.
- Android SDK/NDK are required for gomobile builds.
- CI uses Java 17 + Go + Android SDK.

## Credits

This repository is based on the original MasterDnsVPN project:

- https://github.com/masterking32/MasterDnsVPN
