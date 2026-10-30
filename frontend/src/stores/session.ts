import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, onUnauthorized, setOutlet } from '../lib/api'
import { accessToken, endSession, localDev } from '../lib/auth'
import { supabase } from '../lib/supabase'
import { createRealtime, type Realtime } from '../lib/realtime'
import { ROLES, type Role } from '../lib/roles'

export interface Me { staffId: string; name: string; role: Role; outletId: string; outletName: string }
export interface Settings { name: string; gstPercent: number; serviceChargePercent: number; address?: string; phone?: string; gstin?: string; fssaiLicenseNo?: string }

const TERMINAL = 'tw-terminal'
const read = (k: string) => { try { return sessionStorage.getItem(k) } catch { return null } }

export const useSession = defineStore('session', () => {
  const ready = ref(false)
  const me = ref<Me | null>(null)
  const settings = ref<Settings>({ name: '', gstPercent: 5, serviceChargePercent: 0 })
  const counters = ref<{ id: string; name: string }[]>([])
  const counterId = ref(read(TERMINAL) || '')
  let rt: Realtime | null = null

  const role = computed<Role>(() => me.value?.role ?? 'kitchen')
  const can = computed(() => ROLES[role.value].can)
  const views = computed(() => ROLES[role.value].views)
  const counter = computed(() => counters.value.find(c => c.id === counterId.value) ?? counters.value[0])
  const realtime = () => rt

  function setCounter(id: string) { counterId.value = id; try { sessionStorage.setItem(TERMINAL, id) } catch { /* private mode */ } }

  async function loadOutlet() { settings.value = await api.get('/outlet') }
  async function loadCounters() { if (can.value.pay) counters.value = await api.get('/counters') }

  // The API has no "who am I" call, so the role is read from what each role may open: /staff is owner-only, /counters is owner and cashier.
  const allowed = (path: string) => api.get(path).then(() => true, (e: any) => (e.status === 403 ? false : Promise.reject(e)))
  async function load() {
    const outlet = await api.get('/outlet')
    setOutlet(outlet.id)
    const r: Role = (await allowed('/staff')) ? 'owner' : (await allowed('/counters')) ? 'cashier' : 'kitchen'
    me.value = { staffId: '', name: '', role: r, outletId: outlet.id, outletName: outlet.name }
    settings.value = outlet
    await loadCounters()
    if (!counters.value.find(c => c.id === counterId.value)) counterId.value = counters.value[0]?.id ?? ''
    rt?.stop()
    rt = createRealtime(async () => {
      return { Authorization: 'Bearer ' + ((await accessToken()) ?? ''), 'X-Outlet-Id': me.value!.outletId }
    })
    rt.start()
  }

  async function init() {
    if (await accessToken()) { try { await load() } catch { me.value = null } }
    ready.value = true
    if (!localDev) supabase.auth.onAuthStateChange((ev) => { if (ev === 'SIGNED_OUT') clear() })
  }
  function clear() { rt?.stop(); rt = null; me.value = null; setOutlet('') }
  async function signOut() { await endSession(); clear() }
  onUnauthorized(() => { if (me.value) signOut() })

  return { ready, me, settings, counters, counter, counterId, role, can, views, realtime, init, load, loadOutlet, loadCounters, setCounter, signOut }
})
