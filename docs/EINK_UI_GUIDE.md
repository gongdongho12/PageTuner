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
user preference. Reader body content always uses discrete display pages. Its
viewport pagination remains separate from canonical document identities used by
reading progress and translation.

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
        AdaptiveCollection(
            items = items,
            modifier = Modifier.weight(1f),
            estimatedPagedItemHeight = 96.dp,
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

AdaptiveCollection(
    items = chapters,
    estimatedPagedItemHeight = ChapterRowHeight,
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

### Fill the measured viewport with complete rows

`EinkAutoFitPagingContainer` calculates:

```text
all-items capacity = floor((viewport height + row spacing) / (row height + row spacing))
if every item fits: omit navigation
otherwise: page size = floor((viewport height - navigation height) / (row height + row spacing))
```

The navigation height comes from the same font metrics used to render the bar,
including the system font scale. It is at least 60 dp and leaves button targets
larger than 44 dp. Calculations use rounded physical pixel sizes for the row,
spacing, and navigation, so fractional display density cannot add a clipped
last row.

A finite viewport has no arbitrary eight-row cap: a tall display shows every
complete row that fits. Only an unbounded viewport uses the caller's fallback,
clamped to 1 through 5 rows. A zero-height or too-short viewport is not an
unbounded viewport and must not mount a fallback row. Do not expose arbitrary
`12/p` or `24/p` controls without measuring whether those rows fit.

If one complete page cannot fit, the pager replaces rows and navigation with a
space notice. The full notice is measured before display; a smaller indicator
and an accessible description cover very short viewports. At zero height only
the accessible description can remain. `onInsufficientHeight` reports the
required height, or `null` after recovery, so a parent can move controls into a
sub-tab or otherwise compact its layout. Do not put navigation after an
oversized row: that makes page-turn controls unreachable.

### Keep an item anchor across layout changes

Supply `itemKey` from the item's stable identity and retain the screen-owned
`EinkPagingState` through refreshes. The paged branch uses the key for both
composition and anchor reconciliation. Resizing selects the new page that
contains the previously anchored item; it does not reuse the old page number.
Keep that original anchor across repeated resizes to prevent gradual drift.
An insertion or reorder locates the same key, while deletion falls back to the
nearest surviving index. A temporary empty loading result preserves the anchor.
An explicit page turn selects a new anchor, and a deliberate filter or route
change may call `reset()` or replace the state's reset keys.

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
| Reader body | `ReaderSurface` / `ReaderMeasuredContent` | Measures display slices at the selected font size; overflow goes to another display page. |
| Bounded preview text | `EinkAutoFitText` | Legacy fitting helper used by note previews; not the reader-body pagination policy. |
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

Canonical parsing and display pagination have separate responsibilities:

1. The document parser retains the original pages, segment IDs, text, and
   revision used by translation storage, bookmarks, search, and synchronization.
2. `ReaderMeasuredContent` measures the available width and height with the
   selected font size, line spacing, system density/font scale, margins, display
   mode, and glossary emphasis. `ReaderTextPagination` finds the text slice that
   fully fits that surface. Remaining text becomes the next display page.
3. `ReaderDisplayPosition` maps display offsets back to a canonical page and
   source-character offset. A display page can span canonical text pages, or a
   single canonical page can need several display pages. The two page numbers
   must never be treated as interchangeable identities.

Keep the user's chosen font size. Do not silently shrink reader text, alter
canonical parser boundaries, or introduce scrolling to make a page fit. Next
and previous display slices retain whitespace and avoid splitting UTF-16
surrogate pairs. If even one character cannot fit, show a space error instead
of claiming that clipped text has been displayed.

Re-measure after viewport, typography, glossary, or translation changes while
retaining the source anchor. Translation-only and split layouts measure their
own available panels. Translated offsets stay associated with their known
source segment; there is no invented character-for-character alignment between
different languages. Cached translations remain addressed by the original
canonical identities.

The web reader follows the same separation using measured DOM fragments and
canonical paragraph ranges. Font-loading completion also triggers reflow;
only pagination belonging to the current measured viewport is sent to rolling
translation.

PDF source pages retain their physical page boundaries and fit mode. Text
display pagination must not combine or renumber those physical pages. EPUB
image ordering and completeness remain the separate D2 task: the current image
layout still takes at most two images from a canonical page. Text reflow does
not establish that every EPUB illustration is rendered or survives offline/ZIP
round trips.

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

During ordinary page turns, do not publish a cache-hit status for every page.
Keep cache lookup page-scoped and visually silent for its first 250 ms; show the
static operation indicator only when storage is genuinely slow, or when the
current page is queued/translating. Background work for another page must not
change the visible page's loading panel.

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
canonical page index matches the active source page, preventing a stale
translation from flashing during fast navigation. That canonical index is
independent of the display slice currently shown.

Plain-text and downloaded web-novel bodies now use measured display slices at
the selected typography. Increasing available space can fit text from adjacent
canonical pages; decreasing it moves overflow to later display pages. Parser
pages and existing segment IDs remain unchanged, so a viewport change neither
invalidates downloaded translations nor requires rewriting saved identities.
Reflow retains canonical source position rather than remapping by display-page
percentage. PDF physical pages and the current image-bearing-page layout keep
their own boundaries.

## 10. Implementation checklist

Before completing an E-Ink UI change, verify all of the following:

- [ ] The screen root receives a bounded `fillMaxSize()` viewport.
- [ ] No screen directly introduces `LazyColumn` or `verticalScroll`.
- [ ] Every dynamic collection uses `AdaptiveCollection`.
- [ ] `ListLayoutMode.Paged` remains the default and Reader body stays paged.
- [ ] Auto-fit pagers use `Modifier.weight(1f)`.
- [ ] Every paged row has a fixed height matching `estimatedPagedItemHeight`.
- [ ] Finite viewports use every complete row that fits; only unbounded height
      uses a fallback of 1 through 5 rows.
- [ ] Navigation measurement matches its rendered height at larger font scales.
- [ ] Zero/short viewports show no clipped row, button, or partial space notice.
- [ ] Stable item keys and anchors survive resize, refresh, and temporary empty
      loading results; deliberate resets still start at the beginning.
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
- [ ] Reader font size stays at the user's selection; overflow is available on
      another display page without altering canonical page or segment IDs.
- [ ] Source anchors survive translation arrival and text reflow; physical PDF
      page boundaries remain intact.

## 11. Verification

Run:

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
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
- zero-height and just-below/at-one-row-plus-navigation boundaries;
- insertions, reordering, temporary empty loading results, and repeated resize
  while viewing a later collection page;
- long source and translated text, whitespace and supplementary characters,
  forward/backward page turns, and translation arrival during reading;
- PDF physical pages and EPUB image-bearing pages, with D2 limitations recorded
  separately from text pagination;
- hardware previous/next controls where available.

The production page-size calculation is covered by
`EinkAutoFitPagingContainerTest`; update that test when changing navigation
measurement, row spacing, fallback policy, or anchor reconciliation.
`EinkCollectionViewportInstrumentedTest` checks actual Compose bounds at the
row/navigation boundary, resize/refresh behavior, and larger font scale.
`ReaderTextPaginationTest` checks exact text coverage and canonical mapping.
Compiling instrumentation sources is not evidence of a successful device run;
record connected-device execution separately.

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
ui/reader/ReaderMeasuredContent.kt
ui/reader/ReaderTextPagination.kt
ui/reader/ReaderDisplayText.kt
reader/ReaderDisplayPosition.kt
```

When a new E-Ink layout issue appears in more than one screen, fix or extend a
shared component first, then migrate all affected call sites. Do not copy a
screen-specific workaround into multiple panels.
