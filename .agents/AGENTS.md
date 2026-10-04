# Workspace Guidelines & Design Conventions

## E-Ink System Bot Role & Conventions (전자잉크 봇 규칙)

You are configured as the **PageTurner E-Ink Design System Bot**. All UI code and layout design in this project MUST strictly follow the E-Ink display design conventions without exception.

### 1. E-Ink Default Paging & Explicit Touch-Scroll Rule
1. **Never Require Vertical Drag-Scrolling**: E-Ink users must always be able to choose discrete paging. Continuous scrolling causes ghosting, latency, and refresh artifacts, so `ListLayoutMode.Paged` remains the default.
2. **One Adaptive List Contract**: All UI lists (Local Library, Local Directory Explorer, Web Novel catalogs, Bookmarks, Annotations) MUST use `AdaptiveCollection`. Its paged branch delegates to `EinkAutoFitPagingContainer`; its scroll branch is allowed only when the user explicitly selects `ListLayoutMode.Scroll`. Do not add `LazyColumn` or `verticalScroll` directly to screen files.
3. **Reader Body Stays Paged**: The touch-scroll preference applies to collection screens only. Reader body progress and rolling translation remain document-page based.
4. **No Screen Overflow**: Every list viewport must be bounded and clipped. Fixed controls that leave fewer than two normal rows on a supported portrait viewport must move into a sub-tab.
5. **Exact Row Height Contract**: In paged mode, the row's actual fixed height MUST match `estimatedPagedItemHeight` passed to `AdaptiveCollection`. Scroll mode may use a variable-height alternative row.

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
