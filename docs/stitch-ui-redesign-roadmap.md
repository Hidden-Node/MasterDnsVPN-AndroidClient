# MasterDnsVPN Android UI Redesign Roadmap (Stitch-Aligned, AI-Ready)

Last updated: April 20, 2026  
Target platform: Android 5.0+ (API 21+)  
Frontend stack target: Jetpack Compose (primary) + XML parity mapping (secondary)  
Core logic scope: Presentation layer only. Go core behavior remains unchanged.

---

## 1) Objective and Non-Negotiable Constraints

This document is a decision-complete implementation roadmap for redesigning the Android UI using the Stitch visual language while preserving current app behavior and navigation.

### 1.1 Goals
- Adopt Stitch aesthetic: color language, typography, tonal layering, icon treatment, and spacing rhythm.
- Keep all existing features/pages/routes intact.
- Produce AI-ready implementation instructions (tokens, component names, hierarchy, IDs, state matrix, callback contracts).
- Keep runtime lightweight and smooth on API 21 devices.

### 1.2 Locked Constraints
- Do not modify Go core logic or protocol behavior.
- Preserve current navigation/tab architecture:
  - Bottom tabs: Home, Profiles, Logs, Settings
  - Secondary pages: Info, Profile Settings
- UI must remain responsive on phones, tablets, and foldables.
- Avoid heavyweight effects on low-end devices (especially blur/shader-heavy layers).
- Maintain clear state guidance for Idle, Connecting, Connected, Error across all pages.

---

## 2) Source of Truth

### 2.1 Current app implementation references
- Navigation: `android/app/src/main/java/com/masterdns/vpn/ui/navigation/AppNavigation.kt`
- Theme: `android/app/src/main/java/com/masterdns/vpn/ui/theme/Theme.kt`
- Screens:
  - `android/app/src/main/java/com/masterdns/vpn/ui/home/HomeScreen.kt`
  - `android/app/src/main/java/com/masterdns/vpn/ui/profiles/ProfilesScreen.kt`
  - `android/app/src/main/java/com/masterdns/vpn/ui/logs/LogsScreen.kt`
  - `android/app/src/main/java/com/masterdns/vpn/ui/settings/GlobalSettingsScreen.kt`
  - `android/app/src/main/java/com/masterdns/vpn/ui/settings/SettingsScreen.kt`
  - `android/app/src/main/java/com/masterdns/vpn/ui/info/InfoScreen.kt`
- VPN state bridge: `android/app/src/main/java/com/masterdns/vpn/util/VpnManager.kt`

### 2.2 Stitch reference artifacts
- `stitch_ui/aether_protocol/DESIGN.md`
- `stitch_ui/home_spec_4.3/code.html`
- `stitch_ui/profiles_spec_4.4/code.html`
- `stitch_ui/profile_settings_spec_4.5/code.html`
- `stitch_ui/global_settings_spec_4.6/code.html`
- `stitch_ui/logs_spec_4.7/code.html`
- `stitch_ui/info_spec_4.8/code.html`

---

## 3) Design System Schema

## 3.1 Color Tokens (Stitch-derived)

Use these exact tokens and names. Hex values are frozen for deterministic code generation.

| Token | Hex | Usage |
|---|---:|---|
| `mdv.color.background` | `#10141A` | App background |
| `mdv.color.surface.lowest` | `#0A0E14` | Deepest container |
| `mdv.color.surface.low` | `#181C22` | Secondary card area |
| `mdv.color.surface.default` | `#1C2026` | Standard container |
| `mdv.color.surface.high` | `#262A31` | Elevated cards |
| `mdv.color.surface.highest` | `#31353C` | Active dialogs/sheets |
| `mdv.color.surface.bright` | `#353940` | Highlight rim layer |
| `mdv.color.primary` | `#C3F5FF` | Primary action text/accent |
| `mdv.color.primary.container` | `#00E5FF` | Active accent fill |
| `mdv.color.primary.dim` | `#00DAF3` | Connecting/pulse tint |
| `mdv.color.secondary` | `#BAC8DC` | Secondary text/icon |
| `mdv.color.tertiary` | `#E3EEFF` | Tertiary accents |
| `mdv.color.on.surface` | `#DFE2EB` | Primary text on dark |
| `mdv.color.on.surface.variant` | `#BAC9CC` | Secondary text |
| `mdv.color.outline` | `#849396` | Rare accessibility boundary |
| `mdv.color.outline.variant` | `#3B494C` | Ghost divider (low alpha only) |
| `mdv.color.error` | `#FFB4AB` | Error text/accent |
| `mdv.color.error.container` | `#93000A` | Error container |
| `mdv.color.on.error` | `#690005` | Text on error badges |

### 3.1.1 VPN state semantic tokens
| State | Foreground | Container | Indicator |
|---|---|---|---|
| Idle/Disconnected | `#BAC9CC` | `#1C2026` | `#3B494C` |
| Connecting | `#00DAF3` | `#262A31` | `#00DAF3` |
| Connected | `#00E5FF` | `#181C22` | `#00E5FF` |
| Error | `#FFB4AB` | `#93000A` (15% alpha for cards) | `#FFB4AB` |

### 3.1.2 Material mapping (Compose)
- `colorScheme.background` -> `mdv.color.background`
- `colorScheme.surface` -> `mdv.color.surface.default`
- `colorScheme.surfaceVariant` -> `mdv.color.surface.high`
- `colorScheme.primary` -> `mdv.color.primary`
- `colorScheme.primaryContainer` -> `mdv.color.primary.container`
- `colorScheme.secondary` -> `mdv.color.secondary`
- `colorScheme.error` -> `mdv.color.error`
- `colorScheme.onSurface` -> `mdv.color.on.surface`

### 3.1.3 API 21 performance fallback
- Disable dynamic color for deterministic look and reduced branching.
- Do not use runtime blur on API 21-27.
- Replace blur glass with alpha overlay:
  - `surface.highest` at 85-92% opacity.
  - One glow shadow max per focus component.

## 3.2 Typography Scale

Primary families:
- Headline/metrics: Space Grotesk
- Body/forms: Manrope

Fallback families for API 21 safety:
- Headline fallback: `sans-serif-medium`
- Body fallback: `sans-serif`

| Token | Font | Size | Line Height | Weight | Tracking |
|---|---|---:|---:|---:|---:|
| `mdv.type.display.lg` | Space Grotesk | 34sp | 40sp | 700 | 0.5sp |
| `mdv.type.headline.md` | Space Grotesk | 28sp | 34sp | 700 | 0.4sp |
| `mdv.type.title.lg` | Space Grotesk | 22sp | 28sp | 600 | 0.2sp |
| `mdv.type.title.md` | Space Grotesk | 18sp | 24sp | 600 | 0.15sp |
| `mdv.type.body.lg` | Manrope | 16sp | 24sp | 500 | 0.1sp |
| `mdv.type.body.md` | Manrope | 14sp | 20sp | 400 | 0.1sp |
| `mdv.type.body.sm` | Manrope | 12sp | 18sp | 400 | 0.15sp |
| `mdv.type.label.md` | Space Grotesk | 11sp | 16sp | 600 | 1.2sp |
| `mdv.type.label.sm` | Space Grotesk | 10sp | 14sp | 600 | 1.5sp |
| `mdv.type.mono.log` | monospace | 12sp | 18sp | 400 | 0sp |

## 3.3 Layout, Shape, Elevation Tokens

### 3.3.1 Spacing scale (dp)
- `mdv.space.1 = 4`
- `mdv.space.2 = 8`
- `mdv.space.3 = 12`
- `mdv.space.4 = 16`
- `mdv.space.5 = 20`
- `mdv.space.6 = 24`
- `mdv.space.7 = 32`

### 3.3.2 Corner radius (dp)
- `mdv.radius.sm = 8`
- `mdv.radius.md = 12`
- `mdv.radius.lg = 16`
- `mdv.radius.xl = 20`
- `mdv.radius.full = 999`

### 3.3.3 Elevation/glow
- `mdv.elevation.0 = 0dp`
- `mdv.elevation.1 = 2dp`
- `mdv.elevation.2 = 6dp`
- `mdv.elevation.3 = 12dp`
- `mdv.elevation.focus = 18dp` (single hero element only)
- Glow tint color: `#00DAF3` at 12-20% alpha.

### 3.3.4 Stroke rules
- No hard 1dp separators by default.
- If separation is required for accessibility, use:
  - color `#3B494C` at 15% alpha.

## 3.4 Motion Tokens
- `mdv.motion.fast = 160ms`
- `mdv.motion.normal = 280ms`
- `mdv.motion.slow = 420ms`
- `mdv.motion.pulse = 820ms` (connecting state)
- Easing:
  - standard: `FastOutSlowIn`
  - status pulse: `EaseInOutCubic`
- Reduced motion mode:
  - Disable pulse scaling.
  - Keep only color transition (`<=160ms`).

---

## 4) Responsive Grid and Adaptive Layout Rules

## 4.1 Breakpoints
- `Compact`: width < 600dp (phone portrait)
- `Medium`: 600-839dp (large phone landscape / small tablet)
- `Expanded`: >= 840dp (tablet/foldable expanded)

## 4.2 Grid spec
| Size class | Columns | Outer margin | Gutter | Max content width |
|---|---:|---:|---:|---:|
| Compact | 4 | 16dp | 12dp | 560dp |
| Medium | 8 | 24dp | 16dp | 840dp |
| Expanded | 12 | 32dp | 20dp | 1200dp |

## 4.3 Foldable behavior
- If hinge or folding feature splits available width:
  - Keep critical action column (status + connect control) on start pane.
  - Move telemetry/settings list to end pane.
- Keep bottom nav unchanged.

## 4.4 Home card reflow
- Compact: cards stack vertically.
- Medium: status + profile cards full width; telemetry cards in 2 columns.
- Expanded: hero block left 5 columns, telemetry right 7 columns.

---

## 5) Navigation Preservation Contract

Navigation routes must remain unchanged:
- `home`
- `profiles`
- `logs`
- `settings`
- `info`
- `profile_settings/{profileId}`

Bottom tabs must remain unchanged:
- Home / Profiles / Logs / Settings

Do not import Stitch navigation patterns that alter current route model.

---

## 6) Screen-by-Screen Blueprint

All screens below include:
- Functional Goal
- UI components
- Stitch adaptation
- State rules (Idle, Connecting, Connected, Error)
- View hierarchy and interaction contracts

## 6.1 App Shell and Bottom Navigation (`AppNavigation`)

### Functional Goal
Provide persistent 4-tab root navigation with stable visual identity and route-safe transitions.

### UI Components
- `MdvScaffoldRoot`
- `MdvBottomNavBar`
- `MdvBottomNavItemHome`
- `MdvBottomNavItemProfiles`
- `MdvBottomNavItemLogs`
- `MdvBottomNavItemSettings`

### Stitch Adaptation
- Background: `mdv.color.background` with 95% opacity container.
- Active item: `primary.container` 10% tint capsule + filled icon.
- Inactive item: `secondary` at 65% opacity.
- Label style: `mdv.type.label.sm`.

### State Instructions
- Idle: inactive tint baseline.
- Connecting: active tab icon glow alpha +10%.
- Connected: active tab icon uses `primary.container`.
- Error: if current page has error, show small 4dp `error` dot on relevant tab icon.

### Hierarchy
`Scaffold -> BottomBar(NavigationBar -> NavigationBarItem x4) -> NavHost`

---

## 6.2 Home (`HomeScreen`)

### Functional Goal
Primary control center for VPN connect/disconnect and real-time status visibility.

### UI Components
- `MdvHomeHeader` (logo/title/info action)
- `MdvVpnStateHeading`
- `MdvSelectedProfileLabel`
- `MdvConnectionNodeButton` (hero power control)
- `MdvConnectionTelemetryCard`
  - `MdvResolverProgress`
  - `MdvMtuRow`
  - `MdvSpeedRow`
  - `MdvSocksRow`
- `MdvProfileSelectorCard`
- `MdvErrorBannerCard`

### Stitch Adaptation
- Hero area uses tonal depth and glow ring without runtime blur on API 21.
- Node button:
  - Outer ring: `surface.bright` + `primary` alpha border
  - Inner core: gradient `#C3F5FF -> #00E5FF` at 135 degrees
- Status label uppercase style via `mdv.type.label.md`.
- Telemetry cards use `surface.low` and `surface.high` layering, no hard dividers.

### State Instructions
- Idle:
  - Status text: "Disconnected", color `on.surface.variant`
  - Node button neutral, no pulse.
  - Progress hidden.
- Connecting:
  - Status text: "Connecting...", color `primary.dim`
  - Node pulse active (`mdv.motion.pulse`)
  - Progress bar visible with resolver counters.
- Connected:
  - Status text: "Connected", color `primary.container`
  - Node steady glow.
  - Throughput + active resolver metrics emphasized.
- Error:
  - Status text: "Error", color `error`
  - Show `MdvErrorBannerCard` with message from `VpnManager.errorMessage`.
  - Keep connect action available.

### Hierarchy (Compose-first)
`ScaffoldBodyColumn`
-> `MdvHomeHeader`
-> `MdvVpnStateHeading`
-> `MdvSelectedProfileLabel`
-> `MdvConnectionNodeButton`
-> `MdvConnectionTelemetryCard`
-> `MdvProfileSelectorCard`
-> `MdvErrorBannerCard?`

### XML parity mapping
- `MdvHomeHeader` -> `ConstraintLayout` (`id: mdv_home_header`)
- `MdvConnectionNodeButton` -> `FrameLayout + MaterialButton` (`id: mdv_home_connect_node`)
- `MdvConnectionTelemetryCard` -> `MaterialCardView` (`id: mdv_home_telemetry_card`)

### Existing callback contract (must stay)
- Connect flow:
  - if no selected profile -> navigate to Profiles tab
  - else call `VpnService.prepare(...)` then `VpnManager.connect(context, profile)`
- Disconnect flow:
  - call `VpnManager.disconnect(context)`

---

## 6.3 Profiles (`ProfilesScreen`)

### Functional Goal
Manage profile lifecycle: create/import/edit/select/delete and open profile-specific settings.

### UI Components
- `MdvProfilesTopBar`
- `MdvProfilesEmptyState`
- `MdvProfilesList`
- `MdvProfileCard`
  - `MdvProfileSelectIndicator`
  - `MdvProfileActions` (Edit/Settings/Delete)
- `MdvProfileEditorDialog`
  - `MdvFieldProfileName`
  - `MdvFieldDomain`
  - `MdvFieldEncryptionKey`
  - `MdvFieldEncryptionMethod`
  - `MdvFieldResolvers`
  - `MdvImportTomlAction`
  - `MdvImportResolversAction`

### Stitch Adaptation
- Top bar with cyan headline and compact icon actions.
- Profile card layering:
  - Unselected: `surface.low`
  - Selected: `primary.container` at 14% alpha + check indicator
- Use chip-like small labels for domain/protocol metadata.
- Dialog uses `surface.highest`, rounded `mdv.radius.xl`, no border.

### State Instructions
- Idle:
  - Neutral list/empty state.
  - "Create Profile" primary action visible.
- Connecting:
  - Selected profile card receives active indicator pulse (subtle alpha animation).
- Connected:
  - Selected profile card uses persistent `primary.container` highlight.
- Error:
  - Show local inline error style in dialog/fields and snackbar for import failures.

### Hierarchy
`Scaffold`
-> `TopAppBar`
-> `Body`
-> `LazyColumn(ProfileCard...) OR EmptyState`
-> `ProfileEditorDialog?`

### XML parity mapping
- `MdvProfileCard` -> `MaterialCardView` + nested `LinearLayout`
- Action icons IDs:
  - `mdv_profile_action_edit`
  - `mdv_profile_action_settings`
  - `mdv_profile_action_delete`

### Existing callback contract (must stay)
- Select: `viewModel.selectProfile(profile.id)`
- Add: `viewModel.addProfile(profile)`
- Update: `viewModel.updateProfile(profile)`
- Delete: `viewModel.deleteProfile(profile)`
- Open settings: navigate `profile_settings/{id}`

---

## 6.4 Logs (`LogsScreen`)

### Functional Goal
Provide real-time log monitoring with source filter, auto-scroll control, share, and clear.

### UI Components
- `MdvLogsTopBar`
- `MdvLogFilterChipRow`
- `MdvLogStatsStrip` (optional future enhancement from Stitch style)
- `MdvLogStreamList`
- `MdvLogLineItem`

### Stitch Adaptation
- Terminal-like panel:
  - Background: `surface.lowest`
  - Parent container: `surface.low`
- Log typography: `mdv.type.mono.log`.
- Severity colors:
  - INFO -> `#81C784` (legacy mapping preserved)
  - WARN -> `#FFB74D`
  - ERROR -> `mdv.color.error`
- Chips styled as low-contrast instrument toggles.

### State Instructions
- Idle:
  - Static log list, auto-scroll off/on as selected.
- Connecting:
  - Optional connecting marker in status strip.
- Connected:
  - Show "live" indicator chip in `primary.container`.
- Error:
  - Highlight error entries and keep clear/share actions visible.

### Hierarchy
`Scaffold`
-> `TopAppBar(actions: share, auto, clear)`
-> `Column`
-> `FilterChipRow`
-> `LazyColumn(LogLine...)`

### XML parity mapping
- `RecyclerView` for log stream (`id: mdv_logs_recycler`)
- Chip group equivalent using `MaterialButtonToggleGroup` or `ChipGroup`

### Existing callback contract (must stay)
- Data source: `VpnManager.logEntries`
- Clear: `VpnManager.clearLogs()`
- Share: share current filtered text payload

---

## 6.5 Global Settings (`GlobalSettingsScreen`)

### Functional Goal
Configure app-wide connection mode, split tunneling, and internet sharing.

### UI Components
- `MdvGlobalSettingsTopBar`
- `MdvConnectionModeDropdown`
- `MdvSplitTunnelingToggle`
- `MdvSplitTunnelPickerCard`
- `MdvInternetSharingSection`
  - `MdvInternetSharingToggle`
  - `MdvPortFieldSocks`
  - `MdvPortFieldHttp`
  - `MdvSharingCredentialsFields`
- `MdvGlobalSettingsSaveAction`
- `MdvSplitTunnelAppPickerDialog`

### Stitch Adaptation
- Section cards with tonal nesting:
  - outer `surface.low`
  - interactive field block `surface.highest`
- Large save CTA uses cyan gradient capsule.
- Toggle rows use label-sm upper metadata + body text.

### State Instructions
- Idle:
  - Standard form editable.
- Connecting:
  - Save remains enabled, but show advisory note if network mode changes can affect current tunnel.
- Connected:
  - Show active-state chip near connection mode and sharing section.
- Error:
  - Field-level validation (ports) + snackbar; error colors only for invalid inputs.

### Hierarchy
`Scaffold`
-> `TopBar`
-> `LazyColumn`
-> `SectionCard(Connection Mode + Split Tunnel)`
-> `SectionCard(Internet Sharing)`
-> `SaveButton`
-> `AppPickerDialog?`

### XML parity mapping
- Use `NestedScrollView + LinearLayout + MaterialCardView`
- App picker in `BottomSheetDialogFragment`

### Existing callback contract (must stay)
- Observe/save via `GlobalSettingsViewModel` and `GlobalSettingsStore`.
- Validation must keep current port constraints (1025..65535).

---

## 6.6 Profile Settings (`SettingsScreen`)

### Functional Goal
Edit advanced per-profile runtime settings with grouped sections and import/export tools.

### UI Components
- `MdvProfileSettingsTopBar`
- `MdvProfileHeaderBlock`
- `MdvProfileToolActions` (import/export/resolver/mtu destination)
- `MdvSettingsSectionAccordion`
- `MdvSettingFieldCard` (TEXT/BOOL/OPTION variants)
- `MdvProfileSettingsSaveBar`

### Stitch Adaptation
- Section headers as instrument labels (`mdv.type.label.sm`, uppercase).
- Each accordion section uses surface step:
  - Header: `surface.high`
  - Expanded body: `surface.low`
- Field controls use borderless fill by default; focused state adds 1dp outline at 30% alpha.

### State Instructions
- Idle:
  - All fields editable, default sections expanded: Identity + Proxy.
- Connecting:
  - Keep editing allowed; show non-blocking note "applies on next reconnect" for core runtime fields.
- Connected:
  - Same as connecting note for high-impact fields.
- Error:
  - Show import parsing errors and field validation errors inline + snackbar.

### Hierarchy
`Scaffold`
-> `TopBar(Save icon)`
-> `Body`
-> `NoProfileEmptyState OR ProfileContent`
-> `Sectioned LazyColumn`
-> `Bottom Save button`

### XML parity mapping
- Collapsible sections:
  - `MaterialCardView` + expandable `LinearLayout`
- Field IDs:
  - `mdv_ps_field_<CONFIG_KEY>` (e.g., `mdv_ps_field_LISTEN_PORT`)

### Existing callback contract (must stay)
- Save: `viewModel.saveSettings(selected, fieldsState.toMap())`
- Import/Export resolver/TOML logic remains as implemented.

---

## 6.7 Info (`InfoScreen`)

### Functional Goal
Show project identity, links, and version/build metadata.

### UI Components
- `MdvInfoTopBar`
- `MdvInfoHeroCard`
- `MdvProjectLinksCard`
- `MdvVersionManifestCard`
- `MdvInfoLinkRow`

### Stitch Adaptation
- Hero card with deep gradient + subtle texture.
- Project link rows styled as instrument list entries with open icon.
- Version rows use muted chips ("LATEST", "PRODUCTION" style semantics).

### State Instructions
- Idle:
  - Neutral informational display.
- Connecting:
  - Optional small status chip in hero area.
- Connected:
  - Connected chip in hero area can use `primary.container`.
- Error:
  - If global error exists, show compact error ribbon below hero.

### Hierarchy
`Scaffold`
-> `TopBar`
-> `LazyColumn`
-> `HeroCard`
-> `LinksCard`
-> `VersionCard`

### Existing callback contract (must stay)
- Link handling via `LocalUriHandler`.
- Version values from `BuildConfig.VERSION_NAME` and string resources.

---

## 7) Technical Implementation Logic for Future AI Coding

## 7.1 Naming Convention (mandatory)

### Compose
- Screen roots: `<Feature>ScreenRoot` (e.g., `HomeScreenRoot`)
- Reusable widgets: prefix `Mdv` (e.g., `MdvConnectionNodeButton`)
- State holders: `<Feature>UiState`
- Events: `<Feature>Event`

### XML IDs
- Prefix all view IDs with `mdv_`
- Pattern:
  - Screen root: `mdv_<screen>_root`
  - Components: `mdv_<screen>_<component>`
  - Example: `mdv_home_status_heading`, `mdv_logs_filter_chip_all`

## 7.2 View hierarchy templates

### Home template
- `Scaffold`
  - `Column`
    - Header
    - Status
    - Hero node
    - Telemetry cards
    - Profile card
    - Error banner

### List-heavy screens template
- `Scaffold`
  - top app bar
  - filter/action row
  - `LazyColumn` / `RecyclerView`
  - optional dialog

## 7.3 State source contract (do not alter source ownership)

| UI State/Data | Source |
|---|---|
| VPN lifecycle state | `VpnManager.state` |
| Upload/download speed | `VpnManager.uploadSpeedBps`, `VpnManager.downloadSpeedBps` |
| Scan progress/status | `VpnManager.scanStatus` |
| Error message | `VpnManager.errorMessage` |
| Logs | `VpnManager.logEntries` |
| Selected profile | `HomeViewModel.selectedProfile` |
| Profile list and CRUD | `ProfilesViewModel` |
| Global settings | `GlobalSettingsViewModel` + `GlobalSettingsStore` |
| Profile advanced settings | `SettingsViewModel` |

## 7.4 UI-to-core interaction contract

No direct UI call to Go internals. UI only talks via existing Android bridge:
- Connect: `VpnManager.connect(context, profile)`
- Disconnect: `VpnManager.disconnect(context)`
- Clear logs: `VpnManager.clearLogs()`
- Observe state/log/metrics flows from `VpnManager`

## 7.5 Compose to XML mapping quick reference

| Compose concept | XML equivalent |
|---|---|
| `Scaffold + TopAppBar` | `CoordinatorLayout + MaterialToolbar` |
| `LazyColumn` | `RecyclerView` |
| `Card` | `MaterialCardView` |
| `FilterChip` | `Chip` (`ChipGroup`) |
| `FilledIconButton` | `MaterialButton` icon-only style |
| `Dialog` | `DialogFragment` / `MaterialAlertDialogBuilder` |

---

## 8) Adaptive and Performance Guidelines (API 21+)

## 8.1 Rendering budget rules
- Max one gradient hero element per screen.
- Avoid nested semi-transparent backgrounds beyond 2 layers.
- Replace blur with flat alpha surfaces on API 21-27.
- No large bitmap backgrounds for primary content surfaces.

## 8.2 Overdraw reduction
- Prefer one screen-level background only.
- Cards should not repaint opaque parent backgrounds unless needed.
- Disable decorative layers in scroll-heavy lists.

## 8.3 Compose-specific optimization
- Use `remember` for derived display text from stable inputs.
- Use `derivedStateOf` for filter results where needed.
- Keep large lists keyed (`key = profile.id`, etc.).
- Avoid expensive parsing in composable body; move to ViewModel/helpers when possible.

## 8.4 XML-specific optimization
- Use `RecyclerView` with view holder reuse.
- Avoid deep nested weight-based linear layouts.
- Use vector drawables over raster where possible.

## 8.5 Accessibility minimums
- Touch targets >= 48dp.
- Body text >= 12sp; primary text 14sp+.
- Contrast target:
  - normal text 4.5:1
  - large text 3:1
- All icon-only interactive elements require content descriptions.

---

## 9) Acceptance Criteria and Test Scenarios

## 9.1 Visual/system acceptance
- All color/style values resolve from `mdv.*` tokens.
- Typography and spacing follow this schema without ad-hoc literals except documented exceptions.
- Stitch look is visually recognizable while keeping API 21 smoothness.

## 9.2 Behavior acceptance
- Navigation routes and tab structure unchanged.
- Home connect/disconnect flow unchanged in logic.
- Profiles CRUD and settings import/export flows remain functionally equivalent.

## 9.3 State acceptance
- Every screen documents and renders Idle, Connecting, Connected, Error indicators or variants.
- Error conditions are visible but non-blocking where possible.

## 9.4 Performance acceptance
- No runtime blur required for API 21.
- Scroll in Profiles/Logs/Settings remains fluid under realistic datasets.
- No unnecessary full-screen redraw patterns from animated effects.

## 9.5 Regression checklist
- Home with no selected profile: tapping connect navigates to Profiles.
- Connecting state shows progress and pulse only where specified.
- Connected state shows throughput/resolver metrics.
- Error message card appears when `VpnManager.errorMessage` is non-null.
- Global settings port validation remains 1025..65535.
- Logs filters (All/Core/Android) still function.

---

## 10) Recommended Delivery Sequence (Implementation Order)

1. Tokenize theme (`mdv.*` colors/type/spacing/shape) and disable dynamic-color drift for this theme variant.
2. Refactor shared shell components (top bars, cards, chips, bottom nav item style).
3. Implement Home redesign (hero node + telemetry) with API21 fallbacks first.
4. Apply Profiles and Logs redesign.
5. Apply Global Settings and Profile Settings redesign.
6. Apply Info redesign.
7. Run performance and accessibility pass.
8. Freeze screenshot baseline for Compact/Medium/Expanded layouts.

---

## 11) Explicit Assumptions

- This roadmap intentionally preserves existing feature logic and callbacks.
- Where Stitch specs show heavy visual effects, this roadmap uses adaptive lightweight alternatives for API 21.
- Compose is primary implementation target; XML mapping exists for AI/cross-team parity.
- If any future design request conflicts with current navigation behavior, navigation preservation takes priority.

