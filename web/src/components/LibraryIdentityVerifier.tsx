import { useId, useLayoutEffect, useRef, useState } from 'react'
import { translate as t } from '../lib/locale'
import { inspectPortableIdentity, type PortableIdentityCheck } from '../lib/libraryIdentity'
import { LibraryIdentityError, type LibraryIdentityClient, type LibraryIdentityResult } from '../lib/libraryIdentityApi'
import { validRecordId } from '../lib/validation'
import type { SavedExchange } from '../lib/exchangeLibrary'
import { AdaptiveCollection } from './AdaptiveCollection'
import './libraryIdentity.css'

const failure: Record<string, string> = {
  authentication: '서버 원본을 확인하려면 다시 로그인해 주세요.', forbidden: '서버 확인 권한을 확인해 주세요. 다시 로그인한 뒤 시도해 주세요.',
  'not-found': '현재 계정에서 해당 서버 문서를 찾을 수 없습니다.', mismatch: '서버 문서의 식별자·언어·revision·전체 문단이 가져온 책과 일치하지 않습니다.',
  unavailable: '서버 문서의 원본 정보를 검증할 수 없습니다. 기존 책은 계속 읽을 수 있습니다.',
  'invalid-request': '서버 문서 UUID와 원본 확인 정보를 확인해 주세요.', 'invalid-response': '서버의 확인 응답을 검증하지 못했습니다.',
  network: '서버에 연결하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.', timeout: '서버 확인 시간이 초과되었습니다. 다시 시도해 주세요.',
  server: '서버에서 원본을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}
const unsupported = {
  missing: '이 ZIP에는 공통 원본 확인 정보가 없습니다. 원본 앱이나 웹에서 새로 내보낸 파일이 필요합니다.',
  invalid: 'ZIP의 원본 확인 정보와 본문이 일치하지 않거나 지원하지 않는 형식입니다. 기존 책은 계속 읽을 수 있습니다.',
  assets: 'PDF·이미지가 있는 문서는 아직 서버 원본 확인을 지원하지 않습니다. 읽기와 ZIP 내보내기는 계속 사용할 수 있습니다.',
}
function fields(items: { label: string; value: string }[]) {
  return items.flatMap((item, index) => {
    const points = Array.from(item.value), parts: string[] = []
    for (let start = 0; start < points.length;) {
      let end = Math.min(start + 48, points.length)
      if (end < points.length && !/\s/u.test(points[end - 1]) && !/\s/u.test(points[end])) {
        let boundary = end
        while (boundary > start && !/\s/u.test(points[boundary - 1])) boundary--
        if (boundary > start && points.slice(start, boundary).some(point => !/\s/u.test(point))) end = boundary
      }
      parts.push(points.slice(start, end).join('')); start = end
    }
    return (parts.length ? parts : ['']).map((value, part) => ({ id: `${index}:${part}`, label: item.label, value }))
  })
}
const identityLabels: Record<string, string> = { version: '규격 버전', kind: '문서 종류', contentProviderId: '원본 소스 식별자', bookId: '책 식별자', chapterId: '장 식별자', sourceRevision: '원문 revision', sourceLanguage: '원문 언어', paragraphHash: '전체 문단 hash', targetLanguage: '번역 언어', translationProviderId: '번역 제공자', modelId: '모델 식별자', promptRevision: '프롬프트 revision', glossaryRevision: '용어집 revision', artifactId: '번역 식별 hash', revision: '번역 revision', payloadHash: '번역 본문 hash' }
export function LibraryIdentityVerifier({ book, username, client, onConfirm, onUnbind, onClose }: { book: SavedExchange; username: string; client: LibraryIdentityClient | null; onConfirm: (result: LibraryIdentityResult, signal: AbortSignal) => Promise<void>; onUnbind: () => void; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null), titleId = useId(), recordIdId = useId(), request = useRef<AbortController | null>(null)
  const [check, setCheck] = useState<PortableIdentityCheck>(), [recordId, setRecordId] = useState(''), [details, setDetails] = useState(false), [busy, setBusy] = useState(false), [outcome, setOutcome] = useState(''), [verified, setVerified] = useState<LibraryIdentityResult>()
  useLayoutEffect(() => { const node = dialog.current!; node.showModal(); return () => { request.current?.abort(); if (node.open) node.close() } }, [])
  useLayoutEffect(() => {
    let active = true; request.current?.abort(); setBusy(false); setVerified(undefined); setCheck(undefined); setOutcome(''); setRecordId('')
    void inspectPortableIdentity(book.document).then(value => { if (active) { setCheck(value); if (value.status === 'ready') setRecordId(value.recordIdHint ?? '') } })
    return () => { active = false; request.current?.abort() }
  }, [book, username, client])
  const ready = check?.status === 'ready' ? check : undefined
  const status = outcome || (!check ? 'ZIP 본문과 원본 확인 정보를 검증하고 있습니다.' : check.status !== 'ready' ? unsupported[check.status] : !client ? '현재 계정으로 서버에 연결한 뒤 원본을 확인해 주세요.' : 'UUID는 찾기 힌트입니다. 현재 계정의 서버 문서와 전체 내용을 대조합니다.')
  async function verify() {
    if (!ready || !client || busy || !validRecordId(recordId)) return
    request.current?.abort(); const operation = new AbortController(); request.current = operation; setBusy(true); setVerified(undefined); setOutcome('')
    try {
      const result = await client.verify(recordId, ready.identity, operation.signal)
      if (!operation.signal.aborted) { setVerified(result); setOutcome('서버 원본과 일치합니다. 연결을 선택하면 계정 기록으로 읽습니다. 기기 기록은 별도로 보존됩니다.') }
    } catch (error) { if (!operation.signal.aborted) setOutcome(error instanceof LibraryIdentityError ? failure[error.code] ?? failure.network : failure['invalid-response']) }
    finally { if (!operation.signal.aborted) { setBusy(false); request.current = null } }
  }
  const metadata = fields(details && ready ? Object.entries(ready.identity).map(([label, value]) => ({ label: t(identityLabels[label] ?? label), value: String(value) })) : [
    { label: t('확인 상태'), value: t(busy ? '서버 원본을 확인하고 있습니다.' : status) },
    { label: t('계정'), value: username }, { label: t('책 제목'), value: book.document.bookTitle }, { label: t('장 제목'), value: book.document.chapterTitle },
    { label: t('확인 범위'), value: t('원래 소스·책·장 식별자, 원문·번역 revision, 언어, 순서가 있는 전체 문단을 비교합니다.') },
    { label: t('동기화 연결'), value: t('이번 확인은 읽기 전용입니다. 읽던 위치·메모·분류·용어집은 자동으로 병합하지 않습니다.') },
  ])
  return <dialog ref={dialog} className="library-identity-dialog" aria-labelledby={titleId} onCancel={event => { event.preventDefault(); onClose() }}>
    <header className="library-identity-toolbar"><button autoFocus onClick={onClose}>{t('닫기')}</button><strong id={titleId}>{t('서버 원본 확인')}</strong><button disabled={!ready} onClick={() => setDetails(!details)}>{t(details ? '확인 화면' : '원본 정보')}</button></header>
    {!details && ready && <label className="library-identity-input" htmlFor={recordIdId}>{t('서버 문서 UUID')}<input id={recordIdId} value={recordId} maxLength={36} spellCheck={false} autoComplete="off" disabled={busy} onChange={event => { setRecordId(event.target.value); setVerified(undefined); setOutcome('') }}/></label>}
    <AdaptiveCollection key={`${details}:${busy}:${outcome}`} items={metadata} itemKey={item => item.id} rowHeight={108} renderItem={item => <div className="library-identity-field"><strong>{item.label}</strong><p>{item.value}</p></div>}/>
    {!details && <footer className="library-identity-actions"><button className="button-primary" disabled={!ready || !client || !validRecordId(recordId) || busy} onClick={() => void verify()}>{t(busy ? '확인 중…' : '이 계정의 서버 원본 확인')}</button>{verified && <button disabled={busy} onClick={() => { const operation = new AbortController(); request.current?.abort(); request.current = operation; setBusy(true); void onConfirm(verified, operation.signal).then(() => { if (!operation.signal.aborted) setOutcome('서버 연결을 저장했습니다. 계정 기록으로 읽기를 선택해 주세요.') }).catch(() => { if (!operation.signal.aborted) setOutcome('서버 연결을 저장하지 못했습니다.') }).finally(() => { if (!operation.signal.aborted) setBusy(false) }) }}>{t('이 문서를 계정 기록에 연결')}</button>}<button disabled={busy} onClick={() => { onUnbind(); setVerified(undefined); setOutcome('서버 연결을 해제했습니다. 기기 기록은 보존됩니다.') }}>{t('연결 해제')}</button></footer>}
  </dialog>
}
