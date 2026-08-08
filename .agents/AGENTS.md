# Workspace Guidelines & Design Conventions

## E-Ink Discrete Paging Convention (Non-Scrollable UI Rule)
1. **Never Rely on Vertical Drag-Scrolling**: On E-Ink displays, continuous vertical scrolling produces severe ghosting, latency, and refresh artifacts.
2. **Mandatory List & Panel Pagination**: All UI lists (Local Library, Local Directory Explorer, Web Novel catalogs, Bookmarks, Annotations) must use discrete `EinkPagingContainer` (or explicit `◄ Prev` / `Next ►` arrow buttons) with fixed page sizes (5 to 8 items).
3. **No Screen Overflow**: Any UI panel that exceeds screen boundaries must be paginated into discrete E-Ink pages using `EinkPagingContainer` to ensure zero drag-scrolling and instantaneous page flips.
