# AGENTS.md — conventions for humans and plan-executor agents

This repo is the **Android client** of MasterDnsVPN. The upstream Go core lives
under `cmd/` and `internal/`; the bridge to Android is under `mobile/`. Plan
executors (e.g. dispatched by the `/improve` skill) and human contributors
should follow the conventions below.

## Repository layout

| Path | Role | Edit policy |
|------|------|-------------|
| `android/` | Gradle Android app: Kotlin + Jetpack Compose + Hilt + Room + DataStore. `minSdk 21`, `compileSdk 36`, JDK 17. | Free to edit. |
| `mobile/` | Go gomobile bridge. `mobile.go` exports `mobile.Mobile.*` to Kotlin via `gomobile bind`. `mobile/tun/` is the gVisor TUN bridge — the only engine; the FakeDNS proxy + DNS mapper were deleted. | Free to edit. Rebuild AAR after. |
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
3. The DNS path is **Go-side** (`mobile/tun/gvisor_bridge.go`), NOT Kotlin:
   the FakeDNS proxy and the DNS mapper were deleted, and no Kotlin `dns/`
   package exists. Do not re-wire a Kotlin-side DNS stack; the bridge
   answers DNS in-process.

## Code conventions (match existing patterns)

- **Kotlin ViewModel**: use Hilt `@HiltViewModel constructor(...)`. Expose
  StateFlow via `... .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultValue)`.
  Exemplar: `ui/home/HomeViewModel.kt`.
- **Room entity + DAO**: exemplar `data/local/ProfileEntity.kt` + `ProfileDao.kt`.
- **Compose screens & UI components**:
  - **Design tokens**: Use `MdvColor`, `MdvSpace`, and `MdvRadius` from
    `ui/theme/MdvTokens.kt`. Never hardcode raw hex colors (e.g. `Color(0xFF...)`),
    raw dimensions when tokens exist, or stock Compose colors like `Color.Gray`.
    Declare missing semantic tokens in `MdvTokens.kt` before consuming them.
  - **Reusable MDV primitives**: Always use standard components under
    `ui/components/mdv/` (`MdvTopAppBar`/`MdvBackTopAppBar` for headers,
    `MdvCardLow`/`MdvCardHigh`/`MdvSectionCard` for cards, `MdvFilterChip`,
    `MdvPrimaryActionButton`) rather than building raw ad-hoc equivalents.
  - **Responsive layouts (No code duplication)**: When supporting wide/portrait
    modes (e.g. `metrics.isWide`), never copy-paste child widget trees across
    `if-else` branches. Extract sub-composables and keep the single source of truth;
    responsive branching should only toggle container arrangement (`Row` vs `Column`),
    alignment, and modifier constraints.
  - **No speculative/dead code**: Never create components, helpers, or tokens
    "for the future" without an immediate live call-site. Delete dead UI code proactively.
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
