import { useEffect, useMemo, useRef, useState } from 'react'
import { PagedReader, type PagedReaderProps } from './PagedReader'
import { usePersonalLibrary } from './usePersonalLibrary'
import type { TranslationResponse } from '../lib/api'
import type { WorkflowClient } from '../lib/workflowApi'
import type { ReadingAnchor } from '../lib/offline'
import { findTranslationSource, pairedComparisonDocument, comparisonParagraphId, comparisonSwitchAnchor, type ComparisonMode, type MatchedComparison } from '../lib/translationComparison'
import { getWorkflowPosition, setWorkflowPosition } from '../lib/workflowPosition'
import { createReadingNotes } from '../lib/readingNotes'
import { translate as t } from '../lib/locale'
import './translationComparison.css'

export type TranslationComparisonReaderProps = PagedReaderProps & {
  translation?: TranslationResponse
  workflowClient?: Pick<WorkflowClient, 'chapter' | 'chapters'> | null
  sourceRecordId?: string
}

/** Ordinary original/preview readers keep exactly the same behavior. */
export function TranslationComparisonReader(props: TranslationComparisonReaderProps) {
  if (!props.translation || props.preview || !props.notesNamespace) return <PagedReader {...props}/>
  return <MatchingReader key={`${props.notesNamespace}:${props.translation.recordId}:${props.translation.revision}`} {...props} translation={props.translation}/>
}

function MatchingReader(props: TranslationComparisonReaderProps & { translation: TranslationResponse }) {
  const username = props.notesNamespace!, personal = usePersonalLibrary(username)
  const [mode, setMode] = useState<ComparisonMode>('translation'), [match, setMatch] = useState<MatchedComparison>()
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [savedOriginal, setSavedOriginal] = useState(false), [savingOriginal, setSavingOriginal] = useState(false)
  const positions = useRef<Partial<Record<ComparisonMode, ReadingAnchor>>>({ translation: props.anchor })
  const [viewAnchor, setViewAnchor] = useState(props.anchor)
  const [anchorIsNavigation, setAnchorIsNavigation] = useState(false)
  const request = useRef<AbortController | undefined>(undefined)
  const modeRef = useRef(mode); modeRef.current = mode
  useEffect(() => () => request.current?.abort(), [])
  const combined = useMemo(() => match ? pairedComparisonDocument(match, { original: t('원문'), translation: t('번역문') }) : undefined, [match])

  function select(next: ComparisonMode, verified: MatchedComparison) {
    const previous = modeRef.current
    const paragraphId = comparisonParagraphId(previous, positions.current[previous]) ?? verified.original.paragraphs[0]?.paragraphId
    const anchor = comparisonSwitchAnchor(verified, next, paragraphId, positions.current[next])
    positions.current[next] = anchor
    window.getSelection()?.removeAllRanges()
    setAnchorIsNavigation(true); setViewAnchor(anchor); setMode(next)
  }

  async function open(next: ComparisonMode) {
    if (busy || mode === next) return
    setError('')
    if (match) { select(next, match); return }
    if (next === 'translation') { setMode(next); return }
    const controller = new AbortController(); request.current?.abort(); request.current = controller
    setBusy(true)
    try {
      const online = typeof navigator === 'undefined' || navigator.onLine !== false
      const found = await findTranslationSource(props.translation, { personal, client: online ? props.workflowClient : null, sourceRecordId: props.sourceRecordId, signal: controller.signal })
      controller.signal.throwIfAborted()
      if (!found) { setError(!props.workflowClient || !online ? '이 기기에 대응 원문이 없습니다. 서버에 연결해 원문을 함께 보관하면 오프라인에서도 대조할 수 있습니다.' : '이 번역에 대응하는 원문이 서버나 기기에 없습니다. 번역문은 계속 읽을 수 있습니다.'); return }
      let previous = getWorkflowPosition(username, found.match.original)
      try { previous ??= await createReadingNotes(username).getPosition(found.match.original) } catch { /* Existing text remains readable when position storage is unavailable. */ }
      controller.signal.throwIfAborted()
      if (previous) positions.current.original = previous
      setMatch(found.match); setSavedOriginal(found.saved); select(next, found.match)
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '대응 원문을 불러오지 못했습니다. 번역문은 계속 읽을 수 있습니다.')
    } finally { if (request.current === controller) setBusy(false) }
  }

  async function saveOriginal() {
    if (!personal || !match || savingOriginal) return
    setSavingOriginal(true); setError('')
    try { await personal.saveOriginal(match.source); setSavedOriginal(true) }
    catch (failure) { setError(failure instanceof Error ? failure.message : '원문을 기기에 보관하지 못했습니다.') }
    finally { setSavingOriginal(false) }
  }

  const document = mode === 'translation' ? props.document : mode === 'original' ? match!.original : combined!
  function moved(anchor: ReadingAnchor) {
    positions.current[mode] = anchor
    if (mode === 'translation') props.onAnchorChange(anchor)
    if (mode === 'original' && match) {
      try { setWorkflowPosition(username, match.original, anchor) } catch { /* IndexedDB can still preserve the original position. */ }
      void createReadingNotes(username).setPosition(match.original, anchor).catch(() => setError('원문의 읽은 위치를 저장하지 못했습니다.'))
    }
  }
  return <section className="translation-comparison" aria-label={t('원문·번역 보기')}>
    <nav className="comparison-modes" aria-label={t('읽기 표시 모드')}>
      {(['translation', 'original', 'comparison'] as const).map(value => <button key={value} type="button" aria-pressed={mode === value} disabled={busy}
        onClick={() => void open(value)}>{t(value === 'translation' ? '번역문' : value === 'original' ? '원문' : '대조')}</button>)}
    </nav>
    {busy && <div className="comparison-notice" role="status">{t('대응 원문을 확인하고 있습니다…')} <button className="button-text" onClick={() => { request.current?.abort(); setBusy(false) }}>{t('취소')}</button></div>}
    {error && <div className="comparison-notice" role="alert"><span>{t(error)}</span><button className="button-text" onClick={() => setError('')}>{t('닫기')}</button></div>}
    <PagedReader {...props} key={mode} document={document} anchor={viewAnchor} anchorIsNavigation={anchorIsNavigation} onAnchorChange={moved}
      readOnly={mode === 'comparison'}
      saved={mode === 'original' ? savedOriginal : props.saved} saving={mode === 'original' ? savingOriginal : props.saving}
      onSave={mode === 'translation' ? props.onSave : mode === 'original' ? () => void saveOriginal() : undefined}
      onAction={mode === 'translation' ? props.onAction : undefined} actionLabel={mode === 'translation' ? props.actionLabel : undefined}
      editionLabel={mode === 'comparison' ? t('원문·번역 대조') : undefined}
      readerLabel={mode === 'comparison' ? t('대조 읽기') : undefined}
      contentKindLabel={mode === 'comparison' ? t('대조') : undefined}
      positionNote={mode === 'comparison' ? t('같은 번호의 원문과 번역을 이어 표시합니다. 메모·강조는 원문 또는 번역문 보기에서 사용하세요.') : mode === 'original' ?
        t(savedOriginal ? '기기에 보관한 원문입니다. 연결 없이 대조할 수 있습니다.' : '원문도 기기에 보관하면 연결 없이 대조할 수 있습니다.') : props.positionNote}/>
  </section>
}
