import { translate as t } from '../lib/locale';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { ReadingAnchor } from "../lib/offline";
import { Icon } from "./Icon";
import { usePageKeys } from "./usePageKeys";
import { ReaderTools } from './ReaderTools';
import { firstAnchor, type ReadingDocument } from '../lib/readingDocument';
import { useReaderPreferences } from './ReaderPreferences';
import { readerFontFamilies } from '../lib/readerPreferences';
import { useReaderFullscreen, useReaderTouch } from './useReaderControls';
import { createReadingNotes, type ReadingNote } from '../lib/readingNotes';
import { captureReadingSelection, highlightedReaderParts, type ReadingSelection } from '../lib/readingSelection';
import { deviceStorageMessage } from '../lib/deviceReadingDatabase';
import { usePersonalLibrary } from './usePersonalLibrary';
import { GlossaryEditor } from './GlossaryEditor';
import { sha256 } from '../lib/validation';
import { canonicalReaderPages, glossaryReaderProjection, glossaryReaderParts, type CanonicalReaderRange } from '../lib/glossaryReader';
import type { GlossaryDisplay, GlossaryDisplayEntry } from '../lib/glossaryDisplay';
export type { ReadingDocument } from '../lib/readingDocument';
import { reflowReaderLocation, turnReaderPage, type ReaderLocation, type ReaderFragment, } from "./readerPosition";
type Fragment = ReaderFragment;
export type PagedReaderProps = {
    document: ReadingDocument;
    anchor?: ReadingAnchor;
    preview?: boolean;
    /** Derived display-only documents must never create canonical notes or highlights. */
    readOnly?: boolean;
    editionLabel?: string;
    readerLabel?: string;
    contentKindLabel?: string;
    saved?: boolean;
    saving?: boolean;
    onClose: () => void;
    onSave?: () => void;
    actionLabel?: string;
    onAction?: () => void;
    positionNote?: string;
    notesNamespace?: string;
    actionError?: string;
    onAnchorChange: (anchor: ReadingAnchor) => void;
    onPaginationChange?: (value: { documentId: string; pages: readonly (readonly CanonicalReaderRange[])[]; page: number }) => void;
};
function boundaries(text: string) {
    const result = [0];
    if (typeof Intl.Segmenter === "function") {
        const segmenter = new Intl.Segmenter(undefined, {
            granularity: "grapheme",
        });
        for (const segment of segmenter.segment(text))
            result.push(segment.index + segment.segment.length);
    }
    else {
        for (const char of text)
            result.push(result[result.length - 1] + char.length);
    }
    return result;
}
/** Measure the exact reader DOM. Every source character belongs to exactly one fragment. */
function paginate(paragraphs: ReadingDocument["paragraphs"], measure: HTMLDivElement, height: number, displays: ReadonlyMap<string, GlossaryDisplay>): Fragment[][] {
    const pages: Fragment[][] = [];
    let fragments: Fragment[] = [];
    measure.replaceChildren();
    const flush = () => {
        if (fragments.length)
            pages.push(fragments);
        fragments = [];
        measure.replaceChildren();
    };
    for (const paragraph of paragraphs) {
        const stops = boundaries(paragraph.text);
        let first = 0;
        while (first < stops.length - 1) {
            const element = document.createElement("p");
            element.className = "reader-paragraph";
            measure.append(element);
            const fits = (end: number) => {
                setMeasuredText(element, paragraph.text.slice(stops[first], stops[end]), stops[first], displays.get(paragraph.paragraphId)?.emphasizedRanges ?? []);
                return measure.getBoundingClientRect().height <= height - 2;
            };
            let low = first;
            let high = stops.length - 1;
            while (low < high) {
                const mid = Math.ceil((low + high) / 2);
                if (fits(mid))
                    low = mid;
                else
                    high = mid - 1;
            }
            if (low === first) {
                element.remove();
                if (!fragments.length)
                    throw new Error(t("\uC77D\uAE30 \uC601\uC5ED\uC774 \uB108\uBB34 \uC791\uC2B5\uB2C8\uB2E4. \uAE00\uC790 \uD06C\uAE30\uB97C \uC904\uC774\uAC70\uB098 \uD654\uBA74 \uB192\uC774\uB97C \uB298\uB824 \uC8FC\uC138\uC694."));
                flush();
                continue;
            }
            const start = stops[first];
            const end = stops[low];
            const text = paragraph.text.slice(start, end);
            setMeasuredText(element, text, start, displays.get(paragraph.paragraphId)?.emphasizedRanges ?? []);
            fragments.push({ paragraphId: paragraph.paragraphId, text, start, end });
            first = low;
            if (first < stops.length - 1)
                flush();
        }
    }
    flush();
    return pages;
}
function setMeasuredText(element: HTMLElement, text: string, start: number, emphasis: readonly { start: number; end: number }[]) {
    if (!emphasis.length) { element.textContent = text; return; }
    element.replaceChildren(...glossaryReaderParts(text, start, emphasis).map(part => {
        if (!part.emphasized) return document.createTextNode(part.text);
        const node = document.createElement('strong'); node.textContent = part.text; return node;
    }));
}
export function PagedReader({ document: readingDocument, anchor, preview = false, readOnly = false, editionLabel, readerLabel, contentKindLabel, saved, saving, onClose, onSave, actionLabel, onAction, positionNote, notesNamespace, actionError, onAnchorChange, onPaginationChange, }: PagedReaderProps) {
    const root = useRef<HTMLElement>(null);
    const { preferences, update: updatePreferences, error: preferencesError } = useReaderPreferences(notesNamespace);
    const fullscreen = useReaderFullscreen(root);
    const viewport = useRef<HTMLDivElement>(null);
    const measure = useRef<HTMLDivElement>(null);
    const location = useRef<ReaderLocation>({ page: 0, anchor });
    const fontSize = preferences.fontSize;
    const [bounds, setBounds] = useState({ width: 0, height: 0 });
    const [pages, setPages] = useState<Fragment[][]>([]);
    const [canonicalPages, setCanonicalPages] = useState<CanonicalReaderRange[][]>([]);
    const [page, setPage] = useState(0);
    const [error, setError] = useState("");
    const [toolsOpen, setToolsOpen] = useState(false);
    const [glossaryOpen, setGlossaryOpen] = useState(false);
    const personal = usePersonalLibrary(preview || readOnly ? '' : notesNamespace ?? '');
    const glossaryIdentity = readingDocument.glossaryIdentity ?? (readingDocument.kind === 'local' && readingDocument.local
        ? { providerId: 'uploaded-document', bookId: `local:${readingDocument.local.contentHash}` } : undefined);
    const [glossaryEntries, setGlossaryEntries] = useState<GlossaryDisplayEntry[]>([]);
    const [glossaryError, setGlossaryError] = useState('');
    const { projection, projectionError } = useMemo(() => {
        try { return { projection: glossaryReaderProjection(readingDocument, glossaryEntries), projectionError: '' }; }
        catch (error) { return { projection: glossaryReaderProjection(readingDocument, []), projectionError: error instanceof Error ? error.message : '용어의 종류와 표시 설정을 확인해 주세요.' }; }
    }, [readingDocument, glossaryEntries]);
    useEffect(() => {
        let active = true;
        setGlossaryEntries([]); setGlossaryError('');
        if (personal && glossaryIdentity) void personal.getGlossary(glossaryIdentity.providerId, glossaryIdentity.bookId)
            .then(async glossary => Promise.all(glossary.entries.map(async entry => ({ ...entry, id: (await sha256(entry.source.toLowerCase())).slice(0, 24) }))))
            .then(entries => { if (active) setGlossaryEntries(entries); })
            .catch(error => { if (active) setGlossaryError(error instanceof Error ? error.message : ''); });
        return () => { active = false; };
    }, [personal, glossaryIdentity?.providerId, glossaryIdentity?.bookId, glossaryOpen]);
    const article = useRef<HTMLElement>(null);
    const notes = useMemo(() => notesNamespace && !readOnly ? createReadingNotes(notesNamespace) : undefined, [notesNamespace, readOnly]);
    const [highlights, setHighlights] = useState<ReadingNote[]>([]);
    const paragraphIndices = useMemo(() => new Map(readingDocument.paragraphs.map((p, index) => [p.paragraphId, index])), [readingDocument]);
    const highlightRanges = useMemo(() => highlights.flatMap(item => item.range ? [item.range] : []), [highlights]);
    const displayHighlightRanges = useMemo(() => highlightRanges.map(projection.displayRange), [highlightRanges, projection]);
    const [selection, setSelection] = useState<ReadingSelection>();
    const [highlightBusy, setHighlightBusy] = useState(false), [highlightMessage, setHighlightMessage] = useState('');
    const [highlightError, setHighlightError] = useState('');
    useEffect(() => {
        let active = true;
        setHighlights([]); setSelection(undefined); setHighlightError(''); setHighlightMessage('');
        notes?.list(readingDocument).then(snapshot => {
            if (active) setHighlights(snapshot.items.filter(item => item.kind === 'highlight'));
        }).catch(error => { if (active) setHighlightError(deviceStorageMessage(error)); });
        return () => { active = false; };
    }, [notes, readingDocument, toolsOpen]);
    useEffect(() => {
        if (!notes || toolsOpen || glossaryOpen || preview) return;
        const update = () => {
            try { setSelection(article.current ? captureReadingSelection(readingDocument, article.current, window.getSelection(), projection.sourceAnchor) : undefined); }
            catch (error) { setSelection(undefined); setHighlightError(deviceStorageMessage(error)); }
        };
        document.addEventListener('selectionchange', update);
        return () => document.removeEventListener('selectionchange', update);
    }, [notes, readingDocument, toolsOpen, glossaryOpen, preview, projection]);
    async function saveHighlight() {
        if (!notes || !selection || highlightBusy) return;
        setHighlightBusy(true); setHighlightError(''); setHighlightMessage('');
        try {
            const saved = await notes.add(readingDocument, { kind: 'highlight', title: Array.from(selection.quote.trim()).slice(0, 60).join(''),
                anchor: selection.range.start, range: selection.range });
            setHighlights(value => [...value, saved]); window.getSelection()?.removeAllRanges(); setSelection(undefined);
            setHighlightMessage('선택한 내용을 강조했습니다. 읽기 도구에서 삭제하거나 내보낼 수 있습니다.');
        } catch (error) { setHighlightError(deviceStorageMessage(error)); }
        finally { setHighlightBusy(false); }
    }
    useLayoutEffect(() => {
        const node = viewport.current;
        if (!node)
            return;
        let frame = 0;
        const observer = new ResizeObserver(() => {
            cancelAnimationFrame(frame);
            frame = requestAnimationFrame(() => {
                setBounds((previous) => previous.width === node.clientWidth &&
                    previous.height === node.clientHeight
                    ? previous
                    : { width: node.clientWidth, height: node.clientHeight });
            });
        });
        observer.observe(node);
        return () => {
            observer.disconnect();
            cancelAnimationFrame(frame);
        };
    }, [toolsOpen, glossaryOpen]);
    useLayoutEffect(() => {
        if (!measure.current || bounds.width < 1 || bounds.height < 1)
            return;
        try {
            const next = paginate(projection.document.paragraphs, measure.current, bounds.height, projection.displays);
            const relocated = reflowReaderLocation(next, location.current.anchor ? projection.displayAnchor(location.current.anchor) : undefined);
            location.current = { ...relocated, anchor: location.current.anchor ?? (relocated.anchor ? projection.sourceAnchor(relocated.anchor) : undefined) };
            setPages(next);
            setCanonicalPages(canonicalReaderPages(next, projection));
            setPage(relocated.page);
            setError("");
        }
        catch (failure) {
            setPages([]);
            setCanonicalPages([]);
            setError(failure instanceof Error
                ? failure.message
                : t("\uD398\uC774\uC9C0\uB97C \uB098\uB204\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."));
        }
    }, [projection, bounds, fontSize, preferences.fontFamily, preferences.lineHeight, preferences.pageMargin, toolsOpen, glossaryOpen]);
    const turnPage = useCallback((direction: -1 | 1) => {
        const next = turnReaderPage(pages, location.current, direction);
        if (next === location.current)
            return;
        const source = next.anchor ? projection.sourceAnchor(next.anchor) : undefined;
        location.current = { ...next, anchor: source };
        window.getSelection()?.removeAllRanges(); setSelection(undefined);
        setPage(next.page);
        if (source)
            onAnchorChange(source);
    }, [pages, onAnchorChange, projection]);
    useEffect(() => {
        onPaginationChange?.({ documentId: readingDocument.id, pages: canonicalPages, page });
    }, [readingDocument.id, canonicalPages, page, onPaginationChange]);
    const previous = useCallback(() => turnPage(-1), [turnPage]);
    const next = useCallback(() => turnPage(1), [turnPage]);
    usePageKeys(previous, next, !error && !toolsOpen && !glossaryOpen, preferences.pageKeys);
    const touch = useReaderTouch(preferences.touchDirection, turnPage);
    const typography = { fontFamily: readerFontFamilies[preferences.fontFamily], lineHeight: preferences.lineHeight };
    const percentage = pages.length
        ? Math.round(((page + 1) / pages.length) * 100)
        : 0;
    if (glossaryOpen && personal && glossaryIdentity) return <section className="novel-workspace"><div className="workflow-heading">
        <button className="button-quiet" onClick={() => setGlossaryOpen(false)}>{t('읽기로 돌아가기')}</button><h2>{t('책별 용어집')}</h2></div>
        <GlossaryEditor storage={personal} providerId={glossaryIdentity.providerId} bookId={glossaryIdentity.bookId} onChange={() => {}} /></section>;
    if (toolsOpen && notesNamespace) return <ReaderTools namespace={notesNamespace} document={readingDocument}
      anchor={location.current.anchor ?? firstAnchor(readingDocument)} onClose={() => setToolsOpen(false)}
      onJump={anchor => { const moved = reflowReaderLocation(pages, projection.displayAnchor(anchor)); location.current = { ...moved, anchor }; setPage(moved.page); onAnchorChange(anchor); setToolsOpen(false); }}/>
    return (<section ref={root} className="reader" aria-label={readerLabel ?? (readingDocument.kind === 'local' ? t('로컬 파일 읽기') : readingDocument.kind === "original"
            ? t("\uC6D0\uBB38 \uC77D\uAE30") : readingDocument.kind === "introduction"
            ? t("\uC18C\uAC1C \uC77D\uAE30") : t("\uBC88\uC5ED\uBB38 \uC77D\uAE30"))}>
      <header className="reader-toolbar">
        <button className="button-quiet reader-back" onClick={onClose} aria-label={t("\uC774\uC804 \uD654\uBA74\uC73C\uB85C \uB3CC\uC544\uAC00\uAE30")}>
          <Icon name="back"/>
          <span>{t("\uB3CC\uC544\uAC00\uAE30")}</span>
        </button>
        <div className="reader-title">
          <span className="eyebrow">
            {editionLabel ?? (preview
            ? t("\uBBF8\uB9AC\uBCF4\uAE30 \uC77D\uAE30") : readingDocument.kind === 'local' ? 'LOCAL DOCUMENT' : readingDocument.kind === "original"
            ? "ORIGINAL EDITION"
            : readingDocument.kind === "introduction"
                ? "ABOUT THIS BOOK"
                : "TRANSLATED EDITION")}
          </span>
          <strong title={readingDocument.bookTitle}>
            {readingDocument.bookTitle}
          </strong>
        </div>
        {!preview && onSave && (<button className="button-outline reader-save" onClick={onSave} disabled={saving || saved}>
            <Icon name={saved ? "check" : "download"}/>
            <span>
              {saving ? t("\uBCF4\uAD00 \uC911") : saved ? t("\uAE30\uAE30\uC5D0 \uBCF4\uAD00\uB428") : t("\uAE30\uAE30\uC5D0 \uBCF4\uAD00")}
            </span>
          </button>)}
        {onAction && (<button className="button-outline reader-save" onClick={onAction}>
            {actionLabel}
          </button>)}
        {!preview && !readOnly && notesNamespace && <button className="button-outline reader-save" onClick={() => setToolsOpen(true)}>{t('읽기 도구')}</button>}
        {!preview && personal && glossaryIdentity && <button className="button-outline reader-save" onClick={() => setGlossaryOpen(true)}>{t('용어집')}</button>}
        {!preview && notes && <button className="button-outline reader-save" disabled={!selection || highlightBusy} onPointerDown={event => event.preventDefault()} onClick={() => void saveHighlight()}>{t(highlightBusy ? '저장 중…' : '선택 강조')}</button>}
        <button className="button-outline reader-save" onClick={() => void fullscreen.toggle()}>{t(fullscreen.fullscreen ? '전체 화면 해제' : '전체 화면')}</button>
      </header>
      {actionError && <div role="alert" className="workflow-message">{t(actionError)}</div>}
      {glossaryError && <div role="alert" className="workflow-message">{t(glossaryError)}</div>}
      {projectionError && <div role="alert" className="workflow-message">{t(projectionError)}</div>}
      {highlightError && <div role="alert" className="workflow-message">{t(highlightError)} <button className="button-text" onClick={() => setHighlightError('')}>{t('닫기')}</button></div>}
      {highlightMessage && <div role="status" className="workflow-message">{t(highlightMessage)} <button className="button-text" onClick={() => setHighlightMessage('')}>{t('닫기')}</button></div>}
      {(preferencesError || fullscreen.error) && <div role="alert" className="workflow-message">{t(preferencesError || fullscreen.error)}</div>}
      <div className="reader-sheet" style={{ padding: `${preferences.pageMargin}px ${preferences.pageMargin}px 0` }}>
        <div className="reader-chapter">
          <span title={readingDocument.chapterTitle}>
            {readingDocument.chapterTitle}
          </span>
          <span>
            {readingDocument.language.toUpperCase()} ·{" "}
            {contentKindLabel ?? (readingDocument.kind === 'local' ? t('로컬 파일') : readingDocument.kind === "original"
            ? t("\uC6D0\uBB38") : readingDocument.kind === "introduction"
            ? t("\uC18C\uAC1C") : t("\uBC88\uC5ED\uBB38"))}
          </span>
        </div>
        <div className="reader-page-area" ref={viewport} style={{ fontSize }} {...touch}>
          <div className="reader-measure reader-typeset" style={typography} aria-hidden="true" ref={measure}/>
          {error ? (<div role="alert" className="reader-error">
              {error}
            </div>) : (<article ref={article} className="reader-typeset reader-page" style={typography} aria-label={t("{0}\uBC88\uC9F8 \uD398\uC774\uC9C0", [page + 1])}>
              {pages[page]?.map((fragment) => (<p className="reader-paragraph" key={`${fragment.paragraphId}:${fragment.start}`} data-paragraph-id={fragment.paragraphId} data-character-offset={fragment.start}>
                  {glossaryReaderParts(fragment.text, fragment.start, projection.displays.get(fragment.paragraphId)?.emphasizedRanges ?? [],
                    highlightedReaderParts(projection.document, fragment, displayHighlightRanges, paragraphIndices)).map((part, index) => {
                      const text = part.emphasized ? <strong>{part.text}</strong> : part.text;
                      return part.highlighted ? <mark className="reader-highlight" key={index}>{text}</mark> : <span key={index}>{text}</span>;
                    })}
                </p>))}
            </article>)}
        </div>
        <div className="reader-bottom-rule">
          <span>
            {preview
            ? t("\uB9C8\uC74C\uC5D0 \uB4DC\uB294 \uC18D\uB3C4\uB85C, \uD55C \uD398\uC774\uC9C0\uC529.") : (positionNote ?? t("\uC77D\uC740 \uC704\uCE58\uAC00 \uC774 \uAE30\uAE30\uC5D0 \uAE30\uC5B5\uB429\uB2C8\uB2E4."))}
          </span>
          <span>{percentage}%</span>
        </div>
      </div>
      <footer className="reader-footer">
        <div className="font-controls" aria-label={t("\uAE00\uC790 \uD06C\uAE30")}>
          <button className="icon-button" aria-label={t("\uAE00\uC790 \uC791\uAC8C")} disabled={fontSize <= 14} onClick={() => updatePreferences({ fontSize: Math.max(14, fontSize - 2) })}>
            <Icon name="minus" size={16}/>
          </button>
          <span aria-live="polite">{t("\uAC00")}<small>{fontSize}</small>
          </span>
          <button className="icon-button" aria-label={t("\uAE00\uC790 \uD06C\uAC8C")} disabled={fontSize >= 36} onClick={() => updatePreferences({ fontSize: Math.min(36, fontSize + 2) })}>
            <Icon name="plus" size={16}/>
          </button>
        </div>
        <nav className="reader-navigation" aria-label={t("\uCC45 \uD398\uC774\uC9C0")}>
          <button className="button-quiet" onClick={previous} aria-label={t("\uC774\uC804 \uD398\uC774\uC9C0")} disabled={page === 0}>
            <Icon name="back"/>
            <span>{t("\uC774\uC804")}</span>
          </button>
          <span className="page-count" aria-live="polite">
            {pages.length ? String(page + 1).padStart(2, "0") : "—"}{" "}
            <span className="muted">
              / {pages.length ? String(pages.length).padStart(2, "0") : "—"}
            </span>
          </span>
          <button className="button-quiet" onClick={next} aria-label={t("\uB2E4\uC74C \uD398\uC774\uC9C0")} disabled={!pages.length || page === pages.length - 1}>
            <span>{t("\uB2E4\uC74C")}</span>
            <Icon name="arrow"/>
          </button>
        </nav>
        <span className="keyboard-hint">{t("\u2190 \u2192 \uD0A4\uB85C \uD398\uC774\uC9C0 \uB118\uAE30\uAE30")}</span>
      </footer>
    </section>);
}
