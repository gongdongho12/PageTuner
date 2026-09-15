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
    expect(getOfflineShellStatus()).toEqual({ state: 'pending', updateFailed: false })
    worker.transition('installed')
    expect(getOfflineShellStatus().state).toBe('pending')
    container.registration.activate(worker)
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false })
  })

  it('reports a first-install cache failure even though register already resolved', async () => {
    const container = new Container()
    const worker = new Worker('installing')
    container.registration.installing = worker.asWorker()
    start(container)
    await settle()
    worker.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'error', updateFailed: false })
  })

  it('keeps an existing shell ready when registration or a new install fails', async () => {
    const existing = new Registration()
    const active = new Worker('activated')
    existing.active = active.asWorker()
    const container = new Container(existing.asRegistration(), existing)
    container.register.mockRejectedValueOnce(new Error('Offline update request'))
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true })
    const update = new Worker('installing')
    existing.begin(update)
    update.transition('redundant')
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: true })
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
    expect(getOfflineShellStatus()).toEqual({ state: 'ready', updateFailed: false })
  })

  it('reports registration failures and notifies subscribers without false readiness', async () => {
    const container = new Container()
    container.register.mockRejectedValueOnce(new Error('Denied'))
    const listener = vi.fn()
    stops.push(subscribeOfflineShell(listener))
    start(container)
    await settle()
    expect(getOfflineShellStatus()).toEqual({ state: 'error', updateFailed: false })
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
