# MasterDnsVPN Android UI/UX Design Specification

## 1) Document Purpose
This document translates the current Android UI layer into a complete design blueprint for a UI Designer. It covers all user-facing screens, navigation logic, and component behavior currently implemented in the app.

- **Platform:** Android (Jetpack Compose + Material 3)
- **App module path:** `android/app`
- **Primary audience:** UI Designer, Product Designer, Product Architect
- **Implementation parity:** This spec mirrors current behavior in code.
- **Out of scope:** Go-based networking/core engine internals.

---

## 2) Product Information Architecture & Navigation

## 2.1 App Entry
- **Launcher Activity:** `MainActivity`
- **Host UI:** `AppNavigation()` composable inside `MainActivity`
- **Navigation model:** Single-activity Compose navigation

## 2.2 Primary Bottom Navigation Tabs
The app uses a persistent bottom navigation bar with 4 root tabs:

1. **Home** (`route: home`)
2. **Profiles** (`route: profiles`)
3. **Logs** (`route: logs`)
4. **Settings** (`route: settings`)

### Tab Icon Mapping
- Home → `Icons.Filled.Home`
- Profiles → `Icons.Filled.Person`
- Logs → `Icons.Filled.Terminal`
- Settings → `Icons.Filled.Settings`

## 2.3 Secondary Routes (Not in Bottom Bar)
- **Info** (`route: info`) — opened from Home header
- **Profile Settings** (`route: profile_settings/{profileId}`) — opened from Profiles list item

## 2.4 Navigation Behavior Rules
- App start destination is **Home**.
- Bottom bar navigation is **root-switching behavior** (single top, pops to graph start destination).
- Re-tapping active bottom tab does nothing.
- Home can navigate to Profiles and Info.
- Profiles can open profile-specific settings route.
- Info and Profile Settings use `popBackStack()` to return.
- Profiles and Logs include a top app bar back arrow that navigates to Home.

---

## 3) Global UX Foundations

## 3.1 Visual System
- Uses Material 3 theme with dynamic color support on Android 12+.
- Supports light/dark mode through `MasterDnsVPNTheme`.
- Current palette uses status colors:
  - **Connected:** green
  - **Connecting/Disconnecting:** amber
  - **Error/Disconnected alerts:** red
- Layout direction is forced **LTR**.

## 3.2 Common Layout Patterns
- Screen container pattern: `Scaffold` + `TopAppBar` (on most non-home screens).
- Content frequently structured in cards with rounded corners.
- Vertical layouts often use 12–16dp spacing rhythm.
- Important actions appear as icon buttons in top app bars.

## 3.3 Common Interaction Feedback
- **Snackbars** for save/import/export confirmations and validation errors.
- **Conditional rendering** for empty states and toggled sub-sections.
- **Dialogs** used for profile editing and split-tunnel app picker.
- **File picker/document picker** used for TOML/resolver import and export.

## 3.4 Accessibility & Content Notes for Design
Designer should include in deliverables:
- Sufficient contrast for status colors in both light and dark themes.
- Large tap targets for icon-only actions (minimum 48dp touch area).
- Clear text hierarchy for technical settings (label + helper/supporting copy).
- Clear visual states for selected, expanded, error, and disabled conditions.

---

## 4) Screen-by-Screen Specification

## 4.1 MainActivity (Host Shell)
### Purpose
App shell and theme host. No standalone content design; it renders navigation.

### Designer Deliverables
- Not a visual page itself.
- Ensure global system decisions (safe-area usage, bottom nav behavior, status bar treatment) are reflected in app-wide design system.

---

## 4.2 AppNavigation (Global Navigation Container)
### Purpose
Defines tab structure, bottom nav bar, and route graph.

### Required Design Elements
- Persistent bottom nav with labels and icons.
- Active/inactive tab states.
- Consistent bar style across all routes, including secondary routes.

### States
- Selected tab state based on current route hierarchy.
- Must account for non-bottom routes (`info`, `profile_settings/{id}`) where bottom bar still exists.

---

## 4.3 Home Screen (`HomeScreen`)
### Purpose
Main connection control center: connect/disconnect VPN, monitor connection status, quick access to profile and info.

### Layout Anatomy (Top → Bottom)
1. **Header row**
   - Left cluster: app logo + title text (`MDV-HN`) clickable to open Info.
   - Right icon button: Info.
2. **Primary status text**
   - Displays one of: Disconnected, Connecting..., Connected, Disconnecting..., Error.
3. **Selected profile text**
   - Selected profile name or “No profile selected”.
4. **Central power control area**
   - Large circular power button with animated pulse while connecting/disconnecting.
   - Outer glow ring reflecting current connection status color.
5. **Connection Status card**
   - Detailed status summary, resolver scan info, valid/rejected counts, progress bar, synced MTU, active resolver count, up/down speeds, SOCKS5 endpoint and optional auth credentials.
6. **Profile selector card**
   - Row card linking to Profiles tab.
7. **Error card (conditional)**
   - Red-toned message card shown when error message exists.

### Component Behavior
- **Power button logic**
  - Connected → disconnect.
  - Connecting/Disconnecting → disconnect/cancel.
  - Disconnected/Error:
    - If no selected profile → navigate to Profiles.
    - Else request Android VPN permission (if needed), then connect.
- **Progress area** appears when scan is active/connecting.
- **Resolver status lines** appear only when corresponding data exists.

### Required UI States
- Disconnected idle.
- Connecting (amber + pulse + scan progress).
- Connected (green + active status text).
- Disconnecting (amber + transition messaging).
- Error (red status + error message card).
- No profile selected variant.

### Design Requirements
- Provide motion specs for pulse and status color transitions.
- Include progress bar style for scan progress.
- Define compact card styles for technical telemetry rows.
- Include empty state copy styling for “No profile selected”.
- Include error card style/token pair for destructive feedback.

---

## 4.4 Profiles Screen (`ProfilesScreen`)
### Purpose
Manage connection profiles: create, import, edit, select, open settings, delete.

### Layout Anatomy
1. **Top app bar**
   - Title: Profiles
   - Navigation back arrow (to Home)
   - Add action button
2. **Body**
   - If no profiles: empty state with icon + “No profiles yet” + “Create Profile” button.
   - Else: scrollable list of profile cards.
3. **Modal dialog (conditional): Profile Editor**
   - Used for create/edit and import-assisted creation.

### Profile List Item (`ProfileCard`)
- Card tap selects profile.
- Selected profile shows check-circle indicator and selected-card background.
- Shows profile name and domain summary.
- Right-side action icons:
  - Edit
  - Settings (open profile settings route)
  - Delete

### Profile Editor Dialog (`ProfileEditorDialog`)
#### Fields
- Profile Name
- Domain
- Encryption Key (with show/hide toggle)
- Encryption Method dropdown
- Resolvers multi-line input

#### Create-only Import Actions
- Import TOML
- Import Resolvers

#### Large resolver optimization state
- If resolvers text is very large, show a compact advisory card with “Edit Resolvers” button before rendering full text box.

#### Buttons
- Save (enabled only when name and domain are not blank)
- Cancel

### Required UI States
- Empty list state.
- Populated list state.
- Selected profile visual state.
- Dialog create mode.
- Dialog edit mode.
- Resolver-large optimization state.
- Import success/failure snackbar states.

### Design Requirements
- Define destructive delete affordance style.
- Design clear selected vs unselected profile card differentiation.
- Provide text-field error/required state treatment for Save eligibility.
- Provide password visibility toggle icons/states.
- Include file-import action hierarchy (secondary actions inside dialog).

---

## 4.5 Profile Settings Screen (`SettingsScreen`)
### Purpose
Advanced per-profile client configuration editor with import/export and grouped technical settings.

### Entry Behavior
- Opened from a specific profile via `profile_settings/{profileId}`.
- Fallback supports selected profile when route arg absent.

### Layout Anatomy
1. **Top app bar**
   - Title: Profile Settings
   - Optional back button
   - Save icon action
2. **If no selected profile**
   - Centered informative empty state (“No selected profile”).
3. **If selected profile exists**
   - Profile label (“Editing profile: ...”)
   - Action button group:
     - Import TOML
     - Export TOML
     - Import client_resolvers.txt
     - Pick MTU export destination
   - Sectioned accordion cards by category.
   - Bottom full-width Save Settings button.

### Settings Section Categories
- Identity
- Proxy
- DNS
- Resolver
- Compression
- MTU
- Runtime
- ARQ
- Logging

### Field Rendering Types
- **TEXT:** Outlined text field + helper text
- **BOOL:** Label/helper + switch
- **OPTION:** Dropdown/select field

### Conditional Logic
- SOCKS5 user/pass fields only shown when SOCKS5_AUTH is true.
- DNS warning card appears if LOCAL_DNS_ENABLED is true and LOCAL_DNS_PORT <= 1024.
- Sections are collapsible (Show/Hide).

### Required UI States
- No selected profile state.
- Section collapsed/expanded states.
- Boolean switch states.
- Dropdown closed/opened/selected states.
- Validation/warning card state (DNS privileged port warning).
- Save confirmation snackbar state.

### Design Requirements
- Produce scalable form templates for large technical configuration sets.
- Provide clear grouping hierarchy with section headers.
- Ensure helper text remains readable and non-cluttered.
- Provide sticky/floating affordance concept for save action (top icon + bottom button parity).
- Design warning container style for cautionary but non-blocking notices.

---

## 4.6 Global Settings Screen (`GlobalSettingsScreen`) [Bottom Tab: Settings]
### Purpose
Configure app-level behavior (connection mode, split tunneling, internet sharing) independent of a specific profile.

### Layout Anatomy
1. **Top app bar**
   - Title: Settings
   - Save icon action
2. **Main settings cards**
   - Connection mode dropdown (VPN / PROXY)
   - Split tunneling switch + split app selector entry card
   - Sharing Internet block with enable switch and sub-fields
3. **Bottom full-width Save Global Settings button**
4. **Split app picker dialog (conditional)**

### Feature Blocks
#### A) Connection Mode
- Read-only dropdown with two options: VPN, PROXY.

#### B) Split Tunneling
- Master switch enables feature.
- When enabled, shows card to open app picker dialog.
- Card displays selected-app count.

#### C) Split App Picker Dialog
- Tabs via chips: **Selected** and **Available**
- Search field tied to active tab.
- Bulk actions:
  - Select Visible
  - Select None
- Scroll list of app rows with icon, label, package name, checkbox.
- Footer actions: Cancel / Apply.

#### D) Sharing Internet
- Master switch enables section.
- Displays local IP when available.
- Port fields:
  - SOCKS5 Port
  - HTTP Port
- Auth fields:
  - Username
  - Password
- Validation:
  - Required field errors.
  - Ports <= 1024 treated as invalid in UI (root-required warning).

### Required UI States
- Split tunneling OFF/ON states.
- App picker opened in Available tab default.
- Selected/available empty search-result states.
- Internet sharing OFF/ON states.
- Port validation error states.
- Save success/error snackbar feedback.

### Design Requirements
- Dialog should support high-density app lists without visual fatigue.
- Provide clear chip/tab selected states.
- Include inline form validation styles for ports.
- Ensure password field treatment follows secure-entry expectations.
- Define “count badge in text” style for split-app summary.

---

## 4.7 Logs Screen (`LogsScreen`)
### Purpose
Real-time technical logs viewer with filtering, auto-scroll control, sharing, and clear action.

### Layout Anatomy
1. **Top app bar**
   - Title: Logs
   - Back arrow (to Home)
   - Share action
   - Auto mode toggle button + ON/OFF label
   - Clear logs action
2. **Filter chip row**
   - All
   - Core
   - Android
3. **Scrollable log list**
   - Monospace text rows
   - Line color based on severity tags (`[ERROR]`, `[WARN]`, `[INFO]`)

### Behavior
- New logs auto-scroll when auto mode enabled.
- When auto mode disabled, viewport lock is preserved as logs update.
- Share action exports currently filtered logs via Android share intent.
- Clear action wipes log list and scan-related aggregated counters.

### Required UI States
- Empty log view.
- Populated log view.
- Auto ON vs Auto OFF mode.
- Filter-selected states.
- Severity color treatment.

### Design Requirements
- Design chips for quick source filtering.
- Provide readable monospace typography and spacing for dense logs.
- Ensure destructive clear action is recognizable.
- Include empty-state guidance when no logs exist.

---

## 4.8 Info Screen (`InfoScreen`)
### Purpose
Product/about page with project links and version/build info.

### Layout Anatomy
1. **Top app bar**
   - Title: Info
   - Back arrow
2. **Hero card**
   - Gradient background
   - App logo
   - App name + subtitle
   - Disabled “Build Information” assist chip
3. **Project Links card**
   - Main GitHub
   - Main Telegram
   - Android Client GitHub
4. **Version Info card**
   - App Version
   - Upstream Engine version

### Behavior
- Link rows are tappable and open external URLs.
- Link row includes open-in-new icon.

### Required UI States
- Default content state (no alternate empty states currently).
- Link pressed/hover (material ripple) state.

### Design Requirements
- Provide polished informational aesthetic distinct from utility screens.
- Include gradient hero treatment specifications.
- Define link row layout with long-URL truncation behavior.

---

## 5) Tab & User Flow Logic

## 5.1 Primary User Flows

### Flow A: First-time usage (no profiles)
1. User lands on Home.
2. Power press detects no selected profile.
3. User is redirected to Profiles.
4. User creates profile (manual or import).
5. User selects profile.
6. Returns to Home and connects.

### Flow B: Profile lifecycle
1. Open Profiles tab.
2. Add profile or edit existing.
3. Optionally import TOML/resolvers.
4. Save profile.
5. Select profile card.
6. Optionally open per-profile settings for advanced edits.

### Flow C: Connect/disconnect
1. User taps power on Home.
2. If permission required, Android VPN permission prompt appears.
3. Connecting state shows pulse + scan progress.
4. Connected state shows green status and telemetry.
5. User taps again to disconnect.

### Flow D: Per-profile advanced config
1. Open Profiles tab.
2. Tap settings icon on profile card.
3. Import/export TOML or resolvers, edit grouped fields.
4. Save settings (top action or bottom button).

### Flow E: Global behavior settings
1. Open Settings bottom tab.
2. Adjust connection mode, split tunneling, sharing internet.
3. For split tunneling, pick apps in dialog and apply.
4. Save global settings.

### Flow F: Diagnostics
1. Open Logs tab.
2. Filter by All/Core/Android.
3. Toggle auto-scroll mode.
4. Share filtered logs or clear logs.

### Flow G: Product info
1. From Home header or info icon, open Info.
2. Review project links and version information.

---

## 6) Feature-Level Component Requirements

## 6.1 Connection Control Module
- Large circular primary CTA with status-dependent color.
- Outer glow + pulse animation states.
- Must visually distinguish connect vs disconnect intent.

## 6.2 Connection Telemetry Module
- Card with dynamic rows.
- Includes scan details, MTU sync, active resolvers, speeds, proxy endpoint.
- Progress bar appears only while scan/connecting state is active.

## 6.3 Profile Management Module
- Empty-state onboarding + create CTA.
- Reusable profile cards with selected indicator and action cluster.
- Modal editor with import actions and mixed field types.

## 6.4 Advanced Configuration Form Module
- Section accordion pattern.
- Reusable field cards (text/switch/dropdown).
- Conditional field rendering and inline warning cards.

## 6.5 Split-Tunnel App Selection Module
- Dual-list (selected/available) with search per tab.
- Batch selection actions.
- App rows with icon + metadata + checkbox.

## 6.6 Logs Viewer Module
- Monospace terminal-like list.
- Severity color parsing.
- Filter chips + auto-scroll lock logic.

## 6.7 Informational About Module
- Brand hero card.
- External link list rows.
- Build/version facts presentation.

---

## 7) State & Asset Checklist for Designer

## 7.1 Required State Designs (All screens)
- Default
- Empty
- Loading/transition (where applicable)
- Success feedback
- Warning
- Error
- Destructive action affordance
- Selected/unselected toggles
- Enabled/disabled controls

## 7.2 Required Icons/Visual Assets
- Bottom nav icons: Home, Person, Terminal, Settings
- Action icons: Add, Edit, Delete, Back, Save, Upload, Download, Share, Info, Power, Auto, OpenInNew
- Status icons: CheckCircle, PersonAdd
- App logo asset usage in Home + Info

## 7.3 Motion/Transition Requirements
- Home power-button pulse animation during connecting/disconnecting
- Smooth status color transitions on Home
- Expand/collapse affordances for settings sections
- Dialog open/close transitions for profile editor and app picker

---

## 8) Appendix A — Profile Settings Field Taxonomy
Designer should prepare scalable, reusable form designs covering the following groups and field densities:

- **Identity:** domain list, encryption method/key
- **Proxy:** protocol, bind IP/port, SOCKS auth credentials
- **DNS:** local DNS toggles, cache, timeout, persistence
- **Resolver:** balancing strategy, duplication, failover/recheck/disable behavior
- **Compression:** upload/download compression + thresholds
- **MTU:** ranges, retry/timeout/parallelism, export formats/log text templates
- **Runtime:** workers, channel sizes, polling, session retry and ping intervals
- **ARQ:** windowing, retransmission, packet TTL, NACK timing, inactivity
- **Logging:** log level

Note: this screen is intentionally technical and high-density; design should emphasize readability, grouping, and error prevention.

---

## 9) Appendix B — System-Level UI Surfaces to Account For
These are outside custom Compose screens but part of end-to-end UX:

- Android VPN permission system dialog (triggered on first connect when required)
- Android document picker (import TOML/resolvers, export TOML, pick MTU export destination)
- Android share sheet (logs sharing)

Designer should include handoff notes for these transitions so visual flows are complete.

---

## 10) Optional Architect Recommendations (Non-Blocking)
These are optional UX improvements and not required for current parity:

1. Add explicit confirmation dialog before profile deletion and log clearing.
2. Add skeleton/loading placeholders for app list loading in split-tunnel picker.
3. Add inline validation for profile editor (domain format, encryption key requirements).
4. Add sticky section index/jump-to for Profile Settings due to high field count.
5. Improve Home telemetry hierarchy with compact metric chips to reduce text density.
6. Add empty-state illustration and guidance in Logs when no entries exist.

