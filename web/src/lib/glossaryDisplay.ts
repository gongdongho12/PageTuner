import { kotlinTrim } from './validation'
import type { GlossaryEntry } from './glossary'

export type GlossaryDisplayEntry = Pick<GlossaryEntry, 'source' | 'target'> & Partial<Omit<GlossaryEntry, 'source' | 'target'>> & { id?: string }
export type GlossaryDisplay = {
  text: string
  /** Exclusive-end UTF-16 ranges, unlike Kotlin's inclusive IntRange. */
  emphasizedRanges: { start: number; end: number }[]
  sourceOffset(displayOffset: number, edge?: 'start' | 'end'): number
  displayOffset(sourceOffset: number, edge?: 'start' | 'end'): number
}
type Piece = { sourceStart: number; sourceEnd: number; displayStart: number; displayEnd: number; changed: boolean }
type Match = { start: number; end: number; replacement: string; emphasize: boolean }
const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const latinOrDigit = (char: string) => /[A-Za-z\p{Nd}]/u.test(char)
const particleRegex = /(?:은\(는\)|는\(은\)|은\/는|는\/은|이\(가\)|가\(이\)|이\/가|가\/이|을\(를\)|를\(을\)|을\/를|를\/을|과\(와\)|와\(과\)|과\/와|와\/과|\(으\)로|으로\/로|로\/으로|(?:으로|은|는|이|가|을|를|과|와|로)(?![가-힣]))/y
function particle(text: string, at: number, replacement: string): { text: string; end: number } | undefined {
  particleRegex.lastIndex = at
  const match = particleRegex.exec(text)
  if (!match) return undefined
  let lastHangul = -1
  for (let index = replacement.length - 1; index >= 0; index--) {
    const code = replacement.charCodeAt(index)
    if (code >= 0xac00 && code <= 0xd7a3) { lastHangul = code; break }
  }
  if (lastHangul < 0) return undefined
  const ending = (lastHangul - 0xac00) % 28, raw = match[0]
  const corrected = ['은(는)', '는(은)', '은/는', '는/은', '은', '는'].includes(raw) ? ending === 0 ? '는' : '은'
    : ['이(가)', '가(이)', '이/가', '가/이', '이', '가'].includes(raw) ? ending === 0 ? '가' : '이'
    : ['을(를)', '를(을)', '을/를', '를/을', '을', '를'].includes(raw) ? ending === 0 ? '를' : '을'
    : ['과(와)', '와(과)', '과/와', '와/과', '과', '와'].includes(raw) ? ending === 0 ? '와' : '과'
    : ending === 0 || ending === 8 ? '로' : '으로'
  return { text: corrected, end: at + raw.length }
}

/** Mirrors shared GlossaryTextProcessor display rules; original stored text and hashes remain unchanged.
 * Supply server-derived IDs for deterministic ties between entries with the same translated spelling.
 * Reading positions inside changed replacements map to the original match start. Selection ends
 * expand to the match end, so selecting part of an alias still captures the complete original term.
 */
export function applyGlossaryDisplay(text: string, entries: GlossaryDisplayEntry[], mode: 'original' | 'translation'): GlossaryDisplay {
  const active = entries.map((entry, index) => ({ ...entry, index, source: kotlinTrim(entry.source), target: kotlinTrim(entry.target), displayTerm: kotlinTrim(entry.displayTerm ?? '') }))
    .filter(entry => entry.enabled !== false && entry.source && entry.target)
    .sort((a, b) => b.source.length - a.source.length || (a.id && b.id ? a.id < b.id ? -1 : a.id > b.id ? 1 : 0 : a.index - b.index))
  const candidates: Match[] = []
  for (const entry of active) {
    const source = mode === 'original' ? entry.source : entry.target
    const character = (entry.kind ?? 'Character') === 'Character'
    const replacement = entry.displayTerm || (mode === 'translation' && character ? entry.target : '')
    if (!replacement) continue
    const pattern = latinOrDigit(source[0]) && latinOrDigit(source[source.length - 1]) ? `(?<![A-Za-z0-9_])${escape(source)}(?![A-Za-z0-9_])` : escape(source)
    const regex = new RegExp(pattern, entry.caseSensitive ? 'gu' : 'giu')
    for (const match of text.matchAll(regex)) {
      candidates.push({ start: match.index, end: match.index + match[0].length, replacement, emphasize: character })
      if (candidates.length > 100_000) throw new Error('표시할 용어가 너무 많습니다. 용어집의 표시 별칭을 줄여 주세요.')
    }
  }
  candidates.sort((a, b) => a.start - b.start || (b.end - b.start) - (a.end - a.start))
  const selected: Match[] = []; let occupiedUntil = 0
  for (const match of candidates) if (match.start >= occupiedUntil) { selected.push(match); occupiedUntil = match.end }

  const output: string[] = [], pieces: Piece[] = [], emphasizedRanges: GlossaryDisplay['emphasizedRanges'] = []
  let sourceAt = 0, displayAt = 0
  function append(value: string, start: number, end: number) {
    if (!value.length && start === end) return
    if (displayAt + value.length > Math.max(text.length, 2_000_000)) throw new Error('표시 별칭으로 본문이 너무 길어졌습니다. 더 짧은 별칭을 사용해 주세요.')
    pieces.push({ sourceStart: start, sourceEnd: end, displayStart: displayAt, displayEnd: displayAt + value.length, changed: value !== text.slice(start, end) })
    output.push(value); displayAt += value.length
  }
  for (const match of selected) {
    if (match.start < sourceAt) continue // A preceding alias may also consume its following Korean particle.
    append(text.slice(sourceAt, match.start), sourceAt, match.start)
    const start = displayAt
    append(match.replacement, match.start, match.end)
    if (match.emphasize) emphasizedRanges.push({ start, end: displayAt })
    const corrected = particle(text, match.end, match.replacement)
    if (corrected) { append(corrected.text, match.end, corrected.end); sourceAt = corrected.end }
    else sourceAt = match.end
  }
  append(text.slice(sourceAt), sourceAt, text.length)
  function offset(value: number, from: 'source' | 'display', edge: 'start' | 'end'): number {
    const limit = from === 'source' ? text.length : displayAt
    if (!Number.isSafeInteger(value) || value < 0 || value > limit) throw new Error('본문 문자 위치가 올바르지 않습니다.')
    if (value === limit) return from === 'source' ? displayAt : text.length
    let low = 0, high = pieces.length - 1
    while (low < high) {
      const middle = (low + high) >>> 1, end = from === 'source' ? pieces[middle].sourceEnd : pieces[middle].displayEnd
      if (value < end) high = middle; else low = middle + 1
    }
    const piece = pieces[low]
    if (!piece) return value
    const begin = from === 'source' ? piece.sourceStart : piece.displayStart
    const other = from === 'source' ? piece.displayStart : piece.sourceStart
    if (piece.changed && edge === 'end' && value > begin) return from === 'source' ? piece.displayEnd : piece.sourceEnd
    return other + (piece.changed ? 0 : value - begin)
  }
  return { text: output.join(''), emphasizedRanges, sourceOffset: (value, edge = 'start') => offset(value, 'display', edge), displayOffset: (value, edge = 'start') => offset(value, 'source', edge) }
}
