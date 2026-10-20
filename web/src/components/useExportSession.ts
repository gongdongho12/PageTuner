import { useLayoutEffect, useRef, useState } from 'react'

/** A terminal lease: a retained callback can never adopt a later committed session. */
export class ExportSession<Owner> {
  private active = true
  constructor(readonly owner: Owner, readonly origin: string, private readonly valid: () => boolean) {}
  current(): boolean {
    if (!this.active || !this.valid()) this.active = false
    return this.active
  }
  assertCurrent(message = '계정 또는 화면이 변경되어 작업을 취소했습니다. 다시 선택해 주세요.'): void {
    if (!this.current()) throw new Error(message)
  }
  issue(message?: string): () => boolean {
    this.assertCurrent(message)
    return () => this.current()
  }
  close(): void { this.active = false }
}

/** Publish only a committed lease; StrictMode setup after cleanup gets a new object. */
export function useExportSession<Owner>(owner: Owner, origin: string): ExportSession<Owner> | null {
  const latest = useRef({ owner, origin })
  latest.current = { owner, origin }
  const [lease, setLease] = useState<ExportSession<Owner> | null>(null)
  useLayoutEffect(() => {
    const next = new ExportSession(owner, origin, () => latest.current.owner === owner
      && latest.current.origin === origin && window.location.origin === origin)
    setLease(next)
    return () => next.close()
  }, [owner, origin])
  return lease?.owner === owner && lease.origin === origin && lease.current() ? lease : null
}
