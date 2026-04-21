# MasterDnsVPN Android Client

[![Android CI](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/android-ci.yml)
[![Go Tests](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/go-test.yml/badge.svg)](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/go-test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [فارسی](readme_fa.md)

Android client application for the MasterDnsVPN ecosystem, built with a Kotlin/Jetpack Compose UI on top of the upstream Go networking core.

## Upstream and Scope

- Upstream core project: https://github.com/masterking32/MasterDnsVPN
- This repository focuses on the Android client implementation and packaging.
- Go engine logic is integrated through `gomobile` as an Android AAR.

## Features

- VPN tunnel connect/disconnect lifecycle control
- Real-time connection telemetry (state, throughput, scan progress)
- Structured connection logs with source/severity filtering
- Multi-profile management (create, select, edit, delete)
- Profile-level settings and resolver import workflows
- Global settings for proxy mode, split tunneling, and sharing options
- Local persistence using Room + DataStore
- API 21+ support (Android 5.0 and newer)

## Screens

- Home: connection status, traffic speed, active profile, quick actions
- Profiles: profile CRUD and import helpers
- Settings: profile and global configuration
- Logs: searchable-by-filter timeline with share/clear tools
- Info: project metadata and links

## Download and Install

1. Open the repository **Releases** page.
2. Download the latest **universal** APK (recommended for most users).
3. Install on your Android device.

If Android blocks installation, enable **Install unknown apps** for your browser/file manager and retry.

## Quick Start (In App)

You need an operational MasterDnsVPN server first.

1. Open **Profiles** and create or import a profile.
2. Set required values such as `DOMAINS` and `ENCRYPTION_KEY`.
3. Add DNS resolvers (one per line, as `IP` or `IP:PORT`).
4. Save the profile and select it.
5. Return to **Home** and tap **Connect**.

## Build From Source

### Requirements

- JDK 17
- Android SDK (platform/Build-Tools compatible with `compileSdk 35`)
- Go toolchain (as used in CI)
- `gomobile` and `gobind`

### Android Debug Build

```bash
# From repository root
bash ./android/build_go_mobile.sh
cd android
./gradlew :app:assembleDebug
```

### Release Build (local)

Release builds require signing environment variables (see workflow `release-manual.yml` for exact names):

- `ANDROID_SIGNING_ENABLED=true`
- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- Optional: `ANDROID_VERSION_NAME`, `ANDROID_VERSION_CODE`

Then run:

```bash
cd android
./gradlew :app:assembleRelease
```

## CI/CD

- `android-ci.yml`: builds Go mobile AAR and Debug APK artifacts on push/PR.
- `go-test.yml`: runs Go tests for applicable packages.
- `release-manual.yml`: manual signed release workflow that uploads APK/AAR assets.

## Security Notes

- Install APK files only from trusted releases.
- Never share `ENCRYPTION_KEY`, credentials, or full raw configs publicly.
- Redact sensitive values before sharing logs/screenshots.

## Troubleshooting

- **App not installed / update failed**: uninstall older build signed with a different key.
- **Connected but no internet**: verify domain, resolver list, and server reachability/config.
- **VPN disconnects immediately**: check VPN permission and battery/background restrictions.
- **No logs shown**: confirm a profile is selected and a connection attempt was made.

## Disclaimer

This project is provided for legitimate privacy and network-routing use cases. Users are responsible for complying with local laws, service terms, and organizational policies.

## License

MIT License. See [LICENSE](LICENSE).

## Credits

- MasterDnsVPN upstream project and contributors
- Upstream project link: https://github.com/masterking32/MasterDnsVPN
- Android client maintainers and community testers
