import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { PdfDecoderInput } from './pdfDecoderInput'

describe('bounded immutable decoder bytes', () => {
  it('binds hash and decoder copies to one captured snapshot without caller mutation', async () => {
    const bytes = new TextEncoder().encode('%PDF-exact original'), expected = createHash('sha256').update(bytes).digest('hex')
    const input = PdfDecoderInput.capture(bytes), pending = input.verifiedContext(2)
    bytes.fill(0); input.copyBytes().fill(1)
    expect(await pending).toEqual({ originalFileSha256: expected, pageCount: 2 })
    expect(createHash('sha256').update(input.copyBytes()).digest('hex')).toBe(expected)
    expect(input.byteLength).toBe(19)
    expect(Object.isFrozen(await input.verifiedContext(2))).toBe(true)
  })
  it('rejects nonpositive/fractional/raw invalid counts without clamping and enforces bounds before copying', async () => {
    const source = new Uint8Array([1]), input = PdfDecoderInput.capture(source)
    for (const count of [0, -1, 1.2, NaN, Infinity, 2_147_483_648]) await expect(input.verifiedContext(count)).rejects.toThrow()
    expect((await input.verifiedContext(2_147_483_647)).pageCount).toBe(2_147_483_647)
    expect(() => PdfDecoderInput.capture(new Uint8Array())).toThrow()
    expect(() => PdfDecoderInput.capture(new Uint8Array(2), 1)).toThrow()
    expect(() => PdfDecoderInput.capture(source, 0)).toThrow()
    expect(() => PdfDecoderInput.capture(source, PdfDecoderInput.maximumBytes + 1)).toThrow()
  })
})
