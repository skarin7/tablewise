import { Client, type StompSubscription } from '@stomp/stompjs'

type Cb = (body: any) => void
const WS = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws'

/**
 * One STOMP client per tab with automatic reconnect. Subscriptions are remembered and restored after a drop,
 * and `onReconnect` fires so screens can re-fetch current state (README 12.4).
 */
export function createRealtime(getHeaders: () => Promise<Record<string, string>>, onError?: () => void) {
  const subs = new Map<string, { cbs: Set<Cb>; sub?: StompSubscription }>()
  const reconnect = new Set<() => void>()
  const status = { connected: false }
  let first = true
  const client = new Client({
    brokerURL: WS, reconnectDelay: 3000, heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
    beforeConnect: async () => { client.connectHeaders = await getHeaders() },
    onConnect: () => {
      status.connected = true
      subs.forEach((s, dest) => { s.sub = client.subscribe(dest, m => { let b: any; try { b = JSON.parse(m.body) } catch { return } s.cbs.forEach(cb => cb(b)) }) })
      if (!first) reconnect.forEach(f => f())
      first = false
    },
    onStompError: () => onError?.(), // the server refuses revoked devices and forbidden subscriptions with an ERROR frame
    onWebSocketClose: () => { status.connected = false; subs.forEach(s => { s.sub = undefined }) }
  })
  return {
    status,
    start: () => client.activate(),
    stop: () => client.deactivate(),
    publish: (destination: string, body = '') => { if (client.connected) client.publish({ destination, body }) },
    onReconnect(f: () => void) { reconnect.add(f); return () => reconnect.delete(f) },
    subscribe(dest: string, cb: Cb) {
      let s = subs.get(dest)
      if (!s) { s = { cbs: new Set() }; subs.set(dest, s); if (client.connected) { const e = s; e.sub = client.subscribe(dest, m => { let b: any; try { b = JSON.parse(m.body) } catch { return } e.cbs.forEach(c => c(b)) }) } }
      s.cbs.add(cb)
      return () => { const x = subs.get(dest); if (!x) return; x.cbs.delete(cb); if (!x.cbs.size) { try { x.sub?.unsubscribe() } catch { /* socket already closed */ } subs.delete(dest) } }
    }
  }
}
export type Realtime = ReturnType<typeof createRealtime>
