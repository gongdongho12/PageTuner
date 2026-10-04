import { useEffect } from "react";
import { pageKeyDirection, type ReaderPreferences } from '../lib/readerPreferences';

export function usePageKeys(
  previous: () => void,
  next: () => void,
  enabled = true,
  mode: ReaderPreferences['pageKeys'] = 'normal',
) {
  useEffect(() => {
    if (!enabled) return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target;
      if (
        event.altKey ||
        event.ctrlKey ||
        event.metaKey ||
        event.defaultPrevented
      )
        return;
      if (
        target instanceof Element &&
        target.closest(
          'input, textarea, select, [contenteditable="true"], [role="dialog"]',
        )
      )
        return;
      if (
        event.code === "Space" &&
        target instanceof Element &&
        target.closest("button, a")
      )
        return;
      const direction = pageKeyDirection(event.key, event.code, event.shiftKey, mode);
      if (direction === -1) {
        event.preventDefault();
        previous();
      } else if (direction === 1) {
        event.preventDefault();
        next();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [previous, next, enabled, mode]);
}
