import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import type { SourceFavoriteClient } from '../lib/sourceFavoriteApi'
import { createSourceFavoriteStore } from '../lib/sourceFavoriteStore'
import { createSourceFavoriteController, type SourceFavoriteController, type SourceFavoriteState } from '../lib/sourceFavoriteSync'

type Session = { username: string; api: SourceFavoriteClient | null; controller: SourceFavoriteController; state: SourceFavoriteState }
const SourceFavoriteContext = createContext<Session | null>(null)
export function SourceFavoriteProvider({ username, client, children }: { username: string; client: SourceFavoriteClient | null; children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  useEffect(() => {
    if (!username) { setSession(null); return }
    const store = createSourceFavoriteStore(username), controller = createSourceFavoriteController(store, client)
    let active = true
    const update = (state: SourceFavoriteState) => { if (active) setSession({ username, api: client, controller, state }) }
    update(controller.snapshot()); const unsubscribe = controller.subscribe(update)
    const retry = () => { void controller.drain() }; retry()
    const timer = setInterval(retry, 30_000); window.addEventListener('online', retry); window.addEventListener('focus', retry)
    return () => { active = false; clearInterval(timer); window.removeEventListener('online', retry); window.removeEventListener('focus', retry); unsubscribe(); void controller.close().finally(() => store.close()) }
  }, [username, client])
  return <SourceFavoriteContext.Provider value={session?.username === username && session.api === client ? session : null}>{children}</SourceFavoriteContext.Provider>
}
export function useSourceFavorites() { return useContext(SourceFavoriteContext) }
