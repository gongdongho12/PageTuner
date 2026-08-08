---
name: eink_design_system
description: Design conventions and UI component architecture guidelines for PageTurner E-Ink displays.
---

# E-Ink Design System Guidelines

When building or modifying UI components in PageTurner:

1. **Discrete Page Container**:
   Always wrap list elements inside `EinkPagingContainer` or `EinkAutoFitPagingContainer`. Do not use `LazyColumn` or `verticalScroll` for lists.

2. **Sub-Tab Categorization**:
   For complex settings or multi-section screens, divide options into discrete sub-tab buttons (`FilterChip` or `TextButton`) to fit exactly within 100% of the viewport height.

3. **High-Contrast Typography & Palette**:
   Use `EinkInk` for primary text and `EinkPaper`/`EinkPanel` for container surfaces with a 1.dp `EinkLine` border.
