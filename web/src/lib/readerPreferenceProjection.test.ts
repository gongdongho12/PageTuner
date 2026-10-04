import { describe, expect, it } from 'vitest'
import { defaultReaderPreferences, validateReaderPreferences } from './readerPreferences'
import { projectReaderPreferences, sharedReaderPreferencePatch, sharedReaderPreferences } from './readerPreferenceProjection'

describe('account reading preference projection', () => {
  it('round-trips Android values and preserves the web-only font and keyboard choices', () => {
    const device = { ...defaultReaderPreferences, fontFamily: 'mono' as const, pageKeys: 'disabled' as const }
    const shared = { fontSize: 36, lineHeightPercent: 110, pageMargin: 0, touchDirection: 'buttons-only' as const, listMode: 'paged' as const }
    const projected = validateReaderPreferences(projectReaderPreferences(device, shared))
    expect(projected).toMatchObject({ fontFamily: 'mono', pageKeys: 'disabled', fontSize: 36, lineHeight: 1.1, pageMargin: 0 })
    expect(sharedReaderPreferences(projected)).toEqual(shared)
    expect(device).toEqual({ ...defaultReaderPreferences, fontFamily: 'mono', pageKeys: 'disabled' })
  })
  it('sends only changed shared fields and uses integer percent to prevent floating-point drift', () => {
    expect(sharedReaderPreferencePatch({ fontFamily: 'sans', pageKeys: 'normal' })).toEqual({})
    expect(sharedReaderPreferencePatch({ fontSize: 19, lineHeight: 1.1500000000000001 })).toEqual({ fontSize: 19, lineHeightPercent: 115 })
  })
})
