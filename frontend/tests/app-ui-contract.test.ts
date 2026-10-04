import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { readerDefaults, readerRanges, pageKey, tapAction, fitText, partAt } from '../src/lib/app-ui-contract';
describe('app and web reader contract', () => {
  it('keeps defaults and setting ranges aligned with Android', () => {
    const native = readFileSync('../app/src/main/java/com/dongholab/pagetuner/settings/ReaderSettings.kt', 'utf8');
    expect(native).toContain(`readerFontSizeSp: Int = ${readerDefaults.fontSize}`);
    expect(native).toContain(`readerLineSpacing: Float = ${readerDefaults.lineHeight}f`);
    expect(native).toContain(`readerPageMarginDp: Int = ${readerDefaults.margin}`);
    const ui = readFileSync('../app/src/main/java/com/dongholab/pagetuner/ui/settings/ReaderSettingsUi.kt', 'utf8');
    for (const [min, max] of Object.values(readerRanges)) expect(ui).toContain(`${min}f..${max}f`);
  });
  it('preserves all text and surrogate pairs with tiny viewports', () => {
    const text = '가나다 😀 sample\n\nend';
    for (const capacity of [0, 1, 2, 5, 10]) {
      const parts = fitText(text, value => value.length <= capacity);
      expect(parts.map(p => p.text).join('')).toBe(text);
      expect(parts.every(p => !/[\uD800-\uDBFF]$/.test(p.text) && !/^[\uDC00-\uDFFF]/.test(p.text))).toBe(true);
    }
  });
  it('uses anchors across reflow and supports keyboard and explicit tap modes', () => {
    const parts = fitText('1234567890', value => value.length <= 3);
    expect(parts[partAt(parts, 7)].offset).toBe(6);
    expect(pageKey(' ', true)).toBe(-1); expect(pageKey('PageDown')).toBe(1);
    expect(tapAction(.1, 'reverse', false)).toBe(1); expect(tapAction(.9, 'buttons', true)).toBe(0);
    expect(tapAction(.5, 'buttons', true)).toBe('exit');
  });
});
