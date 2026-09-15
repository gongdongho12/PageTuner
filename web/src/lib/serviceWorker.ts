export interface OfflineShellStatus {
  state: 'development' | 'pending' | 'ready' | 'error'
  /** An existing offline shell remains usable even if installing a newer version fails. */
  updateFailed: boolean
}

let snapshot: OfflineShellStatus = { state: 'development', updateFailed: false }
const listeners = new Set<() => void>()
let generation = 0

export function getOfflineShellStatus(): OfflineShellStatus { return snapshot }
export function subscribeOfflineShell(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function publish(state: OfflineShellStatus['state'], updateFailed = false): void {
  if (snapshot.state === state && snapshot.updateFailed === updateFailed) return
  snapshot = { state, updateFailed }
  listeners.forEach(listener => listener())
}

function isShellWorker(worker: ServiceWorker | null | undefined): worker is ServiceWorker {
  if (!worker) return false
  try { return new URL(worker.scriptURL).pathname === '/sw.js' } catch { return false }
}

/**
 * Registering is not readiness: install's cache.addAll must succeed and the worker must activate.
 * Existing active shells survive update failures. The generated worker intentionally does not skipWaiting.
 */
export function startOfflineShell(options: {
  production: boolean
  container?: ServiceWorkerContainer
}): () => void {
  const version = ++generation
  const cleanups: Array<() => void> = []
  let stopped = false
  let registration: ServiceWorkerRegistration | undefined
  let updateFailed = false
  const watched = new Set<ServiceWorker>()
  const current = () => !stopped && version === generation
  const container = options.container ?? (typeof navigator !== 'undefined' ? navigator.serviceWorker : undefined)
  const ready = () => {
    const active = registration?.active
    return isShellWorker(active) && active.state === 'activated'
  }

  function show(): void {
    if (current()) publish(ready() ? 'ready' : 'pending', ready() && updateFailed)
  }

  function failed(): void {
    if (!current()) return
    updateFailed = true
    publish(ready() ? 'ready' : 'error', ready())
  }

  function watch(worker: ServiceWorker | null | undefined): void {
    if (!isShellWorker(worker) || watched.has(worker)) return
    watched.add(worker)
    let wasActivated = worker.state === 'activated'
    const changed = () => {
      if (!current()) return
      if (worker.state === 'activated') {
        wasActivated = true
        updateFailed = false
        show()
      } else if (worker.state === 'redundant') {
        // Retiring a previously active version after successful activation is not an install failure.
        if (wasActivated) show()
        else failed()
      } else show()
    }
    worker.addEventListener('statechange', changed)
    cleanups.push(() => worker.removeEventListener('statechange', changed))
    changed()
  }

  function observe(value: ServiceWorkerRegistration): void {
    registration = value
    const update = () => {
      if (!current()) return
      watch(value.installing)
      watch(value.waiting)
      watch(value.active)
      // A redundant installing worker has already published failure; don't overwrite it with pending.
      if (!updateFailed) show()
    }
    value.addEventListener('updatefound', update)
    cleanups.push(() => value.removeEventListener('updatefound', update))
    update()
  }

  if (!options.production) publish('development')
  else if (!container) publish('error')
  else {
    publish('pending')
    const changed = () => { if (current() && registration) observeCurrentWorkers() }
    const observeCurrentWorkers = () => {
      watch(registration?.active)
      if (!updateFailed) show()
    }
    container.addEventListener('controllerchange', changed)
    cleanups.push(() => container.removeEventListener('controllerchange', changed))
    void (async () => {
      try {
        const previous = await container.getRegistration('/')
        if (!current()) return
        if (previous) observe(previous)
        const registered = await container.register('/sw.js')
        if (!current()) return
        if (registered !== previous) observe(registered)
        else {
          watch(registered.installing)
          watch(registered.waiting)
          watch(registered.active)
          if (!updateFailed) show()
        }
      } catch { failed() }
    })()
  }

  return () => {
    stopped = true
    cleanups.forEach(cleanup => cleanup())
  }
}
