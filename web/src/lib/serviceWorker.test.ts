import { afterEach, describe, expect, it, vi } from 'vitest'
import { getOfflineShellStatus, startOfflineShell, subscribeOfflineShell } from './serviceWorker'

class Worker extends EventTarget {
  scriptURL = 'https://reader.example/sw.js'
  constructor(public state: ServiceWorkerState) { super() }
  transition(state: ServiceWorkerState) { this.state = state; this.dispatchEvent(new Event('statechange')) }
  asWorker() { return this as unknown as ServiceWorker }
}

class Registration extends EventTarget {
  installing: ServiceWorker | null = null
  waiting: ServiceWorker | null = null
  active: ServiceWorker | null = null
  asRegistration() { return this as unknown as ServiceWorkerRegistration }
  begin(worker: Worker) { this.installing = worker.asWorker(); this.dispatchEvent(new Event('updatefound')) }
  wait(worker: Worker) { this.installing = null; this.waiting = worker.asWorker(); worker.transition('installed') }
  activate(worker: Worker) { this.active = worker.asWorker(); this.installing = null; this.waiting = null; worker.transition('activated') }
}

class Container extends EventTarget {
  constructor(public existing?: ServiceWorkerRegistration, public registration = new Registration()) { super() }
  getRegistration = vi.fn(async (_scope: string) => this.existing)
  register = vi.fn(async (_script: string) => this.registration.asRegistration())
  asContainer() { return this as unknown as ServiceWorkerContainer }
}

const stops: Array<() => void> = []
afterEach(() => { stops.splice(0).forEach(stop => stop()) })
const settle = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }
const start = (container: Container) => {
  stops.push(startOfflineShell({ production: true, container: container.asContainer() }))
}

describe('offline shell lifecycle', () => {
  it('keeps registration success pending until the public cache install activates', async () => {
    const container = new Container()
    const worker = new Worker('installing')
    container.registration.installing = worker.asWorker()
    start(container)
    await settle()
    expect(container.register).toHaveBeenCalledWith('/sw.js')
    expect(getOfflineShellStatus()).toEqual({ state: 'pending', updateFailed: false, updateReady: false })
    container.registration.wait(worker)
    expect(getOfflineShellStatus()).toEqual({ state: 'pending', updateFailed: false, updateReady: false })
    container.registration.activate(worker)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: false })
  })

  it('reports a first-install cache failure even though register already resolved', async () => {
    const container = new Container()
    const worker = new Worker('installing')
    container.registration.installing = worker.asWorker()
    start(container)
    await settle()
    worker.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'error', updateFailed: false, updateReady: false })
  })

  it('keeps an existing shell ready when registration or a new install fails', async () => {
    const existing = new Registration()
    const active = new Worker('activated')
    existing.active = active.asWorker()
    const container = new Container(existing.asRegistration(), existing)
    container.register.mockRejectedValueOnce(new Error('Offline update request'))
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true, updateReady: false })
    const update = new Worker('installing')
    existing.begin(update)
    update.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true, updateReady: false })
  })

  it('reports an already installed waiting update without taking over the active shell', async () => {
    const existing = new Registration()
    const active = new Worker('activated')
    const waiting = new Worker('installed')
    existing.active = active.asWorker()
    existing.waiting = waiting.asWorker()
    const container = new Container(existing.asRegistration(), existing)
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: true })
    expect(existing.active).toBe(active)
    expect(existing.waiting).toBe(waiting)
  })

  it('notifies when an update finishes installing and clears the notice only after activation', async () => {
    const existing = new Registration()
    existing.active = new Worker('activated').asWorker()
    const container = new Container(existing.asRegistration(), existing)
    start(container)
    await settle()
    const listener = vi.fn()
    stops.push(subscribeOfflineShell(listener))
    const update = new Worker('installing')
    existing.begin(update)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: false })
    expect(listener).not.toHaveBeenCalled()
    existing.wait(update)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: true })
    expect(listener).toHaveBeenCalledOnce()
    existing.activate(update)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: false })
  })

  it('preserves a ready waiting update if a later update check or install fails', async () => {
    const existing = new Registration()
    existing.active = new Worker('activated').asWorker()
    existing.waiting = new Worker('installed').asWorker()
    const container = new Container(existing.asRegistration(), existing)
    container.register.mockRejectedValueOnce(new Error('Offline update request'))
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true, updateReady: true })
    const failed = new Worker('installing')
    existing.begin(failed)
    failed.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true, updateReady: true })
  })

  it('clears an earlier update failure once a replacement is fully installed', async () => {
    const existing = new Registration()
    existing.active = new Worker('activated').asWorker()
    const container = new Container(existing.asRegistration(), existing)
    start(container)
    await settle()
    const failed = new Worker('installing')
    existing.begin(failed)
    failed.transition('redundant')
    expect(getOfflineShellStatus().updateFailed).toBe(true)
    const next = new Worker('installing')
    existing.begin(next)
    existing.wait(next)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: true })
  })

  it('does not announce unrelated or incomplete waiting workers as ready updates', async () => {
    const existing = new Registration()
    existing.active = new Worker('activated').asWorker()
    const waiting = new Worker('installed')
    waiting.scriptURL = 'https://reader.example/another-worker.js'
    existing.waiting = waiting.asWorker()
    const container = new Container(existing.asRegistration(), existing)
    start(container)
    await settle()
    expect(getOfflineShellStatus().updateReady).toBe(false)
    const incomplete = new Worker('installing')
    existing.waiting = incomplete.asWorker()
    existing.begin(incomplete)
    expect(getOfflineShellStatus().updateReady).toBe(false)
  })

  it('clears update errors on activation and does not treat retiring the old worker as failure', async () => {
    const existing = new Registration()
    const previous = new Worker('activated')
    existing.active = previous.asWorker()
    const container = new Container(existing.asRegistration(), existing)
    start(container)
    await settle()
    const failed = new Worker('installing')
    existing.begin(failed)
    failed.transition('redundant')
    const next = new Worker('installing')
    existing.begin(next)
    existing.activate(next)
    previous.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false, updateReady: false })
  })

  it('reports registration failures and notifies subscribers without false readiness', async () => {
    const container = new Container()
    container.register.mockRejectedValueOnce(new Error('Denied'))
    const listener = vi.fn()
    stops.push(subscribeOfflineShell(listener))
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'error', updateFailed: false, updateReady: false })
    expect(listener).toHaveBeenCalled()
  })

  it('does not register in development or apply stale completion after cleanup', async () => {
    const container = new Container()
    stops.push(startOfflineShell({ production: false, container: container.asContainer() }))
    expect(getOfflineShellStatus().state).toBe('development')
    expect(container.register).not.toHaveBeenCalled()
    const stop = startOfflineShell({ production: true, container: container.asContainer() })
    stop()
    await settle()
    expect(container.register).not.toHaveBeenCalled()
  })
})
