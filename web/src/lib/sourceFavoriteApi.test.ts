import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import { createSourceFavoriteClient, validateSourceBookIdentity, validateSourceFavoriteBook, validateSourceFavoritePage, validateSourceFavoriteItem, validateSourceFavoriteMutation, type SourceFavoriteMutation } from './sourceFavoriteApi'
const identity = { providerId: 'wtr-lab', bookId: 'https://wtr-lab.com/en/novel/42/example' }
const book = { title: 'Example', authors: ['Author'], language: 'en', url: identity.bookId }
const mutation: SourceFavoriteMutation = { ...identity, expectedVersion: 0, mutationId: 'aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa', deleted: false, book }
const item = { ...identity, version: 1, changeRevision: 1, deleted: false, book, updatedAt: '2026-10-02T12:00:00Z' }
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } })
describe('source favorite contract validation and transport', () => {
  it('accepts the shared Android/server exact identity contract fixtures', () => {
    const fixture = (name: string) => JSON.parse(readFileSync(new URL(`../../../contracts/fixtures/source-book-favorites-v1/${name}.json`, import.meta.url), 'utf8'))
    const request = validateSourceFavoriteMutation(fixture('request')), current = validateSourceFavoriteItem(fixture('item')), page = validateSourceFavoritePage(fixture('changes'), { afterRevision: 0 })
    expect(current.bookId).toBe(request.bookId); expect(current.bookId).toBe('https://wtr-lab.com/en/novel/42/river'); expect(page.items[0]).toEqual(current)
  })
  it('keeps source identity exact including punctuation and rejects invalid canonical metadata', () => {
    expect(validateSourceBookIdentity({ providerId: 'a:b', bookId: 'C:d/%2F' })).toEqual({ providerId: 'a:b', bookId: 'C:d/%2F' })
    for (const id of [' original ', 'x'.repeat(501), '\ud800', 'a\u0000']) expect(() => validateSourceBookIdentity({ ...identity, bookId: id })).toThrow()
    expect(() => validateSourceFavoriteBook({ ...book, title: ' title ' })).toThrow()
    expect(() => validateSourceFavoriteBook({ ...book, authors: Array(21).fill('author') })).toThrow()
  })
  it('accepts @ in URL paths but rejects credentials, repaired, unicode and malformed encoded URLs', () => {
    expect(validateSourceFavoriteBook({ ...book, url: 'https://example.com/a@b?q=%E3%81%82' }).url).toContain('a@b')
    expect(validateSourceFavoriteBook({ ...book, url: 'https://example.com/a?q=[a]' }).url).toContain('[a]')
    for (const url of ['https://u:p@example.com/a', 'https://example.com/あ', 'https://example.com/a%xx', 'https://example.com\\a', 'https:example.com/a', 'javascript:alert(1)', 'https://例.com/a', 'https://%65xample.com/a', 'https://exa_mple.com/a', 'https://127.1/a', 'https://example.com/[a]', 'https://example.com/a|b', 'https://example.com/a^b', 'https://example.com/{a}']) expect(() => validateSourceFavoriteBook({ ...book, url })).toThrow()
  })
  it('matches the shared URI host subset instead of accepting numeric URL repairs', () => {
    for (const url of ['http://localhost:8080/a', 'https://EXAMPLE.COM./a', 'https://127.0.0.1/a', 'https://255.255.255.255/a',
      'https://[::1]/a', 'https://[2001:db8::1]:443/a', `https://${Array(3).fill('a'.repeat(63)).join('.')}.${'a'.repeat(61)}/a`]) {
      expect(validateSourceFavoriteBook({ ...book, url }).url).toBe(url)
    }
    for (const url of ['https://192.168.001.008/a', 'https://192.168.001.007/a', 'https://4294967296/a', 'https://127/a',
      'https://0x100000000/a', 'https://0x7f000001/a', 'https://example.0x7f/a', 'https://1.2.3.256/a',
      'https://127.0.0.1./a', 'https://[::ffff:192.168.1.1]/a', `https://${'a'.repeat(64)}.test/a`,
      `https://${Array(4).fill('a'.repeat(63)).join('.')}/a`]) expect(() => validateSourceFavoriteBook({ ...book, url }), url).toThrow()
  })
  it('rejects missing or regressing feed cursors, duplicate revisions, and changed watermarks', () => {
    const good = { items: [item], nextAfterRevision: 1, watermark: 1, hasMore: false }
    expect(validateSourceFavoritePage(good, { afterRevision: 0 }).items).toHaveLength(1)
    expect(() => validateSourceFavoritePage({ ...good, items: [item, item] }, { afterRevision: 0 })).toThrow()
    expect(() => validateSourceFavoritePage({ ...good, nextAfterRevision: 0 }, { afterRevision: 0 })).toThrow()
    expect(() => validateSourceFavoritePage(good, { afterRevision: 0, untilRevision: 2 })).toThrow()
    expect(() => validateSourceFavoritePage({ ...good, hasMore: true }, { afterRevision: 0 })).toThrow()
  })
  it('protects PUT with CSRF, validates the ACK identity and uses no-store same-origin requests', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ headerName: 'X-CSRF-TOKEN', token: 'token' })).mockResolvedValueOnce(response(item))
    const client = createSourceFavoriteClient({ username: 'alice', password: 'test' }, { fetch }); expect(await client.put(mutation)).toEqual(item)
    expect(fetch.mock.calls[1][0]).toBe('/api/v1/source-book-favorites'); expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'PUT', cache: 'no-store', mode: 'same-origin', redirect: 'error', headers: { 'X-CSRF-TOKEN': 'token' } }); client.close()
    const bad = createSourceFavoriteClient({ username: 'alice', password: 'test' }, { fetch: vi.fn().mockResolvedValueOnce(response({ headerName: 'X-CSRF-TOKEN', token: 'token' })).mockResolvedValueOnce(response({ ...item, bookId: 'other' })) }); await expect(bad.put(mutation)).rejects.toMatchObject({ code: 'invalid-response' }); bad.close()
  })
  it('rejects a conflict belonging to a different source book', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ headerName: 'X-CSRF-TOKEN', token: 'token' })).mockResolvedValueOnce(response({ code: 'SOURCE_BOOK_FAVORITE_CONFLICT', current: { ...item, providerId: 'other' } }, 409))
    const client = createSourceFavoriteClient({ username: 'alice', password: 'test' }, { fetch }); await expect(client.put(mutation)).rejects.toMatchObject({ code: 'invalid-response' }); client.close()
  })
})
