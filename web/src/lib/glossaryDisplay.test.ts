import { describe, expect, it } from 'vitest'
import { applyGlossaryDisplay, type GlossaryDisplayEntry } from './glossaryDisplay'

const character: GlossaryDisplayEntry = { source: 'Qin Feng', target: '진풍', displayTerm: '주인공', kind: 'Character' }
describe('shared glossary display and source-position mapping', () => {
  it('matches shared Korean particle and emphasis examples without rewriting copulas', () => {
    const value = applyGlossaryDisplay('진풍는 아푸은(는) 왔다.', [character, { source: 'A-Pu', target: '아푸', displayTerm: '아이' }], 'translation')
    expect(value.text).toBe('주인공은 아이는 왔다.')
    expect(value.emphasizedRanges).toEqual([{ start: 0, end: 3 }, { start: 5, end: 7 }])
    expect(applyGlossaryDisplay('진풍이다.', [character], 'translation').text).toBe('주인공이다.')
    expect(applyGlossaryDisplay('Gil으로 A-Pu와', [{ source: 'Gil', target: '길', displayTerm: '길' }, { source: 'A-Pu', target: '아푸', displayTerm: '아푸' }], 'original').text).toBe('길로 아푸와')
  })
  it('selects the longest original match, respects Latin boundaries and never cascades aliases', () => {
    const entries: GlossaryDisplayEntry[] = [character, { source: 'Qin', target: '진', displayTerm: 'Qin Feng' }, { source: '주인공', target: '다른', displayTerm: 'wrong' }]
    expect(applyGlossaryDisplay('Qin Feng and Qin but not Qinling.', entries, 'original').text).toBe('주인공 and Qin Feng but not Qinling.')
    expect(applyGlossaryDisplay('QIN FENG', [character], 'original').text).toBe('주인공')
    expect(applyGlossaryDisplay('QIN FENG', [{ ...character, caseSensitive: true }], 'original').text).toBe('QIN FENG')
    expect(applyGlossaryDisplay('Qin Feng', [{ ...character, enabled: false }], 'original').text).toBe('Qin Feng')
  })
  it('emphasizes translated character fallback but leaves ordinary terms and original no-alias text unchanged', () => {
    const entries: GlossaryDisplayEntry[] = [{ source: 'A-Pu', target: '아푸' }, { source: 'hall', target: '전당', kind: 'Place' }]
    const translated = applyGlossaryDisplay('아푸 entered 전당.', entries, 'translation')
    expect(translated.text).toBe('아푸 entered 전당.'); expect(translated.emphasizedRanges).toEqual([{ start: 0, end: 2 }])
    expect(translated.sourceOffset(1)).toBe(1) // Identical replacement preserves exact positions.
    expect(applyGlossaryDisplay('A-Pu', entries, 'original')).toMatchObject({ text: 'A-Pu', emphasizedRanges: [] })
  })
  it('maps expanded aliases, consumed particles and untouched UTF-16 emoji offsets in both directions', () => {
    const source = '😀 진풍는 왔다.'
    const value = applyGlossaryDisplay(source, [character], 'translation')
    expect(value.text).toBe('😀 주인공은 왔다.')
    expect(value.sourceOffset(1)).toBe(1)
    expect(value.sourceOffset(3)).toBe(3); expect(value.sourceOffset(4)).toBe(3); expect(value.sourceOffset(6)).toBe(5)
    expect(value.displayOffset(4)).toBe(3); expect(value.displayOffset(5)).toBe(6)
    expect(value.sourceOffset(value.text.length)).toBe(source.length)
    expect(value.displayOffset(source.length)).toBe(value.text.length)
    const particles = applyGlossaryDisplay('아푸은(는) 끝', [{ source: 'A-Pu', target: '아푸', displayTerm: '아이' }], 'translation')
    expect(particles.text).toBe('아이는 끝'); expect(particles.sourceOffset(3)).toBe(6); expect(particles.displayOffset(6)).toBe(3)
    expect(() => value.sourceOffset(-1)).toThrow('문자 위치')
  })
  it('uses explicit shared IDs for duplicate translated spelling ties and literal regex symbols', () => {
    const entries: GlossaryDisplayEntry[] = [{ id: 'b', source: 'aa', target: '이름', displayTerm: '후순위' }, { id: 'a', source: 'bb', target: '이름', displayTerm: '우선' }]
    expect(applyGlossaryDisplay('이름', entries, 'translation').text).toBe('우선')
    expect(applyGlossaryDisplay('A+B and AAAB', [{ source: 'A+B', target: '더하기', displayTerm: '합' }], 'original').text).toBe('합 and AAAB')
    const empty = applyGlossaryDisplay('', [], 'original'); expect(empty.sourceOffset(0)).toBe(0); expect(empty.displayOffset(0)).toBe(0)
  })
})
