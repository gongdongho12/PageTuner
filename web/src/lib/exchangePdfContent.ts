import { exchangeLimits, validateExchangeDocument, type ExchangePackage } from './libraryExchange'
import { PdfContentError, pdfContentLimits, validatePdfContentDocument, type PdfContentDocument } from './pdfContentApi'

const invalid = (): never => { throw new PdfContentError('invalid-request') }
const current = (signal?: AbortSignal) => { if (signal?.aborted) throw new PdfContentError('aborted') }
function base64(bytes: Uint8Array): string {
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
  return btoa(binary)
}

/** Exact selected ZIP content only: this creates no upload, upload ID, binding or reader-page claim. */
export async function preparePdfContentFromExchange(input: ExchangePackage, documentIndex: number, signal?: AbortSignal) {
  current(signal)
  try {
    if (!input || !Array.isArray(input.documents) || input.documents.length > exchangeLimits.documents || !Number.isSafeInteger(documentIndex) || documentIndex < 0 || documentIndex >= input.documents.length || !Array.isArray(input.assets) || input.assets.length > exchangeLimits.entries) return invalid()
    const document = validateExchangeDocument(input.documents[documentIndex])
    if (document.paragraphs.length > pdfContentLimits.paragraphs || document.assets.length > pdfContentLimits.references || document.assets.filter(a => a.role === 'pdf').length !== 1) return invalid()
    const metadataSize = document.language.length + document.paragraphs.reduce((n, p) => n + p.paragraphId.length + p.text.length, 0) + document.assets.reduce((n, a) => n + (a.paragraphId?.length ?? 0) + (a.alt?.length ?? 0), 0)
    if (metadataSize > pdfContentLimits.metadataCodeUnits) return invalid()
    const paths = [...new Set(document.assets.map(a => a.path))]
    if (paths.length > pdfContentLimits.payloads) return invalid()
    const available = new Map<string, ExchangePackage['assets'][number]>()
    let packageBytes = 0
    for (const asset of input.assets) {
      if (!asset || typeof asset.path !== 'string' || available.has(asset.path) || !(asset.bytes instanceof Uint8Array) || !asset.bytes.length || asset.bytes.length > exchangeLimits.archive) return invalid()
      packageBytes += asset.bytes.length; if (packageBytes > exchangeLimits.expanded) return invalid()
      available.set(asset.path, asset)
    }
    let total = 0
    const selected = paths.map(path => {
      const asset = available.get(path)
      if (!asset || !(asset.bytes instanceof Uint8Array) || !asset.bytes.length || !['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(asset.mimeType)) return invalid()
      total += asset.bytes.byteLength; if (total > pdfContentLimits.payloadBytes) return invalid()
      return asset
    })
    // The shared builder order is first reference occurrence, independent of ZIP entry/payload order.
    // All mutable input is consumed synchronously before validatePdfContentDocument's first hash await.
    const content: PdfContentDocument = { version: 1, language: document.language,
      paragraphs: document.paragraphs.map(p => ({ ...p })),
      assets: document.assets.map(a => ({ path: a.path, role: a.role, paragraphId: a.paragraphId ?? null, alt: a.alt ?? null })),
      payloads: selected.map(a => ({ path: a.path, mimeType: a.mimeType as PdfContentDocument['payloads'][number]['mimeType'], base64: base64(a.bytes) })) }
    const result = await validatePdfContentDocument(content); current(signal)
    return result
  } catch { current(signal); return invalid() }
}
