import type { StoredChapter } from './workflowTypes'
import { normalizeGlossary, type GlossaryEntry } from './glossary'
import { ApiError } from './errors'
import { providerFailureMessage } from './providerCheck'
import { readingFragmentKey, readingFragmentText, verifyReadingTranslation, type ReadingFragment, type ReadingPagination, type ReadingPace,
  type ReadingTranslationClient, type ReadingTranslationItem, type ReadingTranslationRequest, type ReadingTranslationSettings } from './readingTranslation'

export function rollingWindow(page: number, total: number) {
  if (!Number.isSafeInteger(page) || !Number.isSafeInteger(total) || total <= 0 || page < 0 || page >= total) throw new Error('읽기 페이지를 확인해 주세요.')
  const start = Math.floor(page / 10) * 10, end = Math.min(start + 10, total)
  return { start, end, trigger: Math.min(start + 4, end - 1) }
}
export function rollingPageOrder(page: number, total: number): number[] {
  const window = rollingWindow(page, total), end = page >= window.trigger ? Math.min(window.end + 10, total) : window.end
  return [page, ...Array.from({ length: end - window.start }, (_, index) => window.start + index).filter(index => index !== page)]
}
export function readingPacingDelay(words: number, wpm: number, pace: ReadingPace): number {
  const reading = Math.round(Math.max(0, words) / Math.max(80, Math.min(900, wpm)) * 60_000)
  if (pace === 'READING') return Math.max(750, Math.min(14_000, reading))
  if (pace === 'FAST') return Math.max(250, Math.min(4_000, Math.round(reading * .28)))
  return Math.max(80, Math.min(1_200, Math.round(reading * .08)))
}
export type RollingSnapshot = { enabled: boolean; running: boolean; waiting: boolean; error: string; page: number; totalPages: number;
  windowStart: number; windowEnd: number; readyPages: number; items: ReadingTranslationItem[]; cacheCount: number; limited: boolean }
type Options = { uuid?: () => string; sleep?: (milliseconds: number, signal: AbortSignal) => Promise<void>; cacheCharacters?: number; cacheFragments?: number;
  readGlossary?: () => Promise<GlossaryEntry[]> }
function copySettings(value: ReadingTranslationSettings): ReadingTranslationSettings {
  return { providerKind: value.providerKind, sourceLanguage: value.sourceLanguage, targetLanguage: value.targetLanguage,
    endpoint: value.endpoint, model: value.model, apiKey: value.apiKey, glossary: normalizeGlossary(value.glossary ?? []),
    readingWordsPerMinute: value.readingWordsPerMinute, paceMode: value.paceMode }
}
const sleep = (milliseconds: number, signal: AbortSignal) => new Promise<void>((resolve, reject) => {
  if (signal.aborted) { reject(new DOMException('Aborted', 'AbortError')); return }
  const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, milliseconds)
  const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
  signal.addEventListener('abort', abort, { once: true })
})

/** Session-only ranges: never produces or saves a full translation artifact. */
export class RollingTranslationSession {
  private pages: ReadingFragment[][] = []
  private page = 0
  private layout = ''
  private enabled = false
  private running = false
  private waiting = false
  private error = ''
  private generation = 0
  private cache = new Map<string, string>()
  private cacheCharacters = 0
  private deferredPages = new Set<number>()
  private controller?: AbortController
  private activeId?: string
  private activeKeys = new Set<string>()
  private work?: Promise<void>
  private settings: ReadingTranslationSettings
  private readonly uuid: () => string
  private readonly wait: (milliseconds: number, signal: AbortSignal) => Promise<void>
  private readonly maxCharacters: number
  private readonly maxFragments: number
  private readonly readGlossary?: () => Promise<GlossaryEntry[]>
  private refreshPending = false

  constructor(private readonly chapter: StoredChapter, settings: ReadingTranslationSettings, private readonly client: ReadingTranslationClient,
    private readonly changed: (snapshot: RollingSnapshot) => void, options: Options = {}) {
    this.settings = copySettings(settings)
    this.uuid = options.uuid ?? (() => crypto.randomUUID()); this.wait = options.sleep ?? sleep
    this.maxCharacters = Math.max(1, Math.min(1_000_000, options.cacheCharacters ?? 1_000_000))
    this.maxFragments = Math.max(1, Math.min(5_000, options.cacheFragments ?? 5_000))
    this.readGlossary = options.readGlossary
  }
  snapshot(): RollingSnapshot {
    const window = this.pages.length ? rollingWindow(this.page, this.pages.length) : { start: 0, end: 0 }
    const current = this.pages[this.page] ?? []
    const ready = (fragments: ReadingFragment[]) => fragments.length > 0 && fragments.every(fragment => this.translated(fragment) !== undefined)
    return { enabled: this.enabled, running: this.running, waiting: this.waiting, error: this.error, page: this.page, totalPages: this.pages.length,
      windowStart: window.start, windowEnd: window.end, readyPages: this.pages.slice(window.start, window.end).filter(ready).length, cacheCount: this.cache.size, limited: this.deferredPages.size > 0,
      items: ready(current) ? current.map(fragment => ({ ...fragment, text: this.translated(fragment)! })) : [] }
  }
  setPagination(pagination: ReadingPagination): void {
    if (!pagination.pages.length) return
    const pages = pagination.pages.map(fragments => fragments.flatMap(fragment => {
      const text = readingFragmentText(this.chapter, fragment), result: ReadingFragment[] = []
      let offset = 0
      while (offset < text.length) {
        let end = Math.min(offset + 8_000, text.length)
        if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--
        result.push({ paragraphId: fragment.paragraphId, start: fragment.start + offset, end: fragment.start + end }); offset = end
      }
      return result
    }))
    if (!Number.isSafeInteger(pagination.page) || pagination.page < 0 || pagination.page >= pages.length) throw new Error('읽기 페이지를 확인해 주세요.')
    const layout = JSON.stringify(pages)
    const layoutChanged = layout !== this.layout
    const pageChanged = this.page !== pagination.page
    if (layoutChanged || pageChanged) this.refreshPending = true
    this.pages = pages; this.page = pagination.page
    if (layoutChanged) { this.layout = layout; this.generation++; this.controller?.abort(); this.cache.clear(); this.cacheCharacters = 0; this.deferredPages.clear() }
    if (pages[this.page].filter(fragment => readingFragmentText(this.chapter, fragment).trim()).length > this.maxFragments) {
      this.error = '현재 쪽의 번역 범위가 너무 큽니다. 글자 크기를 늘린 뒤 다시 시도해 주세요.'; this.enabled = false; this.controller?.abort(); this.emit(); return
    }
    const missingCurrent = pages[this.page].filter(fragment => this.translated(fragment) === undefined)
    if (pageChanged && missingCurrent.length && (this.waiting || this.activeId && !missingCurrent.some(fragment => this.activeKeys.has(readingFragmentKey(fragment))))) { this.generation++; this.controller?.abort() }
    this.emit(); this.kick()
  }
  configure(settings: ReadingTranslationSettings): void {
    const copied = copySettings(settings)
    if (JSON.stringify(copied) === JSON.stringify(this.settings)) return
    this.settings = copied; this.refreshPending = true
    this.generation++; this.controller?.abort(); this.cache.clear(); this.cacheCharacters = 0; this.deferredPages.clear(); this.error = ''; this.emit(); this.kick()
  }
  start(): void { this.enabled = true; this.error = ''; this.refreshPending = true; this.emit(); this.kick() }
  retry(): void { this.error = ''; this.start() }
  async stop(): Promise<void> { this.enabled = false; this.generation++; this.controller?.abort(); this.emit(); await this.work }
  private emit() { this.changed(this.snapshot()) }
  private translated(fragment: ReadingFragment): string | undefined {
    const original = readingFragmentText(this.chapter, fragment)
    return original.trim() ? this.cache.get(readingFragmentKey(fragment)) : original
  }
  private rememberBatch(items: ReadingTranslationItem[]) {
    const currentKeys = new Set((this.pages[this.page] ?? []).map(readingFragmentKey))
    const isCurrent = items.some(item => currentKeys.has(readingFragmentKey(item)))
    const extra = items.filter(item => !this.cache.has(readingFragmentKey(item))), characters = extra.reduce((sum, item) => sum + item.text.length, 0)
    const fits = () => this.cacheCharacters + characters <= this.maxCharacters && this.cache.size + extra.length <= this.maxFragments
    if (isCurrent && !fits()) for (const [key, value] of this.cache) {
      if (currentKeys.has(key)) continue
      this.cache.delete(key); this.cacheCharacters -= value.length
      this.pages.forEach((page, index) => { if (page.some(fragment => readingFragmentKey(fragment) === key)) this.deferredPages.add(index) })
      if (fits()) break
    }
    if (!fits()) {
      if (isCurrent) throw new Error('현재 쪽의 번역 범위가 너무 큽니다. 글자 크기를 늘린 뒤 다시 시도해 주세요.')
      this.pages.forEach((page, index) => { if (page.some(fragment => items.some(item => readingFragmentKey(item) === readingFragmentKey(fragment)))) this.deferredPages.add(index) })
      return
    }
    for (const item of extra) { this.cache.set(readingFragmentKey(item), item.text); this.cacheCharacters += item.text.length }
  }
  private nextBatch(): ReadingFragment[] {
    if (!this.pages.length) return []
    for (const page of rollingPageOrder(this.page, this.pages.length)) {
      if (page !== this.page && this.deferredPages.has(page)) continue
      const fragments: ReadingFragment[] = [], seen = new Set<string>(); let length = 0
      for (const fragment of this.pages[page]) {
        const key = readingFragmentKey(fragment)
        if (this.translated(fragment) !== undefined || seen.has(key)) continue
        const text = readingFragmentText(this.chapter, fragment)
        if (!text.trim()) continue
        if (fragments.length >= 64 || length + fragment.end - fragment.start > 24_000) break
        fragments.push(fragment); seen.add(key); length += fragment.end - fragment.start
      }
      if (fragments.length) {
        if (page !== this.page && (this.cache.size + fragments.length > this.maxFragments ||
          this.cacheCharacters + Math.min(96_000, length * 4 + 512) > this.maxCharacters)) { this.deferredPages.add(page); continue }
        return fragments
      }
    }
    return []
  }
  private kick() {
    if (!this.enabled || this.running || this.error || !this.nextBatch().length && !(this.readGlossary && this.refreshPending)) return
    this.running = true
    this.work = this.run().finally(() => { this.running = false; this.waiting = false; this.emit(); this.kick() })
    this.emit()
  }
  private async run() {
    const generation = this.generation
    const controller = new AbortController(); this.controller = controller
    let previousWords = 0
    try {
      while (this.enabled && generation === this.generation) {
        this.refreshPending = false
        if (this.readGlossary) {
          const glossary = await this.readGlossary(); controller.signal.throwIfAborted()
          const latest = copySettings({ ...this.settings, glossary })
          if (JSON.stringify(latest) !== JSON.stringify(this.settings)) {
            this.settings = latest; this.cache.clear(); this.cacheCharacters = 0; this.deferredPages.clear(); this.emit()
          }
        }
        const fragments = this.nextBatch(); if (!fragments.length) return
        if (previousWords) { this.waiting = true; this.emit(); await this.wait(readingPacingDelay(previousWords, this.settings.readingWordsPerMinute, this.settings.paceMode), controller.signal); this.waiting = false }
        controller.signal.throwIfAborted()
        const request: ReadingTranslationRequest = { ...this.settings, requestId: this.uuid(), chapterRecordId: this.chapter.recordId, sourceRevision: this.chapter.sourceRevision, fragments }
        this.activeId = request.requestId; this.activeKeys = new Set(fragments.map(readingFragmentKey)); this.emit()
        let result
        for (let attempt = 0; ; attempt++) {
          try { result = await this.client.startReadingTranslation(request, controller.signal); break }
          catch (error) {
            if (!(error instanceof ApiError) || error.status !== 429) throw error
            if (attempt >= 5) throw new Error('이전 번역을 정리하고 있습니다. 잠시 후 현재 쪽을 다시 시도해 주세요.')
            this.waiting = true; this.emit()
            await this.wait(Math.min(4_000, 500 * 2 ** attempt), controller.signal)
            this.waiting = false; controller.signal.throwIfAborted()
          }
        }
        while (true) {
          controller.signal.throwIfAborted(); await verifyReadingTranslation(result, request); controller.signal.throwIfAborted()
          if (result.status === 'COMPLETED') break
          if (result.status === 'FAILED' || result.status === 'CANCELLED') throw new Error(result.errorCode === 'READING_TIMEOUT' ? '읽기 번역이 시간 내 끝나지 않았습니다. 현재 쪽을 다시 시도해 주세요.' : providerFailureMessage(result.errorCode, '읽기 번역을 완료하지 못했습니다. 원문을 읽거나 다시 시도해 주세요.'))
          await this.wait(750, controller.signal); result = await this.client.getReadingTranslation(request.requestId, controller.signal)
        }
        if (!this.enabled || generation !== this.generation) return
        this.rememberBatch(result.items)
        previousWords = fragments.reduce((count, fragment) => count + readingFragmentText(this.chapter, fragment).split(/\s+/).filter(Boolean).length, 0)
        this.activeId = undefined; this.activeKeys.clear(); this.emit()
      }
    } catch (error) {
      if (!controller.signal.aborted && generation === this.generation) this.error = error instanceof Error ? error.message : '읽기 번역을 완료하지 못했습니다.'
    } finally {
      const id = this.activeId
      if (id) {
        // The UUID is allocated before POST, so cancellation also covers an uncertain start response.
        try { await this.client.cancelReadingTranslation(id) } catch { /* The server also bounds task duration. */ }
      }
      this.activeId = undefined; this.activeKeys.clear(); if (this.controller === controller) this.controller = undefined
    }
  }
}
