import { useEffect, useState } from 'react'
import { translate as t } from '../lib/locale'
import type { ReadingDocument } from '../lib/readingDocument'
import { validateLibraryOrganization, type LibraryOrganization } from '../lib/libraryOrganizationApi'
import type { LibraryOrganizationChoice } from '../lib/libraryOrganizationSync'
import { useLibraryOrganization } from './LibraryOrganizationProvider'
import { AdaptiveCollection } from './AdaptiveCollection'
import './readingTools.css'

const errors: Record<string, string> = {
  authentication: '문서 분류를 동기화하려면 다시 로그인해 주세요.',
  forbidden: '문서 분류를 저장할 권한을 확인할 수 없습니다.',
  'not-found': '서버 문서가 없거나 이 계정의 문서가 아닙니다. 기기의 분류는 유지됩니다.',
  storage: '문서 분류를 기기에 보관하지 못했습니다. 저장 공간을 확인해 주세요.',
  'invalid-response': '서버의 문서 분류 응답을 확인할 수 없습니다.',
  'invalid-request': '폴더와 태그의 길이·중복·문자를 확인해 주세요.',
  'mutation-reused': '문서 분류 변경 요청을 확인할 수 없습니다.',
  exhausted: '문서 분류의 저장 한도에 도달했습니다.',
  'choice-stale': '분류가 바뀌었습니다. 최신 내용을 확인한 뒤 다시 선택해 주세요.',
  limit: '동기화 요청이 많습니다. 잠시 후 다시 시도해 주세요.',
  network: '연결되면 문서 분류를 전송합니다. 변경 사항은 기기에 남아 있습니다.',
  timeout: '연결되면 문서 분류를 전송합니다. 변경 사항은 기기에 남아 있습니다.',
  server: '서버에서 문서 분류를 저장하지 못했습니다. 다시 시도해 주세요.',
}
function summary(value: LibraryOrganization) {
  return [t('폴더: {0}', [value.folder || t('없음')]), t('태그: {0}', [value.tags.join(' · ') || t('없음')]), t(value.favorite ? '즐겨찾기 표시' : '즐겨찾기 해제')]
}
export function LibraryOrganizationPanel({ namespace, document, onClose }: { namespace: string; document: ReadingDocument; onClose: () => void }) {
  const sync = useLibraryOrganization(namespace, document.serverProgress), state = sync.state
  const [tab, setTab] = useState<'edit' | 'sync'>('edit')
  const [folder, setFolder] = useState(''), [tags, setTags] = useState(''), [favorite, setFavorite] = useState(false)
  const [dirty, setDirty] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState('')
  const [baseline, setBaseline] = useState<LibraryOrganizationChoice>()
  const [inspecting, setInspecting] = useState<LibraryOrganization>()
  function changed() {
    if (!dirty && state) setBaseline({ local: structuredClone(state.local), remote: structuredClone(state.remote) })
    setDirty(true)
  }
  useEffect(() => {
    if (state && !dirty) { setFolder(state.local.folder); setTags(state.local.tags.join('\n')); setFavorite(state.local.favorite) }
  }, [state?.local, dirty])
  const choosing = state && (!state.enabled || state.status === 'conflict')
  const status = !sync.available ? '계정에 연결된 서버 문서에서 분류를 사용할 수 있습니다.' : !state || state.status === 'loading' ? '문서 분류를 확인하고 있습니다.' :
    state.errorCode ? (errors[state.errorCode] ?? '문서 분류를 동기화하지 못했습니다.') : state.status === 'conflict' ? '다른 기기에서 분류를 변경했습니다. 사용할 값을 선택해 주세요.' :
    !state.enabled ? '먼저 사용할 분류를 선택해 주세요. 선택 전에는 서버에 올리지 않습니다.' : state.status === 'pending' ? '문서 분류 · 저장 대기' : '문서 분류 · 동기화됨'
  async function save() {
    if (!sync.controller || !state) return
    setBusy(true); setError('')
    try {
      // One tag per line preserves literal commas in existing server tags.
      const value = validateLibraryOrganization({ folder: folder.trim(), tags: tags.split(/\r?\n/).map(tag => tag.trim()).filter(Boolean), favorite })
      const accepted = await sync.controller.update(value, baseline)
      if (!accepted) { setError(errors[sync.controller.snapshot().errorCode ?? 'storage'] ?? '문서 분류를 동기화하지 못했습니다.'); return }
      setDirty(false)
      if (!state.enabled || state.status === 'conflict') setTab('sync')
    } catch { setError('폴더는 200자, 태그는 줄마다 60자·32개까지 입력하며 중복과 제어 문자를 제외해 주세요.') }
    finally { setBusy(false) }
  }
  if (inspecting) {
    const parts = inspecting.folder.match(/[\s\S]{1,64}/gu) ?? ['']
    const rows = [...parts.map((value, index) => ({ id: `folder:${index}`, label: t('폴더'), value: value || t('없음') })),
      ...inspecting.tags.map((value, index) => ({ id: `tag:${index}`, label: t('태그'), value })),
      { id: 'favorite', label: t('즐겨찾기'), value: t(inspecting.favorite ? '즐겨찾기 표시' : '즐겨찾기 해제') }]
    return <section className="reading-workspace" aria-label={t('분류 내용')}><header className="reading-tools-header"><button className="button-quiet" onClick={() => setInspecting(undefined)}>{t('돌아가기')}</button><strong>{t('분류 내용')}</strong></header>
      <AdaptiveCollection mode="paged" items={rows} itemKey={row => row.id} rowHeight={136} renderItem={row => <div className="reading-field library-organization-detail"><strong>{row.label}</strong><p>{row.value}</p></div>}/>
    </section>
  }
  return <section className="reading-workspace" aria-label={t('서버 문서 분류')}>
    <header className="reading-tools-header"><button className="button-quiet" disabled={busy} onClick={onClose}>{t('돌아가기')}</button><strong title={document.bookTitle}>{t('서버 문서 분류')}</strong></header>
    {tab === 'edit' && <p className="library-organization-title" title={`${document.bookTitle} · ${document.chapterTitle}`}>{document.chapterTitle || document.bookTitle}</p>}
    <nav className="workflow-subtabs" aria-label={t('문서 분류 도구')}><button aria-pressed={tab === 'edit'} onClick={() => setTab('edit')}>{t('분류 편집')}</button><button aria-pressed={tab === 'sync'} onClick={() => setTab('sync')}>{t('분류 동기화')}</button></nav>
    <p className="workflow-message" role={state?.errorCode || state?.status === 'conflict' ? 'alert' : 'status'}>{t(tab === 'sync' && dirty && !state?.errorCode && state?.status !== 'conflict' ? '편집 중인 분류를 저장한 뒤 선택해 주세요.' : status)}</p>
    {error && error !== errors[state?.errorCode ?? ''] && <p className="workflow-message" role="alert">{t(error)}</p>}
    {tab === 'edit' ? <form className="reading-tools-form" onSubmit={event => { event.preventDefault(); void save() }}>
      <AdaptiveCollection mode="paged" items={['folder', 'tags', 'favorite']} itemKey={item => item} rowHeight={112} renderItem={field => <div className="reading-field">
        {field === 'folder' ? <><label htmlFor="server-folder">{t('폴더')}</label><input id="server-folder" value={folder} maxLength={200} disabled={busy || !state || state.status === 'loading'} onChange={event => { changed(); setFolder(event.target.value) }}/></>
          : field === 'tags' ? <><label htmlFor="server-tags">{t('태그 · 한 줄에 하나')}</label><textarea id="server-tags" value={tags} rows={2} maxLength={1983} disabled={busy || !state || state.status === 'loading'} onChange={event => { changed(); setTags(event.target.value) }}/></>
            : <label className="reading-checkbox"><input type="checkbox" checked={favorite} disabled={busy || !state || state.status === 'loading'} onChange={event => { changed(); setFavorite(event.target.checked) }}/>{t('즐겨찾기에 표시')}</label>}
      </div>}/><div className="library-organization-actions"><button className="button-outline" type="button" disabled={busy || !dirty} onClick={() => { setDirty(false); setBaseline(undefined); setError(''); void sync.controller?.refresh().catch(() => undefined) }}>{t('편집 취소')}</button><button className="button-primary" type="submit" disabled={busy || !state || state.status === 'loading' || !dirty}>{t(busy ? '저장 중…' : '분류 저장')}</button></div>
    </form> : <>
      <AdaptiveCollection mode="paged" items={['local', 'server'] as const} itemKey={item => item} rowHeight={152} renderItem={side => {
        const value = side === 'local' ? state?.local : state?.remote?.organization
        return <div className="library-organization-choice"><strong>{t(side === 'local' ? '이 기기의 분류' : '서버의 분류')}</strong>
          <div>{value ? summary(value).map((line, index) => <p key={index} title={line}>{line}</p>) : <p>{t('아직 서버 분류가 없습니다.')}</p>}</div>
          <div className="library-organization-actions"><button className="button-outline" disabled={!value} onClick={() => { if (value) setInspecting(structuredClone(value)) }}>{t('내용 보기')}</button>
            {choosing && <button className="button-outline" disabled={busy || dirty || !sync.online || !state.remote || !value} onClick={() => { setError(''); void sync.controller?.choose(side, { local: state.local, remote: state.remote }).catch(() => undefined) }}>{t(side === 'local' ? '기기 분류 사용' : '서버 분류 사용')}</button>}</div>
        </div>
      }}/>
      <button className="button-outline reader-preference-sync-entry" disabled={!sync.online || !state || state.status === 'loading'} onClick={() => { void sync.controller?.refresh().catch(() => undefined) }}>{t('동기화 다시 확인')}</button>
    </>}
    {tab === 'edit' && <p className="reading-tools-caption">{t('이 서버 문서의 분류만 공유합니다. 원문과 번역본의 분류는 각각 보관됩니다.')}</p>}
  </section>
}
