import { DOMParser } from '@xmldom/xmldom'
import { Unzip, UnzipInflate } from 'fflate'
import { textParagraphs } from './localDocuments'

const MAX_EXPANDED = 64 * 1024 * 1024, MAX_ENTRY = 8 * 1024 * 1024
type XmlElement = ReturnType<DOMParser['parseFromString']>['documentElement']

export function resolveEpubPath(base: string, reference: string): string {
  let decoded: string
  try { decoded = decodeURIComponent(reference.split('#')[0]) } catch { throw new Error('EPUB 내부 파일 경로가 잘못되었습니다.') }
  if (!decoded || /^[a-z][a-z\d+.-]*:/i.test(decoded) || decoded.startsWith('/') || /[\\\0?]/.test(decoded)) throw new Error('EPUB에 허용되지 않는 외부 파일 경로가 있습니다.')
  const parts = base.split('/').slice(0, -1)
  for (const part of decoded.split('/')) {
    if (!part || part === '.') continue
    if (part === '..') { if (!parts.length) throw new Error('EPUB 내부 파일 경로가 잘못되었습니다.'); parts.pop() } else parts.push(part)
  }
  return parts.join('/')
}

async function expand(bytes: Uint8Array, signal?: AbortSignal): Promise<Map<string, Uint8Array>> {
  const files = new Map<string, Uint8Array>()
  let total = 0, count = 0, failure: Error | undefined
  const unzip = new Unzip(file => {
    if (++count > 4000 || files.has(file.name) || (file.originalSize ?? 0) > MAX_ENTRY || file.name.startsWith('/') || file.name.split('/').includes('..')) {
      failure = new Error('EPUB 압축 파일의 크기 또는 구조가 허용 범위를 벗어났습니다.'); file.terminate(); return
    }
    files.set(file.name, new Uint8Array())
    const chunks: Uint8Array[] = []; let length = 0
    file.ondata = (error, data, final) => {
      if (error) { failure = new Error('EPUB 압축을 풀지 못했습니다.'); file.terminate(); return }
      length += data.length; total += data.length
      if (length > MAX_ENTRY || total > MAX_EXPANDED) { failure = new Error('EPUB 압축 해제 크기가 너무 큽니다.'); file.terminate(); return }
      chunks.push(data)
      if (final) { const result = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length } files.set(file.name, result) }
    }
    file.start()
  })
  unzip.register(UnzipInflate)
  try {
    for (let offset = 0; offset < bytes.length; offset += 16_384) {
      signal?.throwIfAborted(); if (failure) throw failure
      unzip.push(bytes.subarray(offset, offset + 16_384), offset + 16_384 >= bytes.length)
      if (offset % 131_072 === 0) await new Promise(resolve => setTimeout(resolve, 0))
    }
    if (failure) throw failure
  } catch (error) { signal?.throwIfAborted(); throw error instanceof Error ? error : new Error('EPUB 압축을 풀지 못했습니다.') }
  return files
}

function xml(bytes: Uint8Array | undefined) {
  if (!bytes) throw new Error('EPUB에 필요한 내부 파일이 없습니다.')
  let source: string
  try { source = new TextDecoder('utf-8', { fatal: true }).decode(bytes) } catch { throw new Error('EPUB XML 인코딩을 읽지 못했습니다.') }
  if (/<!ENTITY|<!DOCTYPE[^>]*\[/i.test(source)) throw new Error('EPUB의 외부 엔터티 선언은 지원하지 않습니다.')
  source = source.replace(/<!DOCTYPE[^>]*>/gi, '').replace(/&nbsp;/g, '&#160;')
  return new DOMParser({ onError: level => { if (level !== 'warning') throw new Error('EPUB XML 문서가 손상되었습니다.') } }).parseFromString(source, 'application/xml')
}
const descendants = (node: XmlElement, name: string) => node ? Array.from(node.getElementsByTagName('*')).filter(element => element.localName === name) : []
const nameOf = (node: { localName?: string | null; nodeName: string }) => (node.localName ?? node.nodeName).toLowerCase()

export function safeImageType(bytes: Uint8Array): string | undefined {
  if (bytes[0] === 0x89 && String.fromCharCode(...bytes.slice(1, 4)) === 'PNG') return 'image/png'
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg'
  if (String.fromCharCode(...bytes.slice(0, 6)).match(/^GIF8[79]a$/)) return 'image/gif'
  if (String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF' && String.fromCharCode(...bytes.slice(8, 12)) === 'WEBP') return 'image/webp'
  return undefined
}

export async function parseEpub(bytes: Uint8Array, signal?: AbortSignal) {
  const files = await expand(bytes, signal)
  const container = xml(files.get('META-INF/container.xml'))
  const rootPath = descendants(container.documentElement, 'rootfile')[0]?.getAttribute('full-path')
  if (!rootPath) throw new Error('EPUB 패키지 경로를 찾지 못했습니다.')
  const packagePath = resolveEpubPath('', rootPath), packageXml = xml(files.get(packagePath))
  const metadata = descendants(packageXml.documentElement, 'metadata')[0]
  const title = descendants(metadata, 'title')[0]?.textContent?.trim() ?? ''
  const language = descendants(metadata, 'language')[0]?.textContent?.trim() || 'auto'
  const manifest = new Map(descendants(packageXml.documentElement, 'item').map(item => [item.getAttribute('id'), item]))
  const spine = descendants(packageXml.documentElement, 'itemref').filter(item => item.getAttribute('linear') !== 'no')
  if (!spine.length) throw new Error('EPUB 읽기 순서를 찾지 못했습니다.')
  const chapters: { title: string; paragraphs: string[]; images: { alt: string; blob: Blob }[] }[] = []
  let totalText = 0, imageBytes = 0
  for (const [index, item] of spine.entries()) {
    signal?.throwIfAborted()
    const reference = manifest.get(item.getAttribute('idref'))
    if (!reference) throw new Error('EPUB 목차가 존재하지 않는 파일을 가리킵니다.')
    const path = resolveEpubPath(packagePath, reference.getAttribute('href') ?? '')
    const chapter = xml(files.get(path)), body = descendants(chapter.documentElement, 'body')[0] ?? chapter.documentElement
    const chapterTitle = descendants(body, 'h1')[0]?.textContent?.trim() || descendants(body, 'h2')[0]?.textContent?.trim() || descendants(chapter.documentElement, 'title')[0]?.textContent?.trim() || `${index + 1}`
    const images: { alt: string; blob: Blob }[] = []
    let text = ''
    function walk(node: NonNullable<XmlElement>['childNodes'][number]) {
      if (node.nodeType === 3 || node.nodeType === 4) { text += node.nodeValue ?? ''; return }
      if (node.nodeType !== 1) return
      const element = node as NonNullable<XmlElement>, name = nameOf(element)
      if (['script', 'style', 'iframe', 'object', 'embed', 'form', 'svg', 'math'].includes(name)) return
      if (name === 'img') {
        const alt = element.getAttribute('alt')?.trim() || '삽화'
        text += `\n\n[${alt}]\n\n`
        try {
          const image = files.get(resolveEpubPath(path, element.getAttribute('src') ?? ''))
          const type = image && safeImageType(image)
          if (image && type && image.length <= 2 * 1024 * 1024 && images.length < 2) {
            imageBytes += image.length
            if (imageBytes > 20 * 1024 * 1024) throw new Error('EPUB 삽화 용량이 너무 큽니다.')
            images.push({ alt, blob: new Blob([Uint8Array.from(image).buffer], { type }) })
          }
        } catch (error) { if (error instanceof Error && error.message.includes('용량')) throw error }
        return
      }
      const block = ['p', 'div', 'section', 'article', 'h1', 'h2', 'h3', 'li', 'blockquote', 'tr', 'br'].includes(name)
      if (block) text += '\n\n'
      for (const child of Array.from(element.childNodes)) walk(child)
      if (block) text += '\n\n'
    }
    if (body) walk(body)
    if (!text.trim()) throw new Error('EPUB 장에서 읽을 본문이나 삽화를 찾지 못했습니다.')
    totalText += text.length
    if (totalText > 5_000_000) throw new Error('EPUB 본문이 너무 큽니다.')
    chapters.push({ title: chapterTitle, paragraphs: textParagraphs(text), images })
  }
  return { title, language, chapters }
}
