import { sameReadingProgressAnchor, validReadingProgressAnchor, validReadingProgressIdentity, validateReadingProgressView,
  type ReadingProgressIdentity, type ReadingProgressView } from './readingProgressApi'

export const readingProgressExportErrors = {
  stale: '계정 또는 서버 연결이 변경되어 읽던 위치 조회를 취소했습니다. 다시 선택해 주세요.',
  offline: '최신 계정 읽던 위치를 포함하려면 서버에 다시 로그인해 주세요.',
  pending: '먼저 계정 읽던 위치의 대기 중인 변경이나 충돌을 해결한 뒤 다시 내보내 주세요.',
  response: '최신 계정 읽던 위치 응답이 기존 기록과 일치하지 않습니다. 다시 조회해 주세요.',
} as const

/** GET-only foundation. Full canonical text proof, binding and UI issuance are later adapters. */
export interface FreshReadingProgressReader {
  assertCurrent(): void
  read(identity: ReadingProgressIdentity, signal?: AbortSignal): Promise<ReadingProgressView>
  checkReady(identity: ReadingProgressIdentity, signal?: AbortSignal): Promise<void>
}

const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const responseError = (): never => { throw new Error(readingProgressExportErrors.response) }

/** UUID case is normalized only for account record lookup, never for text IDs or anchor values. */
export function captureFreshReadingProgressIdentity(value: ReadingProgressIdentity): ReadingProgressIdentity {
  if (!exact(value, ['kind', 'recordId']) || !validReadingProgressIdentity(value as ReadingProgressIdentity)) return responseError()
  return { kind: value.kind as ReadingProgressIdentity['kind'], recordId: (value.recordId as string).toLowerCase() }
}

function unicode(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(++index)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return false
    } else if (code >= 0xdc00 && code <= 0xdfff) return false
  }
  return true
}

/** Recheck injected API values as well as production client projections; return detached known fields. */
export function captureFreshReadingProgressView(value: unknown, identity: ReadingProgressIdentity, journalAlias = false): ReadingProgressView {
  if (!exact(value, ['kind', 'recordId', 'version', 'anchor', 'updatedAt'])) return responseError()
  if (value.anchor !== null && (!exact(value.anchor, ['paragraphId', 'characterOffset']) || !validReadingProgressAnchor(value.anchor) ||
    !unicode(value.anchor.paragraphId as string))) return responseError()
  let view: ReadingProgressView
  try { view = validateReadingProgressView(value) } catch { return responseError() }
  if (view.kind !== identity.kind || (journalAlias ? view.recordId.toLowerCase() : view.recordId) !== identity.recordId) return responseError()
  return { ...view, recordId: identity.recordId, anchor: view.anchor ? { ...view.anchor } : null }
}

export function sameFreshReadingProgressView(a: ReadingProgressView, b: ReadingProgressView): boolean {
  return a.kind === b.kind && a.recordId === b.recordId && a.version === b.version && a.updatedAt === b.updatedAt && sameReadingProgressAnchor(a.anchor, b.anchor)
}
