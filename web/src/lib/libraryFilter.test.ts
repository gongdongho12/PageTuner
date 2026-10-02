import { describe, expect, it } from 'vitest'
import { libraryFilterQuery, normalizeLibraryFilter, type LibraryFilter } from './libraryFilter'

describe('whole server library filters', () => {
  it('preserves unfiled, false, exact Unicode/tag punctuation and literal query characters', () => {
    const filter = normalizeLibraryFilter({ q: ' \ufeff100%_Tea & 本 +\u3000', folder: '\u00a0 ', tag: ' 인물,별명 ', favorite: false })
    expect(filter).toEqual({ q: '100%_Tea & 本 +', folder: '', tag: '인물,별명', favorite: false })
    const query = new URLSearchParams(libraryFilterQuery(filter).slice(1))
    expect(Object.fromEntries(query)).toEqual({ q: '100%_Tea & 本 +', folder: '', tag: '인물,별명', favorite: 'false' })
    expect(libraryFilterQuery({ q: ' \ufeff\u2028' })).toBe('')
    expect(libraryFilterQuery({})).toBe('')
    expect(libraryFilterQuery({ folder: 'Shelf' })).not.toBe(libraryFilterQuery({ folder: 'shelf' }))
  })

  it('validates UTF-16 limits without normalizing exact folder/tag identities', () => {
    expect(normalizeLibraryFilter({ q: '😀'.repeat(100), folder: 'cafe\u0301', tag: '😀'.repeat(30) }).folder).toBe('cafe\u0301')
    for (const filter of [{ q: '😀'.repeat(101) }, { folder: 'a'.repeat(201) }, { tag: '😀'.repeat(31) }, { tag: '' }, { tag: '\ufeff ' },
      { q: 'a\u0000b' }, { folder: 'a\u0085b' }, { tag: '\u001c' }, { q: '\ud800' }, { folder: '\udc00' }, { tag: '\ud800x' },
      { favorite: 'false' }, { q: 7 }, { private: 'no' }, null]) {
      expect(() => normalizeLibraryFilter(filter as LibraryFilter)).toThrowError(expect.objectContaining({ kind: 'invalid-request' }))
    }
  })
})
