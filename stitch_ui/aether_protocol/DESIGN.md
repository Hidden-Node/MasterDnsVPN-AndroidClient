# Design System: High-Tech Security Interface

## 1. Overview & Creative North Star

**Creative North Star: The Sovereign Node**
This design system moves away from the "toy-like" aesthetics of consumer VPNs and embraces a "Sovereign Node" philosophy—an interface that feels like a high-end command deck for digital privacy. It is characterized by **Tonal Depth**, **Kinetic Light**, and **Information Density** handled with editorial grace.

To break the "template" look, we utilize intentional asymmetry. Large-scale technical typography is offset against dense data clusters, creating a visual rhythm that suggests power and precision. We avoid the rigid grid of the past, opting for overlapping glass containers and "glowing" data points that feel like they are floating in a deep, atmospheric space.

---

## 2. Colors

The palette is rooted in the deep void of private networking, illuminated by high-energy data streams.

### Palette Strategy
- **Primary (`#c3f5ff` / `#00e5ff`):** Use this for "Online" states and primary actions. It represents the energy of an active, secure connection.
- **Surface & Background (`#10141a`):** A deep navy-black that provides the canvas for high-contrast technical data.
- **Secondary/Tertiary (`#bac8dc` / `#e3eeff`):** Used for metadata and inactive states, ensuring they remain legible but subservient to active data.

### The "No-Line" Rule
**Explicit Instruction:** Designers are prohibited from using 1px solid borders for sectioning or containment. Traditional lines feel "analog" and flat. Boundaries must be defined solely through:
1.  **Background Color Shifts:** Placing a `surface-container-low` section against a `surface` background.
2.  **Tonal Transitions:** Using subtle shifts in dark slate greys to imply structure.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical, translucent layers. Use the Material `surface-container` tiers to create depth:
*   **Base Layer:** `surface` (#10141a)
*   **Secondary Content:** `surface-container-low` (#181c22)
*   **Interactive Cards:** `surface-container-high` (#262a31)
*   **Active Modals/Popups:** `surface-container-highest` (#31353c)

### The "Glass & Gradient" Rule
Floating elements (such as the main VPN toggle or connection stats) should utilize **Glassmorphism**. Combine `surface-variant` at 40% opacity with a `backdrop-blur` of 20px. 

**Signature Texture:** Main CTAs should not be flat. Apply a linear gradient from `primary` (#c3f5ff) to `primary-container` (#00e5ff) at a 135-degree angle to provide a "pulse" of visual energy.

---

## 3. Typography

The typography strategy pairs the technical, geometric precision of **Space Grotesk** with the humanistic readability of **Manrope**.

*   **Display & Headlines (Space Grotesk):** Used for status updates (e.g., "CONNECTED") and high-level data. The wide apertures and technical terminals of Space Grotesk communicate "High-Tech."
*   **Titles & Body (Manrope):** Used for configuration settings and descriptive text. Manrope’s clean construction ensures that even dense technical logs remain legible during long-form reading.
*   **Labels (Space Grotesk):** Small caps or high-tracking labels provide an "instrument panel" feel to the interface metrics.

---

## 4. Elevation & Depth

We convey authority through **Tonal Layering**, not structural separators.

*   **The Layering Principle:** Achieve lift by stacking. A `surface-container-lowest` card placed on a `surface-container-low` section creates a natural "depression" or "lift" depending on the tone, mimicking a sophisticated hardware interface.
*   **Ambient Shadows:** For floating action buttons or critical alerts, use a shadow with a 40px blur, 4% opacity, and a color hex of `#00daf3` (Surface Tint). This creates a "glow" rather than a "shadow," suggesting the element is emitting light.
*   **The "Ghost Border" Fallback:** If a container requires a border for accessibility (e.g., in high-glare environments), use the `outline-variant` token at **15% opacity**. This provides a whisper of a boundary without breaking the immersive dark aesthetic.
*   **Kinetic Glass:** When the VPN is active, the glass containers should have a subtle `inner-glow` (1px blur, 0.5px spread) using the `primary` color at 20% opacity.

---

## 5. Components

### Buttons
*   **Primary:** Gradient fill (`primary` to `primary-container`), bold `label-md` in `on-primary-fixed`. Roundedness: `full`.
*   **Secondary:** Glassmorphic background, `outline-variant` ghost border, `primary` text.
*   **States:** Hover should increase the "glow" (shadow spread); Pressed should reduce opacity to 80%.

### Connection Toggle (Signature Component)
Instead of a standard switch, use a large hexagonal or circular glass "Node." Use a `surface-bright` outer ring with a `primary` inner glow when "Active."

### Chips & Metrics
*   **Data Chips:** Use `secondary-container` for the background and `on-secondary-container` for text. No borders.
*   **Status Indicators:** Use a breathing animation on a 4px `primary` dot to indicate an active data stream.

### Input Fields
*   **Styling:** `surface-container-highest` background with a `none` border. On focus, the `outline` token appears at 30% opacity with a subtle `primary` outer glow.

### Cards & Lists
*   **Rule:** Forbid divider lines. Use `0.75rem` (xl) roundedness for cards. Separate list items using `surface-container` shifts or `16px` of vertical whitespace.

---

## 6. Do's and Don'ts

### Do
*   **DO** use "Space Grotesk" for numbers and metrics; it enhances the "technical instrument" feel.
*   **DO** leverage the `surface-container` hierarchy to group related security settings without using boxes.
*   **DO** use the `error` (`#ffb4ab`) color sparingly—only for true security breaches or connection failures.

### Don't
*   **DON'T** use 100% opaque white text. Always use `on-surface` or `on-surface-variant` to maintain the premium, atmospheric depth.
*   **DON'T** use standard Material shadows. Shadows must be colored (tinted with cyan) and extremely diffused.
*   **DON'T** use sharp corners. Use the `xl` (0.75rem) roundedness scale to keep the futuristic aesthetic feeling sophisticated and "machined" rather than aggressive.