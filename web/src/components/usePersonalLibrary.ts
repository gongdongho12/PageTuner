import { useEffect, useMemo } from "react";
import { createPersonalLibrary } from "../lib/personalLibrary";

/** Dispose after React's development effect replay, while preserving account isolation. */
export function usePersonalLibrary(username: string) {
  const resource = useMemo(
    () =>
      username
        ? { library: createPersonalLibrary(username), generation: 0 }
        : null,
    [username],
  );
  useEffect(() => {
    if (!resource) return;
    const generation = ++resource.generation;
    return () => {
      queueMicrotask(() => {
        if (resource.generation === generation) resource.library.close();
      });
    };
  }, [resource]);
  return resource?.library ?? null;
}
