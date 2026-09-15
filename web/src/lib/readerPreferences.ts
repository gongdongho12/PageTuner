export type ReaderPreferences = {
  fontSize: number
  fontFamily: 'serif' | 'sans' | 'mono'
  lineHeight: number
  pageMargin: number
  pageKeys: 'normal' | 'reversed' | 'disabled'
  touchDirection: 'left-previous' | 'left-next' | 'buttons-only'
  listMode: 'paged' | 'scroll'
}
export const defaultReaderPreferences: ReaderPreferences = Object.freeze({ fontSize: 20, fontFamily: 'serif', lineHeight: 1.95, pageMargin: 28, pageKeys: 'normal', touchDirection: 'left-previous', listMode: 'paged' })
type PreferenceStorage = Pick<Storage, 'getItem' | 'setItem'>
export const readerPreferencesKey = (namespace: string) => `pageturner.reader-preferences.v1:${namespace.trim() ? `account:${encodeURIComponent(namespace.trim())}` : 'guest'}`

export function validateReaderPreferences(value: unknown): ReaderPreferences {
  const input = value as ReaderPreferences
  if (!input || !Number.isInteger(input.fontSize) || input.fontSize < 14 || input.fontSize > 36 ||
      !['serif', 'sans', 'mono'].includes(input.fontFamily) || !Number.isFinite(input.lineHeight) || input.lineHeight < 1.2 || input.lineHeight > 2.4 ||
      !Number.isInteger(input.pageMargin) || input.pageMargin < 0 || input.pageMargin > 48 ||
      !['normal', 'reversed', 'disabled'].includes(input.pageKeys) || !['left-previous', 'left-next', 'buttons-only'].includes(input.touchDirection) ||
      !['paged', 'scroll'].includes(input.listMode)) throw new Error('저장된 독서 설정을 확인할 수 없습니다. 기본값으로 초기화해 주세요.')
  return { fontSize: input.fontSize, fontFamily: input.fontFamily, lineHeight: input.lineHeight, pageMargin: input.pageMargin,
    pageKeys: input.pageKeys, touchDirection: input.touchDirection, listMode: input.listMode }
}

export function createReaderPreferences(namespace: string, storage: PreferenceStorage) {
  const key = readerPreferencesKey(namespace)
  const load = () => {
    const raw = storage.getItem(key)
    if (!raw) return { ...defaultReaderPreferences }
    try { return validateReaderPreferences(JSON.parse(raw)) } catch { throw new Error('저장된 독서 설정을 확인할 수 없습니다. 기본값으로 초기화해 주세요.') }
  }
  return {
    key, load,
    update(patch: Partial<ReaderPreferences>): ReaderPreferences {
      const next = validateReaderPreferences({ ...load(), ...patch })
      storage.setItem(key, JSON.stringify(next)); return next
    },
    reset(): ReaderPreferences {
      const next = { ...defaultReaderPreferences }; storage.setItem(key, JSON.stringify(next)); return next
    },
  }
}

export function pageKeyDirection(key: string, code: string, shift: boolean, mode: ReaderPreferences['pageKeys']): -1 | 1 | undefined {
  if (mode === 'disabled') return undefined
  const direction = ['ArrowLeft', 'PageUp', 'AudioVolumeUp'].includes(key) || (code === 'Space' && shift) ? -1 :
    ['ArrowRight', 'PageDown', 'AudioVolumeDown'].includes(key) || code === 'Space' ? 1 : undefined
  return direction === undefined ? undefined : mode === 'reversed' ? direction === -1 ? 1 : -1 : direction
}

export function touchPageDirection(fraction: number, direction: ReaderPreferences['touchDirection']): -1 | 1 | undefined {
  if (direction === 'buttons-only' || fraction < 0 || fraction > 1 || (fraction >= 0.4 && fraction <= 0.6)) return undefined
  const previous = fraction < 0.4
  return (direction === 'left-previous' ? previous : !previous) ? -1 : 1
}

export const readerFontFamilies: Record<ReaderPreferences['fontFamily'], string> = {
  serif: 'Georgia, Batang, AppleMyungjo, serif', sans: 'Arial, Malgun Gothic, Apple SD Gothic Neo, sans-serif', mono: 'Consolas, Menlo, monospace',
}
