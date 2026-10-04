import type { SharedReaderPreferences } from './readerPreferenceApi'
import type { ReaderPreferences } from './readerPreferences'

/** Platform-only font and keyboard choices never enter the account payload. */
export function sharedReaderPreferences(value: ReaderPreferences): SharedReaderPreferences {
  return { fontSize: value.fontSize, lineHeightPercent: Math.round(value.lineHeight * 100), pageMargin: value.pageMargin,
    touchDirection: value.touchDirection, listMode: value.listMode }
}
export function sharedReaderPreferencePatch(value: Partial<ReaderPreferences>): Partial<SharedReaderPreferences> {
  return { ...(value.fontSize !== undefined ? { fontSize: value.fontSize } : {}),
    ...(value.lineHeight !== undefined ? { lineHeightPercent: Math.round(value.lineHeight * 100) } : {}),
    ...(value.pageMargin !== undefined ? { pageMargin: value.pageMargin } : {}),
    ...(value.touchDirection !== undefined ? { touchDirection: value.touchDirection } : {}),
    ...(value.listMode !== undefined ? { listMode: value.listMode } : {}) }
}
export function projectReaderPreferences(device: ReaderPreferences, shared: SharedReaderPreferences): ReaderPreferences {
  return { ...device, fontSize: shared.fontSize, lineHeight: shared.lineHeightPercent / 100, pageMargin: shared.pageMargin,
    touchDirection: shared.touchDirection, listMode: shared.listMode }
}
