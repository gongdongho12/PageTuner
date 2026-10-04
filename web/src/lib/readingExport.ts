import type { ReadingDocument } from './readingDocument'
import type { ReadingNote } from './readingNotes'

export function readingNotesExport(document: ReadingDocument, notes: ReadingNote[], format: 'txt' | 'json'): { text: string; name: string; type: string } {
  const order = new Map(document.paragraphs.map((p, index) => [p.paragraphId, index]))
  const sorted = notes.filter(note => note.documentId === document.id).sort((a, b) =>
    (order.get(a.anchor.paragraphId) ?? Number.MAX_SAFE_INTEGER) - (order.get(b.anchor.paragraphId) ?? Number.MAX_SAFE_INTEGER) ||
    a.anchor.characterOffset - b.anchor.characterOffset || a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
  // Explicit projection excludes account namespace, binary assets, credentials and unrelated device data.
  const items = sorted.map(note => ({ id: note.id, kind: note.kind, title: note.title, text: note.text, excerpt: note.excerpt,
    anchor: { ...note.anchor }, ...(note.range ? { range: { start: { ...note.range.start }, end: { ...note.range.end } } } : {}), createdAt: note.createdAt }))
  const text = format === 'json' ? JSON.stringify({ version: 'pageturner.reading-notes.v1', documentId: document.id,
    bookTitle: document.bookTitle, chapterTitle: document.chapterTitle, items }, null, 2) : `${document.bookTitle}\n${document.chapterTitle}\n\n` +
    items.map(note => `[${note.kind}] ${note.title}\nParagraph ${(order.get(note.anchor.paragraphId) ?? 0) + 1}, offset ${note.anchor.characterOffset}\n${note.excerpt}${note.text ? `\n\n${note.text}` : ''}`).join('\n\n────────\n\n')
  if (text.length > 5_000_000) throw new Error('읽기 기록이 너무 큽니다. 일부 기록을 정리한 뒤 내보내 주세요.')
  return { text, name: `${document.bookTitle.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 120)}-notes.${format}`, type: format === 'json' ? 'application/json' : 'text/plain' }
}

export function downloadReadingExport(value: ReturnType<typeof readingNotesExport>) {
  const url = URL.createObjectURL(new Blob([value.text], { type: `${value.type};charset=utf-8` }))
  const link = document.createElement('a'); link.href = url; link.download = value.name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** Call directly from a user click; do not await storage or network before opening Web Share. */
export async function shareReadingExport(value: ReturnType<typeof readingNotesExport>): Promise<'opened' | 'cancelled'> {
  if (!navigator.share) throw new Error('이 브라우저는 공유를 지원하지 않습니다. TXT 파일로 내보내 주세요.')
  const file = new File([value.text], value.name, { type: value.type })
  const data: ShareData = navigator.canShare?.({ files: [file] }) ? { files: [file], title: value.name } : { title: value.name, text: value.text }
  try { await navigator.share(data); return 'opened' }
  catch (error) { if (error instanceof DOMException && error.name === 'AbortError') return 'cancelled'; throw new Error('공유 화면을 열지 못했습니다. TXT 파일로 내보내 주세요.') }
}
