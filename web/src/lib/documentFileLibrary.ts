import { createLocalDocuments } from './localDocuments'
import { createOfflineLibrary } from './offline'
import { createPersonalLibrary } from './personalLibrary'
import { createExchangeLibrary, exchangeReadingDocument } from './exchangeLibrary'
import { translationReadingDocument } from './translationReading'
import type { ReadingDocument } from './readingDocument'

export type DocumentFileChoice = { key: string; label: string; document: ReadingDocument }

/** Reads only verified, locally stored bodies. Exporting prose never opens a sync controller or reads notes. */
export async function documentFileChoices(username: string): Promise<{ choices: DocumentFileChoice[]; damaged: number; unavailable: string[] }> {
  const originals = createPersonalLibrary(username), translations = createOfflineLibrary(username)
  try {
    const results = await Promise.allSettled([
      createLocalDocuments(username).list(), originals.originals(), translations.list(), createExchangeLibrary(username).list(),
    ])
    const labels = ['로컬 파일', '원문 보관', '번역 보관', '가져온 책']
    const local = results[0].status === 'fulfilled' ? results[0].value : { books: [], damagedIds: [] }
    const source = results[1].status === 'fulfilled' ? results[1].value : { books: [], damagedIds: [] }
    const translated = results[2].status === 'fulfilled' ? results[2].value : { books: [], corruptRecords: [] }
    const portable = results[3].status === 'fulfilled' ? results[3].value : []
    return {
      unavailable: results.flatMap((result, index) => result.status === 'rejected' ? [labels[index]] : []),
      damaged: local.damagedIds.length + source.damagedIds.length + translated.corruptRecords.length,
      choices: [
        ...local.books.map(book => ({ key: `local:${book.document.id}`, label: '로컬 파일', document: book.document })),
        ...source.books.map(({ chapter }) => ({ key: `original:${chapter.recordId}`, label: '원문 보관', document: {
          id: `original:${chapter.recordId}:${chapter.sourceRevision}`, kind: 'original' as const,
          bookTitle: chapter.bookTitle, chapterTitle: chapter.chapterTitle, language: chapter.sourceLanguage, paragraphs: chapter.paragraphs,
        } })),
        ...translated.books.map(book => ({ key: `translation:${book.translation.recordId}`, label: '번역 보관', document: translationReadingDocument(book.translation) })),
        ...portable.map(book => ({ key: book.id, label: '가져온 책', document: exchangeReadingDocument(book) })),
      ],
    }
  } finally { originals.close(); translations.close() }
}
