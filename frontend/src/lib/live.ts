import { onMounted, onUnmounted, ref } from 'vue'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

/**
 * Fetch on mount, re-fetch on STOMP events for the given channels and after a reconnect.
 * The sockets only say "something changed"; the screen asks the API for the truth.
 */
export function useResource<T>(fetcher: () => Promise<T>, channels: ('orders' | 'online' | 'inventory')[] = [], initial: T | null = null) {
  const s = useSession(), ui = useUi()
  const data = ref<T | null>(initial) as { value: T | null }
  const loading = ref(true)
  const error = ref('')
  let seq = 0
  async function reload() {
    const mine = ++seq
    try { const d = await fetcher(); if (mine === seq) { data.value = d; error.value = '' } }
    catch (e: any) { if (mine === seq) { error.value = e.message; if (!data.value) ui.toast(e.message) } }
    finally { if (mine === seq) loading.value = false }
  }
  const offs: (() => void)[] = []
  onMounted(() => {
    reload()
    const rt = s.realtime(), id = s.me?.outletId
    if (!rt || !id) return
    let t: any
    const soon = () => { clearTimeout(t); t = setTimeout(reload, 150) } // a burst of events becomes one fetch
    // The server closes the socket on a forbidden SUBSCRIBE, so only ask for channels this role may hear.
    const allowed = (c: string) => c === 'orders' || (c === 'online' && s.role !== 'kitchen') || (c === 'inventory' && s.role === 'owner')
    channels.filter(allowed).forEach(c => offs.push(rt.subscribe(`/topic/outlets/${id}/${c}`, soon)))
    offs.push(rt.onReconnect(reload))
  })
  onUnmounted(() => offs.forEach(f => f()))
  return { data, loading, error, reload }
}
