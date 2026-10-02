import { useId, useState, type ReactNode } from 'react'
import { translate as t } from '../lib/locale'
import { hasLibraryFilter, normalizeLibraryFilter, type LibraryFilter } from '../lib/libraryFilter'
import './libraryFilter.css'
import { usePageKeys } from './usePageKeys'
import { useReaderPreferences } from './ReaderPreferences'

export function LibraryFilterBar({ value, onEdit, children }: { value: LibraryFilter; onEdit: () => void; children?: ReactNode }) {
  const summary = [
    value.q && t('제목: {0}', [value.q]),
    value.folder !== undefined && (value.folder ? t('폴더: {0}', [value.folder]) : t('미분류')),
    value.tag && t('태그: {0}', [value.tag]),
    value.favorite !== undefined && t(value.favorite ? '즐겨찾기만' : '즐겨찾기 제외'),
  ].filter(Boolean).join(' · ') || t('서버 서재 전체')
  return <div className="library-filter-bar">
    <span title={summary}>{summary}</span>
    {children}
    <button className="button-outline" onClick={onEdit}>{t(hasLibraryFilter(value) ? '필터 변경' : '검색 · 필터')}</button>
  </div>
}

/** Replaces the list while editing; a field step keeps controls reachable on short landscape screens. */
export function LibraryFilterPanel({ value, onApply, onBack }: { value: LibraryFilter; onApply: (value: LibraryFilter) => void; onBack: () => void }) {
  const id = useId(), [draft, setDraft] = useState(value), [step, setStep] = useState(0)
  const [folderMode, setFolderMode] = useState(value.folder === undefined ? 'all' : value.folder === '' ? 'unfiled' : 'exact')
  const [folderText, setFolderText] = useState(value.folder ?? ''), [error, setError] = useState('')
  const { preferences } = useReaderPreferences()
  const fields = folderMode === 'exact' ? ['q', 'folder', 'folder-name', 'tag', 'favorite'] as const : ['q', 'folder', 'tag', 'favorite'] as const
  const field = fields[step], previous = () => { setError(''); setStep(current => Math.max(0, current - 1)) }, next = () => { setError(''); setStep(current => Math.min(fields.length - 1, current + 1)) }
  const fieldLabel = t({ q: '책·회차 제목 검색', folder: '폴더', 'folder-name': '폴더 이름 (정확히 일치)', tag: '태그 하나 (정확히 일치)', favorite: '즐겨찾기' }[field])
  const fieldProps = { id: `${id}-${field}`, 'aria-label': fieldLabel, 'aria-invalid': !!error, 'aria-describedby': error ? `${id}-error` : undefined }
  usePageKeys(previous, next, true, preferences.pageKeys)
  function apply() {
    try {
      if (folderMode === 'exact' && !folderText.trim()) { setStep(2); throw new Error(t('폴더 이름을 입력해 주세요.')) }
      const next = normalizeLibraryFilter({ ...draft, folder: folderMode === 'all' ? undefined : folderMode === 'unfiled' ? '' : folderText })
      onApply(next)
    } catch (failure) { setError(failure instanceof Error ? t(failure.message) : t('검색 조건의 길이와 문자를 확인해 주세요.')) }
  }
  return <form className="library-filter-panel" aria-label={t('서버 서재 검색 필터')} onChange={() => setError('')} onSubmit={event => { event.preventDefault(); apply() }}>
    <div className="library-filter-fields">
      <label htmlFor={fieldProps.id}>
      <span className="library-filter-label"><span title={error || fieldLabel}>{error ? t('검색 조건을 확인해 주세요.') : fieldLabel}</span><span className="library-filter-step">{step + 1} / {fields.length}</span></span>
      {field === 'q' &&
        <input {...fieldProps} maxLength={200} value={draft.q ?? ''} onChange={event => setDraft({ ...draft, q: event.target.value })} />
      }
      {field === 'folder' &&
        <select {...fieldProps} value={folderMode} onChange={event => setFolderMode(event.target.value)}>
          <option value="all">{t('모든 폴더')}</option><option value="unfiled">{t('미분류')}</option><option value="exact">{t('폴더 이름 지정')}</option>
        </select>
      }
      {field === 'folder-name' && <input {...fieldProps} maxLength={200} value={folderText} onChange={event => setFolderText(event.target.value)} />}
      {field === 'tag' &&
        <input {...fieldProps} maxLength={60} value={draft.tag ?? ''} onChange={event => setDraft({ ...draft, tag: event.target.value.trim() ? event.target.value : undefined })} placeholder={t('비워 두면 모든 태그')} />
      }
      {field === 'favorite' &&
        <select {...fieldProps} value={draft.favorite === undefined ? 'all' : String(draft.favorite)} onChange={event => setDraft({ ...draft, favorite: event.target.value === 'all' ? undefined : event.target.value === 'true' })}>
          <option value="all">{t('전체')}</option><option value="true">{t('즐겨찾기만')}</option><option value="false">{t('즐겨찾기 제외')}</option>
        </select>
      }
      </label>
    </div>
    {error && <span id={`${id}-error`} role="alert" className="sr-only">{error}</span>}
    <div className="library-filter-actions">
      <button className="button-quiet" type="button" onClick={onBack}>{t('목록으로')}</button>
      <button className="button-outline" type="button" aria-label={t('이전 필터 항목')} onClick={previous} disabled={step === 0}>‹</button>
      <button className="button-outline" type="button" aria-label={t('다음 필터 항목')} onClick={next} disabled={step === fields.length - 1}>›</button>
      <button className="button-outline" type="button" aria-label={t('전체 보기로 초기화')} onClick={() => onApply({})}>{t('초기화')}</button>
      <button className="button-primary">{t('필터 적용')}</button>
    </div>
  </form>
}
