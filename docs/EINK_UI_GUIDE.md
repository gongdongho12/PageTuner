# PageTurner E-Ink UI Guide

This document is the implementation reference for PageTurner's Jetpack Compose
UI on E-Ink devices. It describes the layout contracts behind the shared
widgets, not only their visual appearance.

Use it together with:

- [workspace rules](../.agents/AGENTS.md)
- [E-Ink design-system skill](../.agents/skills/eink_design_system/SKILL.md)
- [application architecture](ARCHITECTURE.md)

## 1. Design goals

PageTurner screens must remain usable on a slow-refresh, narrow, non-scrolling
viewport.

The implementation has four primary goals:

1. Every interactive item is completely visible and reachable.
2. Navigation causes discrete page changes instead of continuous movement.
3. Loading and long-running work remain visible without animation-dependent
   feedback.
4. Text remains readable without silently clipping content below a panel.

## 2. Non-negotiable rules

### Discrete paging by default; explicit touch scrolling only

E-Ink collection screens default to `ListLayoutMode.Paged`. A user may explicitly
select `ListLayoutMode.Scroll` for touch-oriented use, but screens must not build
their own scrolling implementation.

Do not use these directly in screen files:

```kotlin
LazyColumn { /* ... */ }
Modifier.verticalScroll(rememberScrollState())
```

`AdaptiveCollection` owns the only permitted `LazyColumn`. Its paged branch uses
`EinkAutoFitPagingContainer`, while its scrolling branch remains an explicit
user preference. Reader body content never follows this setting because reading
progress and rolling translation are document-page based.

### Bound every screen to the remaining viewport

A child can calculate an automatic page size only when its parent supplies a
finite height.

Use this hierarchy:

```kotlin
Column(Modifier.fillMaxSize()) {
    EinkSegmentedControl(/* ... */)

    EinkViewportSurface(
        modifier = Modifier.weight(1f),
    ) {
        EinkAutoFitPagingContainer(
            items = items,
            modifier = Modifier.weight(1f),
            estimatedItemHeight = 96.dp,
        ) { item ->
            ExampleRow(
                item = item,
                modifier = Modifier.height(96.dp),
            )
        }
    }
}
```

Missing either `fillMaxSize()` or `weight(1f)` commonly makes `maxHeight`
unbounded. The pager then has to use its conservative fallback instead of the
real device height.

### Match the row height exactly

`estimatedItemHeight` is a layout contract even though the historical
parameter name says "estimated".

```kotlin
private val ChapterRowHeight = 124.dp

EinkAutoFitPagingContainer(
    items = chapters,
    estimatedItemHeight = ChapterRowHeight,
) { chapter ->
    ChapterRow(
        modifier = Modifier.height(ChapterRowHeight),
        chapter = chapter,
    )
}
```

Do not pass `48.dp` for a row containing a 48 dp button plus padding, multiple
text lines, or a second action row. That was the main cause of partially visible
chapter entries.

### Keep page sizes conservative

`EinkAutoFitPagingContainer` calculates:

```text
available = viewport height - page navigation reserve
page size = floor(available / (row height + row spacing))
```

The result is limited to 1 through 8 rows. If height is unbounded, the fallback
is limited to 3 through 5 rows. Never expose arbitrary `12/p` or `24/p` controls
on a device where those rows cannot fit.

## 3. Shared component selection

| UI need | Component | Notes |
| --- | --- | --- |
| Bounded full-screen panel | `EinkViewportSurface` | Supplies `fillMaxSize()` and standard panel border/padding. |
| Selectable collection list | `AdaptiveCollection` | Required screen-level component. Delegates to paging or opt-in touch scroll. |
| E-Ink page implementation | `EinkAutoFitPagingContainer` | Internal paged branch. Pair with exact fixed-height rows. |
| Proven fixed-height list | `EinkPagingContainer` | Use only when the complete parent and row height are statically known. |
| Page navigation | `EinkPageNavigation` | Internal shared previous/range/next bar. Center text has a fixed region. |
| Remote catalog navigation | `EinkRemoteCatalogPager` | Server-side first/previous/next/last controls; keep separate from viewport paging. |
| Two to five categories | `EinkSegmentedControl` | Equal-width, two-line labels with a solid selected marker. |
| Many mutually exclusive choices | `EinkChoiceStepper` | Previous/current/next interaction without wrapped chips. |
| Long-running work | `EinkOperationIndicator` | Static high-contrast progress suited to low refresh rates. |
| Reader text fitting | `EinkAutoFitText` | Reduces text size only when bounded content overflows. |
| Global status | `StatusStrip` | Allows three lines and shows a visible busy bar at zero progress. |
| Compact toolbar | `EinkSingleLineToolbar` | Use only when truncating the title does not hide an action or choice. |

The shared components live in:

```text
app/src/main/java/com/dongholab/pagetuner/ui/common/
```

## 4. Screen composition patterns

### Split complex screens before reducing content

When a screen has multiple independent jobs, make each job a sub-tab.

Current examples:

- Local: `Library` / `Device files`
- Web novel detail: `Overview` / `Chapters`
- Web novel source management: `Saved sources` / `Catalog filters`
- Settings: display, reader, translation, and diagnostics categories

Use `EinkSegmentedControl` instead of reimplementing selected borders and
indicator bars in each screen.

In paged mode, a primary list should retain room for at least two normal rows on
a supported portrait viewport. Search, batch actions, and advanced filters must
move into a sub-tab when they would reduce the list below that threshold.

```kotlin
enum class Section(val label: String) {
    Overview("Overview"),
    Chapters("Chapters"),
}

var section by remember { mutableStateOf(Section.Chapters) }

EinkSegmentedControl(
    options = Section.entries,
    selected = section,
    onSelect = { section = it },
    label = Section::label,
)
```

### Use a stepper instead of an unbounded chip flow

Dynamic folders, languages, sources, and categories may exceed one or two
lines. A `FlowRow` is safe only for a short, compile-time-bounded set.

For a dynamic set, use:

```kotlin
EinkChoiceStepper(
    options = listOf(AllFolders) + folders,
    selected = selectedFolder,
    onSelect = onFolderSelected,
    label = FolderOption::displayName,
)
```

All choices remain reachable without creating content below the viewport.

### Keep editing forms out of list rows

List rows should identify an item and expose one or two compact actions. Do not
place several text fields inside every row. Use a dialog or a dedicated edit
sub-page.

The local library follows this pattern: the row stays at a stable height, while
folder and tag editing opens in a dialog.

## 5. Row layout contract

A paged row should have:

- a single fixed outer height;
- a 1 dp `EinkLine` border;
- no elevation-dependent separation;
- at least 44 dp for every interactive target;
- a full-width title region before actions consume width;
- explicit `maxLines` and `TextOverflow.Ellipsis` only for metadata that can be
  recovered from a detail screen;
- action text that remains on one line, or a two-row layout with actions below
  the title.

Recommended starting heights:

| Row type | Starting height |
| --- | ---: |
| File or bookmark | 64 dp |
| Annotation | 76 dp |
| Catalog or local-book summary | 104–116 dp |
| Chapter with title and action row | 124 dp |

These are starting values, not universal constants. If the row structure
changes, update both the actual row height and `estimatedItemHeight`.

## 6. Text and reader pagination

### UI labels

- Give important titles up to two or three lines before truncating.
- Avoid putting a long title and several text buttons in one horizontal row.
- Use weighted regions in navigation bars so the page counter cannot push
  previous/next actions off-screen.
- Localize user-facing labels through `stringResource` when adding production
  copy.

### Reader body

Reader content has two layers of protection:

1. `PlainTextDocumentParser` creates conservative text pages.
2. `EinkAutoFitText` fits the current page inside the actual reader surface.

Do not solve reader overflow by adding vertical scrolling. If the minimum font
size is still insufficient, reduce parser page size or split oversized text
segments at sentence boundaries.

When automatic web-novel translation starts, translation-only mode is used so
the original and translated text do not each receive an unusably small half of
the viewport.

## 7. Loading and asynchronous work

E-Ink feedback must not depend on a spinning animation.

Use `EinkOperationIndicator` at the location where the operation started:

```kotlin
EinkOperationIndicator(
    visible = state.busy,
    title = "Loading chapter list…",
    detail = state.statusText,
    progress = state.progress.takeIf { it > 0f },
)
```

Use a solid progress bar for unknown progress. This remains visible after a
single screen refresh and avoids rapid animation artifacts.

For multi-stage operations, expose the actual stage:

```text
Loading source page -> Extracting DOM -> Saving locally -> Translating page
```

Disable actions while their operation is active, but keep back navigation
available unless leaving would corrupt state.

Avoid racing an automatic translation request against a cache lookup. Hold the
pending translation document identity until translation succeeds or fails,
then resume normal cached-page loading.

## 8. Color and rendering rules

Use project tokens rather than arbitrary colors:

| Token | Purpose |
| --- | --- |
| `EinkPaper` | Main white reading surface |
| `EinkInk` | Primary text, active borders, primary actions |
| `EinkLine` | 1 dp structural border |
| `EinkMuted` | Secondary text and disabled affordances |
| `EinkPanel` | Panel background |
| `EinkSoft` | Selected or grouped secondary surface |

Avoid:

- gradients;
- blur and glass effects;
- shadows as the only boundary;
- subtle alpha-only state changes;
- rapidly animated indeterminate indicators.

Selected state must remain understandable in grayscale through a solid border,
fill, underline, icon, or text weight.

## 9. Common failure patterns

### "The pager says four items, but only three are visible"

Cause: the reported row estimate is smaller than the row's actual measured
height, or the pager did not receive a bounded parent height.

Fix:

1. Give the parent `fillMaxSize()`.
2. Give the pager `weight(1f)`.
3. Give the row a fixed height.
4. Pass the same height as `estimatedItemHeight`.

### "The last control exists but cannot be selected"

Cause: several independent sections were stacked in a non-scrolling column.

Fix: split sections with `EinkSegmentedControl`; do not reduce touch targets to
force everything into one screen.

### "Titles become `Ch. #1 - Cha…`"

Cause: title and all actions compete in one horizontal row.

Fix: reserve a full-width title row and put actions in a second row, or move
secondary actions to a detail dialog.

### "Busy state is invisible"

Cause: a zero-value determinate progress bar renders like an empty track, or
status is shown only at the bottom of a different screen.

Fix: show `EinkOperationIndicator` inline and render unknown progress as a
solid high-contrast bar.

### "The reader leaves too little room for the book"

Use reader full screen when the goal is maximum text per physical refresh.
The full-screen viewport is intentionally body-only: Android system bars, the
app header, reader sub-tabs, translation status, and the bottom pager are all
removed. The configured paper margin is capped at 8 dp and translation-only
mode applies that margin once (never both outside and inside its panel).

The left and right 40% regions remain previous/next page targets. The center
20% exits full screen, as does the Android Back action. Background translation
and catalog loading must not disable page turns; only a library mutation may
temporarily lock navigation. A cached translation is rendered only when its
page index matches the visible page, preventing a stale translated page from
flashing during fast navigation.

Plain-text and downloaded web-novel chapters use a dense 1,100-character page
target. Auto-fit reduces the font only when that bounded page would otherwise
clip, down to the 11 sp safety floor. Reflow deliberately retains the former
620-character segment IDs, so already downloaded translations remain addressable;
when the resulting page count changes, saved reading progress is remapped by
percentage instead of merely clamping to the new last page.

## 10. Implementation checklist

Before completing an E-Ink UI change, verify all of the following:

- [ ] The screen root receives a bounded `fillMaxSize()` viewport.
- [ ] No screen directly introduces `LazyColumn` or `verticalScroll`.
- [ ] Every dynamic collection uses `AdaptiveCollection`.
- [ ] `ListLayoutMode.Paged` remains the default and Reader body stays paged.
- [ ] Auto-fit pagers use `Modifier.weight(1f)`.
- [ ] Every paged row has a fixed height matching `estimatedPagedItemHeight`.
- [ ] The page size remains between 1 and 8; fallback remains between 3 and 5.
- [ ] Dynamic choices remain reachable without an unbounded `FlowRow`.
- [ ] Complex screens use sub-tabs.
- [ ] Buttons have at least a 44 dp target.
- [ ] Important text is not hidden solely by ellipsis.
- [ ] Busy, progress, empty, and error states are visible inline.
- [ ] Selected state is clear in grayscale.
- [ ] English and Korean resources are updated for new production strings.
- [ ] Hardware or button page navigation still works while touch scrolling is
      absent.
- [ ] Full screen leaves only reader content and restores system bars on exit.
- [ ] Translation-only content has one paper margin, not nested panel padding.
- [ ] Background translation/catalog work does not block reader page turns.

## 11. Verification

Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Static checks:

```bash
rg -n "verticalScroll|LazyColumn" app/src/main/java/com/dongholab/pagetuner/ui
rg -n "AdaptiveCollection\(" app/src/main/java/com/dongholab/pagetuner/ui
rg -n "EinkPagingContainer\\(" app/src/main/java/com/dongholab/pagetuner/ui
```

The first command may return `LazyColumn` only from
`ui/common/AdaptiveCollection.kt`. Every screen-level list should appear in the
second command. A direct pager or scroll call requires an explicit justification.

Manual device checks should include:

- first and last list pages;
- an empty list;
- a one-item list;
- a very long title;
- Korean and English locale;
- busy and failed operations;
- largest reader font and line spacing;
- narrow phone and larger E-Ink tablet viewports;
- hardware previous/next controls where available.

The production page-size calculation is covered by
`EinkAutoFitPagingContainerTest`; update that test when changing the reserved
navigation height, row spacing, fallback policy, or maximum page size.

Web-novel route ownership, remote-versus-viewport paging, and refresh behavior
are documented in [Web Novel Page Architecture](WEB_NOVEL_PAGE_ARCHITECTURE.md).
The measured Android rendering comparison between the paged and opt-in scroll
branches is recorded in [E-Ink Collection Layout Benchmark](EINK_LIST_LAYOUT_BENCHMARK.md).

## 12. Shared component file map

```text
ui/common/EinkViewportSurface.kt
ui/common/AdaptiveCollection.kt
ui/common/EinkSegmentedControl.kt
ui/common/EinkChoiceStepper.kt
ui/common/EinkAutoFitPagingContainer.kt
ui/common/EinkPagingContainer.kt
ui/common/EinkPageNavigation.kt
ui/common/EinkOperationIndicator.kt
ui/common/EinkAutoFitText.kt
ui/common/EinkSingleLineToolbar.kt
ui/common/StatusStrip.kt
```

When a new E-Ink layout issue appears in more than one screen, fix or extend a
shared component first, then migrate all affected call sites. Do not copy a
screen-specific workaround into multiple panels.
