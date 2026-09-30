type Event = { username: string; documentId: string; dbName: string }
const listeners = new Set<{ key: Event; listener: () => void }>()
let channel: BroadcastChannel | undefined
const db = (name?: string) => name ?? 'pageturner-device-reading'
function dispatch(event: Event) { for (const entry of listeners) if (entry.key.username === event.username && entry.key.documentId === event.documentId && entry.key.dbName === event.dbName) { try { entry.listener() } catch { /* Observers cannot turn a committed write into a reported storage failure. */ } } }
function openChannel() {
  if (!channel && typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') {
    try { channel = new BroadcastChannel('pageturner-reading-notes') } catch { return }
    channel.onmessage = ({ data }: MessageEvent<unknown>) => {
      const event = data as Event
      if (event && typeof event.username === 'string' && event.username.length <= 200 && typeof event.documentId === 'string' && event.documentId.length <= 1000 && typeof event.dbName === 'string') dispatch(event)
    }
  }
}
/** Only document identifiers cross tabs; note bodies and credentials never do. */
export function notifyReadingNotes(username: string, documentId: string, dbName?: string): void {
  const event = { username, documentId, dbName: db(dbName) }
  dispatch(event)
  try {
    if (channel) channel.postMessage(event)
    else if (typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined') {
      const outgoing = new BroadcastChannel('pageturner-reading-notes'); outgoing.postMessage(event); outgoing.close()
    }
  } catch { /* Other tabs still discover durable changes on their next sync. */ }
}
export function subscribeReadingNotes(username: string, documentId: string, listener: () => void, dbName?: string): () => void {
  const entry = { key: { username: username.trim(), documentId, dbName: db(dbName) }, listener }
  listeners.add(entry); openChannel()
  return () => { listeners.delete(entry); if (!listeners.size) { channel?.close(); channel = undefined } }
}
