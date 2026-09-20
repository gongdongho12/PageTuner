'use client';
import { useEffect } from 'react';
export function OfflineShell() {
  useEffect(() => {
    if (process.env.NODE_ENV !== 'production' || !('serviceWorker' in navigator)) return;
    let active = true;
    void navigator.serviceWorker.register('/sw.js').then(() => navigator.serviceWorker.ready).then(registration => {
      if (!active) return;
      const urls = [...Array.from(document.querySelectorAll<HTMLScriptElement>('script[src]')).map(n => n.src),
        ...Array.from(document.querySelectorAll<HTMLLinkElement>('link[rel=stylesheet]')).map(n => n.href),
        ...performance.getEntriesByType('resource').map(r => r.name)];
      registration.active?.postMessage({ type: 'CACHE_ASSETS', urls });
    }).catch(() => { /* Library storage remains available when SW is disabled by browser policy. */ });
    return () => { active = false; };
  }, []);
  return null;
}
