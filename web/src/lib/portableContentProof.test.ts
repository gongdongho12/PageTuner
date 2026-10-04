import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import vectors from '../../../contracts/fixtures/portable-content-proof-v1/vectors.json'
import { createPortableContentProof, validPortablePdfAnchor, validPortableTextAnchor, portableProofLimits, type PortableContentInput, type PortableRepresentation } from './portableContentProof'
const root = new URL('../../../contracts/fixtures/portable-content-proof-v1/', import.meta.url)
const input = (index: number): PortableContentInput => {
  const v = vectors[index]
  return { representation: v.representation as PortableRepresentation, language: v.document.language, paragraphs: structuredClone(v.document.paragraphs),
    originalFile: v.originalFile ? new Uint8Array(readFileSync(new URL(v.originalFile, root))) : undefined,
    assetReferences: structuredClone(v.document.assets) as PortableContentInput['assetReferences'],
    assets: v.payloads.map(a => ({ path: a.path, mimeType: a.mimeType, bytes: new Uint8Array(readFileSync(new URL(a.file, root))) })) }
}


describe('portable content proof v1 is an isolated equality contract', () => {
  it.each(vectors.map((v, index) => [v.name, index] as const))('matches independently produced shared vector %s', async (_, index) => {
    expect(await createPortableContentProof(input(index))).toEqual(vectors[index].expected)
  })
  it('binds exact language, order, bytes, role, paragraph binding, null versus empty alt, and repeated image refs', async () => {
    const baseline = input(2), proof = await createPortableContentProof(baseline)
    const mutations: ((value: PortableContentInput) => void)[] = [
      v => { v.language = 'KO' }, v => { v.paragraphs.reverse() }, v => { v.paragraphs[0].text += ' ' },
      v => { const prior = v.paragraphs[0].paragraphId; v.paragraphs[0].paragraphId = '\ufeff'; for (const ref of v.assetReferences) if (ref.paragraphId === prior) ref.paragraphId = '\ufeff' },
      v => { v.paragraphs[0].paragraphId += '|'; v.assetReferences[0].paragraphId = v.paragraphs[0].paragraphId },
      v => { v.assetReferences.reverse() }, v => { v.assetReferences[0].alt = '' },
      v => { v.assetReferences[0].paragraphId = null }, v => { v.assets[0].mimeType = 'image/jpeg' },
      v => { v.originalFile![0] ^= 1 }, v => { v.assetReferences.pop() },
    ]
    for (const change of mutations) { const value = structuredClone(baseline); change(value); expect((await createPortableContentProof(value)).sha256).not.toBe(proof.sha256) }
    expect(proof.assets).toHaveLength(2); expect(proof.assets[0].path).toBe(proof.assets[1].path)
  })
  it('snapshots every mutable source before asynchronous hashing', async () => {
    const value = input(2), expected = await createPortableContentProof(value), pending = createPortableContentProof(value)
    value.originalFile!.fill(0); value.assets[0].bytes.fill(0); value.assetReferences[0].alt = 'changed'; value.assetReferences.reverse(); value.paragraphs[0].text = 'changed'; value.paragraphs.reverse()
    expect(await pending).toEqual(expected)
  })
  it('rejects missing originals/assets, altered payloads, duplicate payload paths, orphans, illegal roles/MIME/Unicode and bounds', async () => {
    const invalid: ((v: PortableContentInput) => void)[] = [
      v => { delete v.originalFile }, v => { v.originalFile = new Uint8Array() }, v => { v.assets = [] },
      v => { v.assets.push(structuredClone(v.assets[0])) }, v => { v.assetReferences = [] },
      v => { v.assets[0].bytes[0] ^= 1 }, v => { v.assets[0].path = '../file' },
      v => { v.assets[0].mimeType = 'image/PNG' }, v => { v.assetReferences[0].role = 'pdf' },
      v => { v.assetReferences[0].paragraphId = 'missing' }, v => { v.assetReferences[0].alt = '\ud800' },
      v => { v.paragraphs[0].text = '\udc00' }, v => { v.paragraphs[0].paragraphId = '\u00a0' },
      v => { v.language = 'en_US' }, v => { v.paragraphs = [] },
      v => { v.assetReferences = Array.from({ length: portableProofLimits.references + 1 }, () => v.assetReferences[0]) },
      v => { v.assets[0].bytes = new Uint8Array(portableProofLimits.fileBytes + 1) },
    ]
    for (const mutate of invalid) { const v = input(2); mutate(v); await expect(createPortableContentProof(v)).rejects.toThrow() }
    const text = input(0); text.assets = input(2).assets; text.assetReferences = input(2).assetReferences; await expect(createPortableContentProof(text)).rejects.toThrow()
    const pdf = input(1); pdf.originalFile![0] ^= 1; await expect(createPortableContentProof(pdf)).rejects.toThrow()
    const duplicate = input(1); duplicate.assetReferences.push(duplicate.assetReferences[0]); await expect(createPortableContentProof(duplicate)).rejects.toThrow()
  })
  it('does not derive EPUB original bytes from an old ZIP with only text and illustrations', async () => {
    const legacy = input(2); delete legacy.originalFile
    await expect(createPortableContentProof(legacy)).rejects.toThrow()
  })
  it('validates proof-bound exact UTF16 text anchors, including empty/end, without approximate PDF mapping', async () => {
    const value = input(0), proof = await createPortableContentProof(value), paragraph = value.paragraphs[0]
    for (const anchor of [{ type: 'TEXT', paragraphId: paragraph.paragraphId, characterOffset: paragraph.text.length }, { type: 'TEXT', paragraphId: 'empty', characterOffset: 0 }]) expect(await validPortableTextAnchor(proof, value.paragraphs, anchor)).toBe(true)
    expect(await validPortableTextAnchor({ ...proof, sha256: 'a'.repeat(64) }, value.paragraphs, { type: 'TEXT', paragraphId: 'empty', characterOffset: 0 })).toBe(false)
    expect(await validPortableTextAnchor(proof, [null] as unknown as typeof value.paragraphs, { type: 'TEXT', paragraphId: 'empty', characterOffset: 0 })).toBe(false)
    const split = paragraph.text.indexOf('😀') + 1
    for (const offset of [-1, split, paragraph.text.length + 1, 1.5]) expect(await validPortableTextAnchor(proof, value.paragraphs, { type: 'TEXT', paragraphId: paragraph.paragraphId, characterOffset: offset })).toBe(false)
    const changed = structuredClone(value.paragraphs); changed[0].text += ' '
    expect(await validPortableTextAnchor(proof, changed, { type: 'TEXT', paragraphId: paragraph.paragraphId, characterOffset: 0 })).toBe(false)
    expect(await validPortableTextAnchor({ ...proof, extra: true } as typeof proof, value.paragraphs, { type: 'TEXT', paragraphId: 'empty', characterOffset: 0 })).toBe(false)
    const pdf = await createPortableContentProof(input(1)); expect(await validPortableTextAnchor(pdf, [], { type: 'TEXT', paragraphId: 'empty', characterOffset: 0 })).toBe(false)
  })
  it('accepts an explicit PDF text namespace only against the same extracted canonical text proof', async () => {
    const source = input(1); source.paragraphs = [{ paragraphId: 'pdf-text', text: 'A😀' }]
    const proof = await createPortableContentProof(source), anchor = { type: 'TEXT', paragraphId: 'pdf-text', characterOffset: 3 }
    expect(await validPortableTextAnchor(proof, source.paragraphs, anchor)).toBe(true)
    expect(await validPortableTextAnchor(proof, [{ paragraphId: 'pdf-text', text: 'different' }], anchor)).toBe(false)
    expect(await validPortablePdfAnchor(proof, { originalFileSha256: proof.originalFileSha256!, pageCount: 1 }, anchor)).toBe(false)
  })
  it('keeps PDF anchors in a physical namespace tied to the exact original and independently decoded page count', async () => {
    const proof = await createPortableContentProof(input(1)), verified = { originalFileSha256: proof.originalFileSha256!, pageCount: 2 }, anchor = { type: 'PDF', originalFileSha256: proof.originalFileSha256!, pageIndex: 1 }
    expect(await validPortablePdfAnchor(proof, verified, anchor)).toBe(true)
    expect(await validPortablePdfAnchor({ ...proof, sha256: 'a'.repeat(64) }, verified, anchor)).toBe(false)
    for (const pageIndex of [-1, 2, 0.5, Number.MAX_SAFE_INTEGER]) expect(await validPortablePdfAnchor(proof, verified, { ...anchor, pageIndex })).toBe(false)
    expect(await validPortablePdfAnchor(proof, { ...verified, pageCount: 0 }, anchor)).toBe(false)
    expect(await validPortablePdfAnchor(proof, { ...verified, originalFileSha256: 'a'.repeat(64) }, anchor)).toBe(false)
    expect(await validPortablePdfAnchor(proof, verified, { ...anchor, type: 'TEXT' })).toBe(false)
    expect(await validPortablePdfAnchor(await createPortableContentProof(input(2)), verified, anchor)).toBe(false)
  })
})
