export type ApiErrorKind =
  | 'authentication' | 'forbidden' | 'not-found' | 'conflict' | 'invalid-request'
  | 'server' | 'network' | 'timeout' | 'aborted' | 'invalid-response' | 'integrity' | 'unsupported'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status?: number

  constructor(kind: ApiErrorKind, message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
  }
}

export type OfflineErrorKind = 'unavailable' | 'blocked' | 'quota' | 'corrupt' | 'not-found' | 'conflict' | 'invalid-anchor'

export class OfflineError extends Error {
  readonly kind: OfflineErrorKind

  constructor(kind: OfflineErrorKind, message: string) {
    super(message)
    this.name = 'OfflineError'
    this.kind = kind
  }
}
