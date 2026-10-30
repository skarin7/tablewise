<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import CustomerScreen from '../components/CustomerScreen.vue'
import { api, setDeviceToken } from '../lib/api'
import { fromMessage, type Screen } from '../lib/display'
import { cdown } from '../lib/format'
import { createRealtime, type Realtime } from '../lib/realtime'
import { useUi } from '../stores/ui'

// The tablet never signs in: it pairs with a 6-digit code, keeps its device token in localStorage and listens to one counter (README 7.6).
interface Device { token: string; counterId: string }
const DEV = 'tw-device', KEY = 'tw-device-key'
const get = (k: string) => { try { return localStorage.getItem(k) } catch { return null } }
const set = (k: string, v: string | null) => { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v) } catch { /* storage blocked */ } }

const ui = useUi()
const device = ref<Device | null>(JSON.parse(get(DEV) || 'null'))
const pair = ref<{ code: string; expiresAt: number } | null>(null)
const screen = ref<Screen>({ mode: 'idle' })
const err = ref('')
const code = computed(() => (pair.value ? pair.value.code.slice(0, 3) + ' ' + pair.value.code.slice(3) : ''))
let rt: Realtime | null = null, timers: any[] = [], idle: any, lock: any

const deviceKey = () => get(KEY) || (set(KEY, crypto.randomUUID()), get(KEY)!)

async function newCode() {
  try {
    const r = await api.post('/devices/pairing-codes', { deviceKey: deviceKey() })
    pair.value = { code: r.code, expiresAt: new Date(r.expiresAt).getTime() }; err.value = ''
  } catch (e: any) { err.value = e.message }
}
async function pollPairing() {
  try {
    const r = await api.get('/devices/pairing-codes/' + deviceKey())
    if (r?.token) { const d: Device = { token: r.token, counterId: r.counterId }; set(DEV, JSON.stringify(d)); device.value = d; connect() }
    else if (r?.code && pair.value?.code !== r.code) pair.value = { code: r.code, expiresAt: new Date(r.expiresAt).getTime() }
  } catch (e: any) { err.value = e.message }
}
function show(sc: Screen) {
  screen.value = sc; clearTimeout(idle)
  if (sc.mode === 'paid' || sc.mode === 'failed') idle = setTimeout(() => { screen.value = { mode: 'idle' } }, 9000) // back to the welcome screen
}
// The backend has no "current display state" call, so after a reconnect the tablet waits for the next message.
const sync = () => { screen.value = { mode: 'idle' } }
function revoked() { set(DEV, null); setDeviceToken(''); device.value = null; rt?.stop(); rt = null; screen.value = { mode: 'idle' }; newCode(); timers.push(setInterval(pollPairing, 3000)) }
function connect() {
  const d = device.value!; setDeviceToken(d.token)
  rt?.stop()
  rt = createRealtime(async () => ({ 'X-Device-Token': d.token }), revoked)
  rt.subscribe(`/topic/counters/${d.counterId}/display`, m => show(fromMessage(m)))
  rt.onReconnect(sync)
  rt.start()
  timers.push(setInterval(() => rt?.publish('/app/heartbeat'), 5000)) // server marks the device online from this
  ;(navigator as any).wakeLock?.request('screen').then((l: any) => { lock = l }).catch(() => {}) // keep the screen awake
}

onMounted(() => {
  if (device.value) return connect()
  newCode(); timers.push(setInterval(pollPairing, 3000))
  timers.push(setInterval(() => { if (!device.value && (!pair.value || pair.value.expiresAt < Date.now())) newCode() }, 1000))
})
onUnmounted(() => { timers.forEach(clearInterval); clearTimeout(idle); rt?.stop(); lock?.release?.() })
</script>

<template>
  <div class="csw">
    <section v-if="!device" class="cs" aria-label="Pair this display">
      <div class="top"><div class="rest"><span class="mark"><Icon name="screen" :size="18" /></span>Customer display</div><span class="pill">Not paired</span></div>
      <div class="pp">
        <p class="msg">Pair this screen with a billing counter</p>
        <div class="paircode" :aria-label="'Pairing code ' + pair?.code">{{ code || '··· ···' }}</div>
        <p class="sub">On the cashier computer, open <b>Admin → Customer displays → Add customer display</b> and enter this code. Nobody needs to sign in on this tablet.</p>
        <p v-if="pair" class="sub">New code in <b>{{ cdown(Math.max(0, pair.expiresAt - ui.now)) }}</b></p>
        <p v-if="err" class="err" role="alert">{{ err }}</p>
      </div>
    </section>
    <template v-else>
      <CustomerScreen :sc="screen" />
      
    </template>
  </div>
</template>
