import { describe, expect, it } from 'vitest'
import { createReaderPreferences, defaultReaderPreferences, pageKeyDirection, readerPreferencesKey, touchPageDirection } from './readerPreferences'

function memory() {
  const values = new Map<string, string>()
  return { values, getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } }
}
describe('persisted reader preferences', () => {
  it('persists per account and merges changes against the latest saved settings', () => {
    const storage = memory(), first = createReaderPreferences('alice', storage), second = createReaderPreferences('alice', storage)
    expect(first.load()).toEqual(defaultReaderPreferences)
    first.update({ fontSize: 28, lineHeight: 1.5, pageMargin: 12 }); second.update({ listMode: 'scroll', touchDirection: 'left-next' })
    expect(createReaderPreferences('alice', storage).load()).toMatchObject({ fontSize: 28, lineHeight: 1.5, pageMargin: 12, listMode: 'scroll', touchDirection: 'left-next' })
    expect(createReaderPreferences('bob', storage).load()).toEqual(defaultReaderPreferences)
    expect(readerPreferencesKey('')).not.toBe(readerPreferencesKey('guest'))
    expect(readerPreferencesKey('a:b')).not.toBe(readerPreferencesKey('a%3Ab'))
  })
  it('keeps previous settings on invalid updates or failed disk writes and allows explicit corrupt reset', () => {
    const storage = memory(), store = createReaderPreferences('alice', storage)
    store.update({ fontFamily: 'sans' }); expect(() => store.update({ fontSize: 100 })).toThrow('독서 설정')
    expect(store.load().fontFamily).toBe('sans')
    const denied = createReaderPreferences('alice', { getItem: storage.getItem, setItem: () => { throw new DOMException('Denied', 'QuotaExceededError') } })
    expect(() => denied.update({ fontSize: 32 })).toThrow('Denied'); expect(store.load().fontSize).toBe(20)
    storage.values.set(store.key, '{bad json'); expect(() => store.load()).toThrow('초기화')
    store.reset(); expect(store.load()).toEqual(defaultReaderPreferences)
  })
  it('maps only configured keys and the two outer tap zones', () => {
    expect(pageKeyDirection('ArrowLeft', '', false, 'normal')).toBe(-1)
    expect(pageKeyDirection('PageDown', '', false, 'reversed')).toBe(-1)
    expect(pageKeyDirection('', 'Space', true, 'normal')).toBe(-1)
    expect(pageKeyDirection('AudioVolumeDown', '', false, 'normal')).toBe(1)
    expect(pageKeyDirection('ArrowRight', '', false, 'disabled')).toBeUndefined()
    expect(pageKeyDirection('a', '', false, 'normal')).toBeUndefined()
    expect(touchPageDirection(.1, 'left-previous')).toBe(-1)
    expect(touchPageDirection(.9, 'left-next')).toBe(-1)
    expect(touchPageDirection(.5, 'left-next')).toBeUndefined()
    expect(touchPageDirection(.1, 'buttons-only')).toBeUndefined()
  })
})
