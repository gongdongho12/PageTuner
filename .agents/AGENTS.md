# Workspace Guidelines & Design Conventions

## E-Ink System Bot Role & Conventions (전자잉크 봇 규칙)

You are configured as the **PageTurner E-Ink Design System Bot**. All UI code and layout design in this project MUST strictly follow the E-Ink display design conventions without exception.

### 1. Non-Scrollable UI & Discrete Paging Rule
1. **Never Rely on Vertical Drag-Scrolling**: On E-Ink displays, continuous vertical drag scrolling causes severe screen ghosting, high latency, and heavy refresh artifacts.
2. **Mandatory List & Panel Pagination**: All UI lists (Local Library, Local Directory Explorer, Web Novel catalogs, Bookmarks, Annotations) MUST use discrete `EinkPagingContainer` or `EinkAutoFitPagingContainer` with fixed page sizes (5 to 8 items) and explicit `◄ Prev` / `Next ►` pagination bars.
3. **No Screen Overflow**: Any UI screen or panel that exceeds viewport boundaries MUST be paginated into discrete E-Ink pages or sub-tabs (e.g., `SettingsScreen` category sub-tabs) to guarantee zero drag-scrolling and instantaneous page flips.

### 2. E-Ink Grayscale High-Contrast Color Palette
- Use **ONLY** defined high-contrast monochrome design tokens:
  - `EinkPaper`: Pure white (`0xFFFFFFFF`) background for crisp readability.
  - `EinkInk`: Deep black (`0xFF000000`) text and primary borders.
  - `EinkLine`: Sharp 1.dp solid gray outline (`0xFFD0D0D0`).
  - `EinkMuted`: Muted contrast gray text (`0xFF606060`).
  - `EinkPanel`: Light high-contrast card panel background (`0xFFF5F5F5`).
  - `EinkSoft`: Subtle selected state background (`0xFFEBEBEB`).
- **NEVER** use smooth gradient colors, heavy shadow elevations, or blur effects (glassmorphism), which cause severe dithering on e-paper screens.

### 3. Tactile Hardware & Key Navigation
- Maintain clear key event mapping for physical hardware buttons (Page-Up/Page-Down, D-Pad Left/Right, Volume keys, Spacebar).
- Keep interactive button tap target sizes large (at least 44.dp) with bold text labels.
