import { useState } from 'react'
import { translate as t } from '../lib/locale'
import { localOrganizationInput, type LocalOrganization } from '../lib/localOrganization'
import type { SavedLocalDocument } from '../lib/localDocuments'
import { AdaptiveCollection } from './AdaptiveCollection'

export function LocalLibraryOrganizer({ book, onSave, onClose }: { book: SavedLocalDocument; onSave: (organization: LocalOrganization) => Promise<void>; onClose: () => void }) {
  const [folder, setFolder] = useState(book.organization.folder), [tags, setTags] = useState(book.organization.tags.join(', ')), [favorite, setFavorite] = useState(book.organization.favorite)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  return <section className="reading-workspace"><header className="reading-tools-header"><button className="button-quiet" disabled={busy} onClick={onClose}>{t('돌아가기')}</button><strong>{book.document.bookTitle}</strong></header>
    {error && <div role="alert" className="workflow-message">{t(error)}</div>}
    <form className="reading-tools-form" onSubmit={event => { event.preventDefault(); setBusy(true); setError(''); void (async () => { try { await onSave(localOrganizationInput(folder, tags, favorite)); onClose() } catch (error) { setError(error instanceof Error ? error.message : '서재 분류를 저장하지 못했습니다.') } finally { setBusy(false) } })() }}>
      <AdaptiveCollection items={['folder', 'tags', 'favorite']} itemKey={item => item} rowHeight={128} renderItem={field => <div className="reading-field">
        {field === 'folder' ? <><label htmlFor="local-folder">{t('폴더')}</label><input id="local-folder" value={folder} maxLength={200} disabled={busy} onChange={event => setFolder(event.target.value)}/></>
          : field === 'tags' ? <><label htmlFor="local-tags">{t('태그 · 쉼표로 구분')}</label><input id="local-tags" value={tags} maxLength={1984} disabled={busy} onChange={event => setTags(event.target.value)}/></>
          : <label className="reading-checkbox"><input type="checkbox" checked={favorite} disabled={busy} onChange={event => setFavorite(event.target.checked)}/>{t('즐겨찾기에 표시')}</label>}
      </div>}/><button className="button-primary" type="submit" disabled={busy}>{t(busy ? '저장 중…' : '분류 저장')}</button>
    </form>
  </section>
}
