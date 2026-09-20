'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { defaultState, sha256, translationKey, type Book, type LibraryState, type Settings, type Translation } from './model';
import { loadState, saveState } from './storage';
export function useLibrary() {
  const [state, setState] = useState<LibraryState>(defaultState);
  const current = useRef(state); const [loaded, setLoaded] = useState(false); const [loadError, setLoadError] = useState(false); const [storageError, setStorageError] = useState(false);
  useEffect(() => { let active = true;
    loadState().then(value => { if (active) { current.current = value; setState(value); setLoaded(true); } })
      .catch(() => { if (active) { setStorageError(true); setLoadError(true); setLoaded(true); } });
    return () => { active = false; };
  }, []);
  const update = useCallback((change: (state: LibraryState) => LibraryState) => {
    const next = change(current.current); current.current = next; setState(next);
    void saveState(next).catch(() => setStorageError(true));
  }, []);
  return { state, current, update, loaded, storageError, loadError };
}
export type Queue = { status: 'ready' | 'running' | 'paused' | 'done'; total: number; completed: number; failed: number[]; bookTitle: string; error: string };
export function useTranslationQueue(current: React.RefObject<LibraryState>, update: (change: (s: LibraryState) => LibraryState) => void) {
  const [queue, setQueue] = useState<Queue>({ status: 'ready', total: 0, completed: 0, failed: [], bookTitle: '', error: '' });
  const job = useRef<{ book: Book; settings: Settings; pages: number[]; index: number; failed: number[]; apiKey: string } | null>(null);
  const control = useRef<AbortController | null>(null);
  useEffect(() => () => { control.current?.abort(); }, []);
  const run = useCallback(async () => {
    const work = job.current; if (!work || control.current) return;
    const controller = new AbortController(); control.current = controller;
    setQueue(q => ({ ...q, status: 'running', error: '' }));
    try {
      for (; work.index < work.pages.length;) {
        if (controller.signal.aborted) return;
        const page = work.pages[work.index]; const key = translationKey(work.book.id, page, work.settings);
        if (!current.current.books.some(b => b.id === work.book.id)) return;
        if (!current.current.translations.some(t => t.key === key)) {
          try {
            const text = work.book.pages[page].text;
            if (!text.trim()) throw new Error('noText');
            const response = await fetch('/api/translate', { method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ text, provider: work.settings.provider, source: work.settings.source,
                target: work.settings.target, model: work.settings.model, apiKey: work.apiKey }), signal: controller.signal });
            const result = await response.json();
            if (!response.ok) throw new Error(result.code ?? 'providerError');
            const translation: Translation = { key, bookId: work.book.id, page, provider: work.settings.provider,
              source: work.settings.source, target: work.settings.target, model: work.settings.provider === 'llm' ? work.settings.model : '',
              sourceRevision: await sha256(text), text: result.text, createdAt: new Date().toISOString() };
            if (controller.signal.aborted) return;
            update(s => s.books.some(b => b.id === work.book.id) ? { ...s,
              translations: [...s.translations.filter(t => t.key !== key), translation] } : s);
          } catch (error) {
            if (controller.signal.aborted) return;
            work.failed.push(page);
            setQueue(q => ({ ...q, error: error instanceof Error ? error.message : 'network' }));
          }
        }
        work.index++;
        setQueue(q => ({ ...q, completed: work.index, failed: [...work.failed] }));
        if (work.index < work.pages.length) await new Promise<void>(resolve => {
          const finish = () => { clearTimeout(timer); controller.signal.removeEventListener('abort', finish); resolve(); };
          const timer = setTimeout(finish, work.settings.pacing === 'fast' ? 500 : work.settings.delaySeconds * 1000);
          controller.signal.addEventListener('abort', finish, { once: true });
        });
      }
      if (!controller.signal.aborted) setQueue(q => ({ ...q, status: 'done' }));
    } finally { if (control.current === controller) control.current = null; }
  }, [current, update]);
  const start = (book: Book, settings: Settings, pages: number[], apiKey: string) => {
    if (control.current || queue.status === 'paused') return;
    job.current = { book, settings: { ...settings }, pages, index: 0, failed: [], apiKey };
    setQueue({ status: 'running', total: pages.length, completed: 0, failed: [], bookTitle: book.title, error: '' });
    void run();
  };
  const pause = () => { control.current?.abort(); control.current = null; setQueue(q => ({ ...q, status: 'paused' })); };
  const cancel = () => { control.current?.abort(); control.current = null; job.current = null;
    setQueue({ status: 'ready', total: 0, completed: 0, failed: [], bookTitle: '', error: '' }); };
  const retry = (apiKey: string) => {
    const work = job.current;
    if (!work || control.current) return;
    const failed = [...work.failed];
    job.current = { ...work, apiKey, pages: failed, failed: [], index: 0 };
    setQueue(q => ({ ...q, total: failed.length, completed: 0, failed: [] })); void run();
  };
  return { queue, start, pause, cancel, resume: (apiKey: string) => { if (job.current) job.current.apiKey = apiKey; void run(); }, retry };
}
