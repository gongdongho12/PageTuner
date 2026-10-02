import { ApiError } from './errors'

/** Absent means all; an explicit empty folder means unfiled. Never infer identity from these display fields. */
export interface LibraryFilter {
  q?: string
  folder?: string
  tag?: string
  favorite?: boolean
}

export function normalizeLibraryFilter(input: LibraryFilter = {}): LibraryFilter {
  const invalid = (): never => { throw new ApiError('invalid-request', '검색 조건의 길이와 문자를 확인해 주세요.') }
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(key => !['q', 'folder', 'tag', 'favorite'].includes(key))) invalid()
  const result: LibraryFilter = {}
  for (const key of ['q', 'folder', 'tag'] as const) {
    const value = input[key]
    if (value === undefined) continue
    if (typeof value !== 'string') invalid()
    const text = value.trim()
    if (text.length > (key === 'tag' ? 60 : 200) || /[\u0000-\u001f\u007f-\u009f]/.test(text)) invalid()
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff) { const next = text.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) invalid() }
      else if (code >= 0xdc00 && code <= 0xdfff) invalid()
    }
    if (key === 'tag' && !text) invalid()
    if (key !== 'q' || text) result[key] = text
  }
  if (input.favorite !== undefined) {
    if (typeof input.favorite !== 'boolean') invalid()
    result.favorite = input.favorite
  }
  return result
}

/** Appended to the existing pagination query so unfiltered request URLs remain compatible. */
export function libraryFilterQuery(input: LibraryFilter = {}): string {
  const value = normalizeLibraryFilter(input), query = new URLSearchParams()
  for (const key of ['q', 'folder', 'tag', 'favorite'] as const) if (value[key] !== undefined) query.set(key, String(value[key]))
  return query.size ? `&${query}` : ''
}

export function hasLibraryFilter(value: LibraryFilter): boolean { return !!libraryFilterQuery(value) }
