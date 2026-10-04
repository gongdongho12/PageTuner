import type { SavedLocalDocument } from './localDocuments'

export type LocalOrganization = { folder: string; tags: string[]; favorite: boolean }
export const emptyLocalOrganization = (): LocalOrganization => ({ folder: '', tags: [], favorite: false })
const folded = (text: string) => text.normalize('NFKC').toLowerCase()

export function validateLocalOrganization(value: unknown): LocalOrganization {
  const input = value as LocalOrganization
  if (!input || typeof input.folder !== 'string' || input.folder.trim().length > 200 || !Array.isArray(input.tags) || input.tags.length > 32 ||
      input.tags.some(tag => typeof tag !== 'string' || !tag.trim() || tag.trim().length > 60) || typeof input.favorite !== 'boolean') throw new Error('폴더는 200자, 태그는 각 60자·32개 이하로 입력해 주세요.')
  const seen = new Set<string>(), tags: string[] = []
  for (const tag of input.tags) { const clean = tag.trim(), key = folded(clean); if (!seen.has(key)) { seen.add(key); tags.push(clean) } }
  return { folder: input.folder.trim(), tags, favorite: input.favorite }
}
export function localOrganizationInput(folder: string, tags: string, favorite: boolean): LocalOrganization {
  return validateLocalOrganization({ folder, tags: tags.split(/[,\n]/).map(tag => tag.trim()).filter(Boolean), favorite })
}

export type LocalLibraryFilter = { query: string; folder: string; favoritesOnly: boolean; sort: 'recent' | 'title' | 'folder' }
export const emptyLocalLibraryFilter = (): LocalLibraryFilter => ({ query: '', folder: '', favoritesOnly: false, sort: 'recent' })
export function filterLocalDocuments(books: SavedLocalDocument[], filter: LocalLibraryFilter): SavedLocalDocument[] {
  const query = folded(filter.query.trim()), folder = folded(filter.folder.trim())
  return books.filter(book => (!filter.favoritesOnly || book.organization.favorite) && (!folder || folded(book.organization.folder) === folder) &&
    (!query || [book.document.bookTitle, book.document.local.format, book.organization.folder, ...book.organization.tags].some(value => folded(value).includes(query))))
    .sort((a, b) => {
      const compare = filter.sort === 'recent' ? b.savedAt.localeCompare(a.savedAt) : filter.sort === 'folder' ?
        a.organization.folder.localeCompare(b.organization.folder) || a.document.bookTitle.localeCompare(b.document.bookTitle) : a.document.bookTitle.localeCompare(b.document.bookTitle)
      return compare || a.document.id.localeCompare(b.document.id)
    })
}
export function localDocumentFolders(books: SavedLocalDocument[]): string[] {
  const values = new Map<string, string>()
  for (const book of books) { const folder = book.organization.folder; if (folder) values.set(folded(folder), folder) }
  return [...values.values()].sort((a, b) => a.localeCompare(b))
}
