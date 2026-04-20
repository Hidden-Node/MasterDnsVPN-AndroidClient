# Stitch UI Implementation Tickets (Execution Backlog)

Last updated: April 20, 2026  
Source roadmap: `docs/stitch-ui-redesign-roadmap.md`

This backlog converts the redesign roadmap into implementation-ready tickets with clear scope, dependencies, and acceptance criteria.

---

## Phase 0 - Foundation and Safety Rails

### TKT-00.1 - Add Stitch token set to theme layer
- **Goal:** Introduce `mdv.*` design tokens in Kotlin theme objects (color/type/shape/spacing/elevation).
- **Scope:**
  - Add token definitions in `android/app/src/main/java/com/masterdns/vpn/ui/theme/`.
  - Freeze hex values from roadmap.
  - Add typography mapping (Space Grotesk + Manrope with fallback).
- **Dependencies:** None.
- **Acceptance criteria:**
  - All tokens exist with exact values from roadmap section 3.
  - App compiles with no missing token references.

### TKT-00.2 - API 21 performance mode switches
- **Goal:** Ensure heavy visual effects are automatically downgraded for API 21-27.
- **Scope:**
  - Disable dynamic color for Stitch theme variant.
  - Introduce helper functions: `supportsRuntimeBlur`, `supportsEnhancedGlow`.
- **Dependencies:** TKT-00.1.
- **Acceptance criteria:**
  - API 21-27 renders without blur-based effects.
  - API 28+ can use enhanced styling where allowed.

### TKT-00.3 - Shared component naming and file structure
- **Goal:** Standardize reusable UI components for AI and human implementation.
- **Scope:**
  - Create folder strategy:
    - `ui/components/mdv/`
    - `ui/components/mdv/cards/`
    - `ui/components/mdv/controls/`
  - Add component stubs using naming convention (`Mdv*`).
- **Dependencies:** TKT-00.1.
- **Acceptance criteria:**
  - Component names follow roadmap section 7.1.
  - No duplicate naming patterns.

---

## Phase 1 - App Shell and Shared Surfaces

### TKT-01.1 - Bottom navigation visual refactor (behavior preserved)
- **Goal:** Apply Stitch style to bottom bar without route changes.
- **Scope:**
  - Update visuals in `AppNavigation.kt` only.
  - Keep tab structure and route behavior unchanged.
- **Dependencies:** TKT-00.1, TKT-00.2.
- **Acceptance criteria:**
  - Routes remain: Home/Profiles/Logs/Settings.
  - Active/inactive styles match token spec.

### TKT-01.2 - Shared top app bar variants
- **Goal:** Create consistent top bars for root and secondary screens.
- **Scope:**
  - Build reusable top bar composables (title-only, with back, with actions).
  - Apply to Logs, Profiles, Settings, Info.
- **Dependencies:** TKT-00.3.
- **Acceptance criteria:**
  - Top bars use tokenized typography and color.
  - Icon-only buttons have content descriptions.

### TKT-01.3 - Core card and chip system
- **Goal:** Replace ad-hoc card/chip styling with shared primitives.
- **Scope:**
  - Implement `MdvCardLow`, `MdvCardHigh`, `MdvStatusChip`, `MdvFilterChip`.
  - Migrate at least one screen section as proof.
- **Dependencies:** TKT-00.1, TKT-00.3.
- **Acceptance criteria:**
  - New primitives used in multiple screens.
  - No raw one-off colors in migrated sections.

---

## Phase 2 - Home Screen (Primary Journey)

### TKT-02.1 - Home layout and hero node redesign
- **Goal:** Rebuild Home hierarchy with Stitch adaptive layout.
- **Scope:**
  - Implement `MdvHomeHeader`, `MdvVpnStateHeading`, `MdvConnectionNodeButton`.
  - Maintain current connect/disconnect behavior.
- **Dependencies:** Phase 1 tickets.
- **Acceptance criteria:**
  - Connect button behavior exactly matches existing flow.
  - Idle/Connecting/Connected/Error visuals are distinct and token-based.

### TKT-02.2 - Telemetry card set refactor
- **Goal:** Split monolithic telemetry card into reusable sections.
- **Scope:**
  - `MdvResolverProgress`, `MdvSpeedRow`, `MdvMtuRow`, `MdvSocksRow`.
  - Keep all current metrics and scan data visibility.
- **Dependencies:** TKT-02.1.
- **Acceptance criteria:**
  - All current data points still displayed when available.
  - Scan progress matches current values from `VpnManager.scanStatus`.

### TKT-02.3 - Home responsive reflow rules
- **Goal:** Apply Compact/Medium/Expanded layout behavior.
- **Scope:**
  - Use width classes with adaptive column decisions.
  - Keep content order consistent.
- **Dependencies:** TKT-02.1, TKT-02.2.
- **Acceptance criteria:**
  - Compact stacks vertically.
  - Medium/Expanded follow roadmap reflow table.

---

## Phase 3 - Profiles and Logs

### TKT-03.1 - Profiles list/card restyle
- **Goal:** Upgrade profile cards/actions to Stitch visual system.
- **Scope:**
  - Restyle `ProfileCard` with selected-state emphasis and action affordances.
  - Preserve CRUD callbacks.
- **Dependencies:** Phase 1 complete.
- **Acceptance criteria:**
  - Select/edit/settings/delete logic unchanged.
  - Selected profile state visually obvious.

### TKT-03.2 - Profile editor dialog restyle and field normalization
- **Goal:** Modernize dialog fields while keeping current validation behavior.
- **Scope:**
  - Apply tokenized text fields and action buttons.
  - Keep import TOML/resolvers behavior.
- **Dependencies:** TKT-03.1.
- **Acceptance criteria:**
  - Save enabled/disabled logic unchanged.
  - Large resolver optimization still present.

### TKT-03.3 - Logs terminal panel redesign
- **Goal:** Implement Stitch terminal-like log experience.
- **Scope:**
  - Restyle top bar, filter chips, and log stream container.
  - Keep filtering/autoscroll/share/clear behaviors.
- **Dependencies:** Phase 1 complete.
- **Acceptance criteria:**
  - `All/Core/Android` filtering unchanged.
  - Auto ON/OFF behavior unchanged.

---

## Phase 4 - Global Settings and Profile Settings

### TKT-04.1 - Global settings section redesign
- **Goal:** Apply sectioned Stitch form language to global settings.
- **Scope:**
  - Restyle connection mode, split tunnel, internet sharing sections.
  - Keep current state and validation logic.
- **Dependencies:** Phase 1 complete.
- **Acceptance criteria:**
  - Port validation remains 1025..65535.
  - Split tunnel picker still fully functional.

### TKT-04.2 - Split tunnel picker UX and performance pass
- **Goal:** Improve picker readability and list performance.
- **Scope:**
  - Add clearer tab/selection hierarchy and optimized list derivation.
  - Avoid expensive recomposition work.
- **Dependencies:** TKT-04.1.
- **Acceptance criteria:**
  - Smooth search and selection on large installed-app lists.
  - Apply/cancel semantics unchanged.

### TKT-04.3 - Profile settings accordion and field card redesign
- **Goal:** Redesign advanced settings editor to tokenized section cards.
- **Scope:**
  - Restyle all section headers and field types (TEXT/BOOL/OPTION).
  - Keep import/export/save logic unchanged.
- **Dependencies:** Phase 1 complete.
- **Acceptance criteria:**
  - Sections: Identity/Proxy/DNS/Resolver/Compression/MTU/Runtime/ARQ/Logging remain intact.
  - Save actions (top + bottom) continue to work.

---

## Phase 5 - Info, Accessibility, and QA Hardening

### TKT-05.1 - Info screen redesign
- **Goal:** Apply Stitch hero + manifest card style to Info page.
- **Scope:**
  - Restyle project links/version cards.
  - Preserve external link behavior and version values.
- **Dependencies:** Phase 1 complete.
- **Acceptance criteria:**
  - Link opens unchanged.
  - Build/version values unchanged.

### TKT-05.2 - Accessibility compliance sweep
- **Goal:** Ensure minimum accessibility baseline across redesigned screens.
- **Scope:**
  - Add missing `contentDescription`.
  - Verify touch target and text size constraints.
- **Dependencies:** Phases 2-5 UI tickets.
- **Acceptance criteria:**
  - Icon-only actions all labeled.
  - No interactive element below 48dp touch size.

### TKT-05.3 - Performance and overdraw pass
- **Goal:** Validate low-end runtime constraints.
- **Scope:**
  - Review overdraw hotspots.
  - Remove redundant layers and expensive effects.
- **Dependencies:** Phases 2-5 UI tickets.
- **Acceptance criteria:**
  - No runtime blur on API 21-27.
  - List-heavy screens remain smooth under realistic dataset.

---

## Cross-Cutting Validation Tickets

### TKT-QA.1 - Visual token conformance test
- **Goal:** Ensure all new UI styles reference token set, not random literals.
- **Acceptance criteria:**
  - New UI code has zero ad-hoc hardcoded colors except documented legacy exceptions.

### TKT-QA.2 - Navigation parity regression suite
- **Goal:** Confirm route behavior unchanged after redesign.
- **Acceptance criteria:**
  - Route transitions match current app behavior.
  - Bottom tab structure unchanged.

### TKT-QA.3 - Connection state matrix verification
- **Goal:** Validate state rendering across screens.
- **Acceptance criteria:**
  - Idle/Connecting/Connected/Error visible and consistent on Home and reflected contextually elsewhere.

---

## Suggested Delivery Milestones

- **Milestone A:** Phase 0 + Phase 1 complete.
- **Milestone B:** Phase 2 complete (Home done end-to-end).
- **Milestone C:** Phase 3 complete (Profiles + Logs).
- **Milestone D:** Phase 4 complete (Settings screens).
- **Milestone E:** Phase 5 + QA tickets complete (release candidate).

---

## Definition of Done (Backlog Level)

- All phase tickets are completed with acceptance criteria met.
- Navigation and callback contracts remain behaviorally equivalent.
- UI follows `docs/stitch-ui-redesign-roadmap.md`.
- API 21+ performance constraints are respected.
