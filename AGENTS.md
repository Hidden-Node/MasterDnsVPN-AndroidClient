# AGENTS.md — conventions for humans and plan-executor agents

This repo is the **Android client** of MasterDnsVPN. The upstream Go core lives
under `cmd/` and `internal/`; the bridge to Android is under `mobile/`. Plan
executors (e.g. dispatched by the `/improve` skill) and human contributors
should follow the conventions below.

## Repository layout

| Path | Role | Edit policy |
|------|------|-------------|
| `android/` | Gradle Android app: Kotlin + Jetpack Compose + Hilt + Room + DataStore. `minSdk 21`, `compileSdk 36`, JDK 17. | Free to edit. |
| `mobile/` | Go gomobile bridge. `mobile.go` exports `mobile.Mobile.*` to Kotlin via `gomobile bind`. `mobile/tun/` is the FakeDNS proxy + DNS mapper. | Free to edit. Rebuild AAR after. |
| `cmd/`, `internal/` | Upstream Go core. | DO NOT EDIT in Android-client plans. The script `android/build_go_mobile.sh` auto-traps if `go.mod`/`go.sum` are touched. |
| `scripts/`, `docker/`, `assets/` | Server-side tooling. | Out of scope for the Android client. |
| `plans/` | Advisor plan files + this index. | Executors update their row in `plans/README.md` when done. |
| `.github/workflows/` | CI: `android-ci.yml` (Debug APK + AAR), `go-test.yml` (Go tests, `mobile/` top-level excluded), `release-manual.yml` (signed release). | Edit when adding CI steps (e.g. lint, tests). |

## Build & test commands

All commands run from the repository root unless noted.

```bash
# Build the gomobile AAR (writes android/app/libs/masterdnsvpn.aar; pinned via android/gomobile.version)
bash ./android/build_go_mobile.sh

# Build the debug APK (AAR must already exist)
cd android && ./gradlew :app:assembleDebug

# Run the Go tests that CI runs (top-level mobile/ package is excluded)
go test $(go list ./... | grep -v -e '^masterdnsvpn-go$' -e '^masterdnsvpn-go/mobile$')

# Typecheck Kotlin (no dedicated script — assembleDebug IS the typecheck)
cd android && ./gradlew :app:compileDebugKotlin
```

There is currently **no Android lint task**, **no `ktlint`/`detekt`** plugin, and **no
Kotlin unit-test or instrumented-test suite**. Plans that need verification on
the Kotlin side must add the scaffolding first (see `plans/005-kotlin-test-baseline.md`).

## Critical invariants

1. `go.mod` and `go.sum` are **immutable from the Android side**. The script
   `android/build_go_mobile.sh` runs in an isolated `mktemp` tree precisely so
   the real root files are not perturbed, and traps on changes:
   ```
   if ! git diff --quiet -- go.mod go.sum; then
     echo "ERROR: gomobile build modified shared Go module files."
     exit 1
   fi
   ```
   If a plan appears to require editing `go.mod`/`go.sum`, stop and report back —
   the upstream core owns those files.
2. The Go bridge `mobile/mobile.go` top-level package is **excluded from
   `go-test.yml`** (`grep -v '^masterdnsvpn-go/mobile$'`). Do not add Go files
   to `mobile/` expecting CI to test them automatically; either add a test file
   in `mobile/` AND remove the exclusion, or scope the file to `mobile/tun/`.
3. The FakeDNS code path is **Go-side** (`mobile/tun/fakedns_proxy.go`), NOT
   the legacy Kotlin `dns/FakeDnsServer.kt`/`FakeDnsInterceptor.kt`. The Kotlin
   `dns/` package is dead code per `plans/007-delete-dead-code.md`. Do not
   re-wire Kotlin-side FakeDNS; do not import from `com.masterdns.vpn.dns`.

## Code conventions (match existing patterns)

- **Kotlin ViewModel**: use Hilt `@HiltViewModel constructor(...)`. Expose
  StateFlow via `... .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultValue)`.
  Exemplar: `ui/home/HomeViewModel.kt`.
- **Room entity + DAO**: exemplar `data/local/ProfileEntity.kt` + `ProfileDao.kt`.
- **Compose screen**: exemplar files in `ui/<feature>/`. Use `MdvColor` from
  `ui/theme/MdvTokens.kt` for colors — do not hardcode hex.
- **DI**: Hilt module under `di/`. Exemplar: `di/AppModule.kt`.
- **Error handling**: prefer `runCatching { }.onFailure { }` for I/O / IPC;
  swallow only with a logged `VpnManager.appendLog(...)` call.
- **Commit style**: conventional commits, lowercase, scope in parens
  (`fix(vpn):`, `feat(ui):`, `chore:`, `docs:`). See `git log --oneline -15`
  for recent examples.

## Release signing env vars

Local signed release (`cd android && ./gradlew :app:assembleRelease`):
- `ANDROID_SIGNING_ENABLED=true`
- `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- Optional: `ANDROID_VERSION_NAME`, `ANDROID_VERSION_CODE`

CI signed release (`.github/workflows/release-manual.yml`):
- `ANDROID_KEYSTORE_BASE64` (decoded to `android/release.jks` by the workflow)

Do NOT commit keystores or AARs — both are gitignored per `plans/001-gitignore-android-entries.md`.

## Plans index

See `plans/README.md` for the active plan list, execution order, and status.
