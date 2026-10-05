import { createHash, randomBytes } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import { localSha256 } from './localSha256'

describe('local SHA-256 without secure-context WebCrypto', () => {
  it('matches independent digests across padding boundaries, all byte values, Unicode and large payloads', async () => {
    vi.stubGlobal('crypto', undefined)
    try {
      const values = [new TextEncoder().encode('abc'), new TextEncoder().encode('한글😀\0'), ...[0,1,55,56,63,64,65,119,120,127,128,256,65536,1048576].map(size => new Uint8Array(randomBytes(size)))]
      for (const bytes of values) expect(await localSha256(bytes)).toBe(createHash('sha256').update(bytes).digest('hex'))
    } finally { vi.unstubAllGlobals() }
  })
})
