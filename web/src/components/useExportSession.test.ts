import { describe, expect, it } from 'vitest'
import { ExportSession } from './useExportSession'

describe('terminal export issuance leases', () => {
  it('rejects retained issuance callbacks after cleanup, including a StrictMode replacement', () => {
    const owner = {}, first = new ExportSession(owner, 'https://example.test', () => true)
    const begin = () => first.issue(), current = begin()
    first.close()
    const second = new ExportSession(owner, 'https://example.test', () => true)
    expect(current()).toBe(false)
    expect(begin).toThrow()
    expect(second.issue()()).toBe(true)
  })

  it.each(['account', 'client', 'origin', 'selection'])('does not revive after %s A -> B -> A', () => {
    const first = {}, second = {}
    let selected = first
    const lease = new ExportSession(first, 'https://example.test', () => selected === first)
    const current = lease.issue(), begin = () => lease.issue()
    selected = second
    expect(current()).toBe(false)
    selected = first
    expect(current()).toBe(false)
    expect(begin).toThrow()
  })

  it('checks current ownership before issuing, even when no earlier reader was created', () => {
    let mounted = true
    const lease = new ExportSession({}, 'https://example.test', () => mounted)
    const begin = () => lease.issue('stale')
    mounted = false
    expect(begin).toThrow('stale')
    mounted = true
    expect(begin).toThrow('stale')
  })
})
