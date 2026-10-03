import { sha256, kotlinTrim } from './validation'

/** Content equality only: this proof never establishes provenance, ownership or permission. */
export type PortableRepresentation = 'TEXT' | 'PDF' | 'EPUB'
export type ProofParagraph = { paragraphId: string; text: string }
export type ProofAssetReference = { path: string; role: 'pdf' | 'image'; paragraphId?: string | null; alt?: string | null }
export type ProofAsset = { path: string; mimeType: string; bytes: Uint8Array }
export type PortableContentInput = { representation: PortableRepresentation; language: string; paragraphs: ProofParagraph[];
  originalFile?: Uint8Array; assetReferences: ProofAssetReference[]; assets: ProofAsset[] }
export type ProofResolvedAsset = { path: string; role: 'pdf' | 'image'; paragraphId: string | null; alt: string | null; mimeType: string; byteLength: number; sha256: string }
export type PortableContentProof = { version: 1; representation: PortableRepresentation; language: string; paragraphHash: string;
  originalFileSha256: string | null; originalFileByteLength: number | null; assets: ProofResolvedAsset[]; sha256: string }
export type PortableTextAnchor = { type: 'TEXT'; paragraphId: string; characterOffset: number }
export type PortablePdfAnchor = { type: 'PDF'; originalFileSha256: string; pageIndex: number }
export const portableProofLimits = { paragraphs: 50_000, textCodeUnits: 5_000_000, references: 512, assets: 511,
  fileBytes: 32 * 1024 * 1024, totalBytes: 64 * 1024 * 1024 } as const
const invalid = (): never => { throw new Error('Invalid portable content proof') }
const hex = (s: unknown): s is string => typeof s === 'string' && /^[0-9a-f]{64}$/.test(s)
const encoder = new TextEncoder()
function unicode(s: unknown, maximum: number, blank = false): string {
  if (typeof s !== 'string' || s.length > maximum || !blank && !kotlinTrim(s)) return invalid()
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c >= 0xd800 && c <= 0xdbff) { const next = s.charCodeAt(++i); if (!(next >= 0xdc00 && next <= 0xdfff)) return invalid() }
    else if (c >= 0xdc00 && c <= 0xdfff) return invalid()
  }
  return s
}
const frame = (s: string) => `${encoder.encode(s).byteLength}:${s}`
async function bytesHash(bytes: Uint8Array): Promise<string> {
  const result = await crypto.subtle.digest('SHA-256', Uint8Array.from(bytes).buffer)
  return Array.from(new Uint8Array(result), b => b.toString(16).padStart(2, '0')).join('')
}
function snapshot(input: PortableContentInput): PortableContentInput {
  if (!input || !['TEXT', 'PDF', 'EPUB'].includes(input.representation) || !Array.isArray(input.paragraphs) || !Array.isArray(input.assetReferences) || !Array.isArray(input.assets)) return invalid()
  if (input.paragraphs.length > portableProofLimits.paragraphs || input.assetReferences.length > portableProofLimits.references || input.assets.length > portableProofLimits.assets) return invalid()
  let total = input.originalFile?.byteLength ?? 0
  for (const asset of input.assets) { total += asset.bytes?.byteLength ?? portableProofLimits.totalBytes + 1; if (total > portableProofLimits.totalBytes) return invalid() }
  const copyBytes = (bytes: Uint8Array) => { if (!(bytes instanceof Uint8Array) || !bytes.length || bytes.length > portableProofLimits.fileBytes) return invalid(); return Uint8Array.from(bytes) }
  return { representation: input.representation, language: input.language,
    paragraphs: input.paragraphs.map(p => ({ paragraphId: p.paragraphId, text: p.text })),
    originalFile: input.originalFile === undefined ? undefined : copyBytes(input.originalFile),
    assetReferences: input.assetReferences.map(r => ({ path: r.path, role: r.role, paragraphId: r.paragraphId ?? null, alt: r.alt ?? null })),
    assets: input.assets.map(a => ({ path: a.path, mimeType: a.mimeType, bytes: copyBytes(a.bytes) })) }
}
/** Full original EPUB bytes are required; old ZIPs containing only extracted text/images cannot supply this proof. */
export async function createPortableContentProof(input: PortableContentInput): Promise<PortableContentProof> {
  // Every mutable byte/array/object is copied before the first asynchronous digest.
  const source = snapshot(input), { representation, language, paragraphs, assetReferences, assets, originalFile } = source
  unicode(language, 35); if (!/^[A-Za-z][A-Za-z0-9-]{0,34}$/.test(language)) return invalid()
  if (paragraphs.length > portableProofLimits.paragraphs || !paragraphs.length && representation !== 'PDF' || assetReferences.length > portableProofLimits.references || assets.length > portableProofLimits.assets) return invalid()
  if (representation !== 'TEXT' && !originalFile || representation === 'TEXT' && (assetReferences.length || assets.length)) return invalid()
  const ids = new Set<string>(), paragraphFrames = [frame('pageturner.document-paragraphs.v1'), frame(String(paragraphs.length))]; let codeUnits = 0
  for (const p of paragraphs) {
    unicode(p.paragraphId, 500); unicode(p.text, portableProofLimits.textCodeUnits, true)
    if (ids.has(p.paragraphId)) return invalid(); ids.add(p.paragraphId); codeUnits += p.text.length
    if (codeUnits > portableProofLimits.textCodeUnits) return invalid(); paragraphFrames.push(frame(p.paragraphId), frame(p.text))
  }
  const assetMap = new Map<string, ProofAsset>(); let total = originalFile?.length ?? 0
  for (const asset of assets) {
    if (!/^assets\/[a-f0-9]{64}$/.test(asset.path) || assetMap.has(asset.path) || !['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(asset.mimeType)) return invalid()
    assetMap.set(asset.path, asset); total += asset.bytes.length; if (total > portableProofLimits.totalBytes) return invalid()
  }
  const used = new Set<string>(); let pdfCount = 0
  for (const ref of assetReferences) {
    const asset = assetMap.get(ref.path); if (!asset) return invalid()
    if (ref.paragraphId !== null && ref.paragraphId !== undefined) { unicode(ref.paragraphId, 500); if (!ids.has(ref.paragraphId)) return invalid() }
    if (ref.alt !== null && ref.alt !== undefined) unicode(ref.alt, 2000, true)
    if (ref.role === 'pdf') { pdfCount++; if (representation !== 'PDF' || asset.mimeType !== 'application/pdf' || ref.paragraphId !== null) return invalid() }
    else if (ref.role !== 'image' || !asset.mimeType.startsWith('image/')) return invalid()
    used.add(ref.path)
  }
  if (used.size !== assets.length || representation === 'PDF' && pdfCount !== 1 || representation !== 'PDF' && pdfCount !== 0) return invalid()
  const paragraphHash = await sha256(paragraphFrames.join('')), originalFileSha256 = originalFile ? await bytesHash(originalFile) : null
  const hashes = new Map<string, string>()
  for (const asset of assets) { const hash = await bytesHash(asset.bytes); if (asset.path !== `assets/${hash}`) return invalid(); hashes.set(asset.path, hash) }
  const resolved: ProofResolvedAsset[] = []
  const values = ['pageturner.content-proof.v1', '1', representation, language, paragraphHash, originalFile ? '1' : '0']
  if (originalFile) values.push(String(originalFile.length), originalFileSha256!)
  values.push(String(assetReferences.length))
  for (const ref of assetReferences) {
    const asset = assetMap.get(ref.path)!, hash = hashes.get(ref.path)!
    if (ref.role === 'pdf' && hash !== originalFileSha256) return invalid()
    resolved.push({ path: ref.path, role: ref.role, paragraphId: ref.paragraphId ?? null, alt: ref.alt ?? null, mimeType: asset.mimeType, byteLength: asset.bytes.length, sha256: hash })
    values.push(ref.path, ref.role, ref.paragraphId == null ? '0' : '1'); if (ref.paragraphId != null) values.push(ref.paragraphId)
    values.push(ref.alt == null ? '0' : '1'); if (ref.alt != null) values.push(ref.alt)
    values.push(asset.mimeType, String(asset.bytes.length), hash)
  }
  return { version: 1, representation, language, paragraphHash, originalFileSha256, originalFileByteLength: originalFile?.length ?? null, assets: resolved, sha256: await sha256(values.map(frame).join('')) }
}
/** Never maps PDF physical pages to text paragraphs, or uses extracted text to count PDF pages. */
function checkedProofSnapshot(proof: PortableContentProof): PortableContentProof | undefined {
  try {
    if (!proof || typeof proof !== 'object' || Object.keys(proof).sort().join(',') !== 'assets,language,originalFileByteLength,originalFileSha256,paragraphHash,representation,sha256,version' || proof.version !== 1 || !['TEXT', 'PDF', 'EPUB'].includes(proof.representation) || !hex(proof.paragraphHash) || !hex(proof.sha256) || typeof proof.language !== 'string' || !/^[A-Za-z][A-Za-z0-9-]{0,34}$/.test(proof.language) || !Array.isArray(proof.assets) || proof.assets.length > portableProofLimits.references) return undefined
    if (proof.originalFileSha256 === null ? proof.originalFileByteLength !== null || proof.representation !== 'TEXT' : !hex(proof.originalFileSha256) || !Number.isSafeInteger(proof.originalFileByteLength) || proof.originalFileByteLength! <= 0 || proof.originalFileByteLength! > portableProofLimits.fileBytes) return undefined
    if (proof.representation === 'TEXT' && proof.assets.length) return undefined
    const unique = new Map<string, { mimeType: string; byteLength: number }>(); let total = proof.originalFileByteLength ?? 0, pdfCount = 0
    for (const asset of proof.assets) {
      if (!asset || Object.keys(asset).sort().join(',') !== 'alt,byteLength,mimeType,paragraphId,path,role,sha256' || !hex(asset.sha256) || asset.path !== `assets/${asset.sha256}` || !Number.isSafeInteger(asset.byteLength) || asset.byteLength <= 0 || asset.byteLength > portableProofLimits.fileBytes) return undefined
      if (asset.paragraphId !== null) unicode(asset.paragraphId, 500)
      if (asset.alt !== null) unicode(asset.alt, 2000, true)
      if (asset.role === 'pdf') { pdfCount++; if (proof.representation !== 'PDF' || asset.mimeType !== 'application/pdf' || asset.paragraphId !== null || asset.sha256 !== proof.originalFileSha256 || asset.byteLength !== proof.originalFileByteLength) return undefined }
      else if (asset.role !== 'image' || !['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(asset.mimeType)) return undefined
      const prior = unique.get(asset.path)
      if (prior && (prior.mimeType !== asset.mimeType || prior.byteLength !== asset.byteLength)) return undefined
      if (!prior) { unique.set(asset.path, asset); total += asset.byteLength }
    }
    if (unique.size > portableProofLimits.assets || total > portableProofLimits.totalBytes || (proof.representation === 'PDF' ? pdfCount !== 1 : pdfCount !== 0)) return undefined
    return { ...proof, assets: proof.assets.map(a => ({ ...a })) }
  } catch { return undefined }
}
async function proofSelfConsistent(proof: PortableContentProof): Promise<boolean> {
  const values = ['pageturner.content-proof.v1', '1', proof.representation, proof.language, proof.paragraphHash, proof.originalFileSha256 === null ? '0' : '1']
  if (proof.originalFileSha256 !== null) values.push(String(proof.originalFileByteLength), proof.originalFileSha256)
  values.push(String(proof.assets.length))
  for (const asset of proof.assets) {
    values.push(asset.path, asset.role, asset.paragraphId === null ? '0' : '1'); if (asset.paragraphId !== null) values.push(asset.paragraphId)
    values.push(asset.alt === null ? '0' : '1'); if (asset.alt !== null) values.push(asset.alt)
    values.push(asset.mimeType, String(asset.byteLength), asset.sha256)
  }
  return await sha256(values.map(frame).join('')) === proof.sha256
}
export async function validPortableTextAnchor(proof: PortableContentProof, paragraphs: ProofParagraph[], value: unknown): Promise<boolean> {
  const checked = checkedProofSnapshot(proof)
  if (!checked || !Array.isArray(paragraphs) || !paragraphs.length || paragraphs.length > portableProofLimits.paragraphs || !value || typeof value !== 'object' || Array.isArray(value)) return false
  if (paragraphs.some(p => !p || typeof p !== 'object' || typeof p.paragraphId !== 'string' || typeof p.text !== 'string')) return false
  const expectedParagraphHash = checked.paragraphHash
  const v = { ...(value as Record<string, unknown>) }, copy = paragraphs.map(p => ({ paragraphId: p.paragraphId, text: p.text }))
  if (Object.keys(v).length !== 3 || v.type !== 'TEXT' || typeof v.paragraphId !== 'string' || !Number.isSafeInteger(v.characterOffset)) return false
  const frames = [frame('pageturner.document-paragraphs.v1'), frame(String(copy.length))], ids = new Set<string>(); let total = 0
  try { for (const p of copy) { unicode(p.paragraphId, 500); unicode(p.text, portableProofLimits.textCodeUnits, true); total += p.text.length; if (ids.has(p.paragraphId) || total > portableProofLimits.textCodeUnits) return false; ids.add(p.paragraphId); frames.push(frame(p.paragraphId), frame(p.text)) } } catch { return false }
  if (checked.assets.some(asset => asset.paragraphId !== null && !ids.has(asset.paragraphId))) return false
  if (!await proofSelfConsistent(checked) || await sha256(frames.join('')) !== expectedParagraphHash) return false
  const p = copy.find(p => p.paragraphId === v.paragraphId), offset = v.characterOffset as number
  return !!p && offset >= 0 && offset <= p.text.length && !(offset > 0 && offset < p.text.length && /[\uD800-\uDBFF]/.test(p.text[offset - 1]) && /[\uDC00-\uDFFF]/.test(p.text[offset]))
}
/** pageCount must come from independently opening the exact original PDF, never package metadata. */
export async function validPortablePdfAnchor(proof: PortableContentProof, verified: { originalFileSha256: string; pageCount: number }, value: unknown): Promise<boolean> {
  const checked = checkedProofSnapshot(proof)
  if (!checked || checked.representation !== 'PDF' || !hex(checked.originalFileSha256) || verified.originalFileSha256 !== checked.originalFileSha256 || !Number.isSafeInteger(verified.pageCount) || verified.pageCount <= 0 || verified.pageCount > 2_147_483_647 || !value || typeof value !== 'object' || Array.isArray(value)) return false
  const v = { ...(value as Record<string, unknown>) }, pageCount = verified.pageCount
  if (!await proofSelfConsistent(checked)) return false
  return Object.keys(v).length === 3 && v.type === 'PDF' && v.originalFileSha256 === checked.originalFileSha256 && Number.isSafeInteger(v.pageIndex) && (v.pageIndex as number) >= 0 && (v.pageIndex as number) < pageCount
}
