import type { VerifiedPdfContext } from './portableContentProof'
import { localSha256 } from './localSha256'

/** The decoder and digest receive copies of one bounded snapshot, never caller-owned/transferred memory. */
export class PdfDecoderInput {
  static readonly maximumBytes = 32 * 1024 * 1024
  #bytes: Uint8Array<ArrayBuffer>
  #hash?: Promise<string>
  private constructor(bytes: Uint8Array) { this.#bytes = Uint8Array.from(bytes) }
  static capture(bytes: Uint8Array, maximumBytes = PdfDecoderInput.maximumBytes): PdfDecoderInput {
    if (!(bytes instanceof Uint8Array) || !Number.isSafeInteger(maximumBytes) || maximumBytes <= 0 || maximumBytes > PdfDecoderInput.maximumBytes || !bytes.length || bytes.length > maximumBytes) throw new Error('비어 있지 않은 32MB 이하 파일을 선택해 주세요.')
    return new PdfDecoderInput(bytes)
  }
  get byteLength() { return this.#bytes.length }
  copyBytes(): Uint8Array<ArrayBuffer> { return this.#bytes.slice() }
  originalHash(): Promise<string> {
    this.#hash ??= localSha256(this.#bytes)
    return this.#hash
  }
  /** rawPageCount must be returned by the actual decoder opened with copyBytes(). No clamping. */
  async verifiedContext(rawPageCount: number): Promise<VerifiedPdfContext> {
    if (!Number.isSafeInteger(rawPageCount) || rawPageCount <= 0 || rawPageCount > 2_147_483_647) throw new Error('PDF를 열지 못했습니다. 손상되었거나 지원하지 않는 파일인지 확인해 주세요.')
    return Object.freeze({ originalFileSha256: await this.originalHash(), pageCount: rawPageCount })
  }
}
