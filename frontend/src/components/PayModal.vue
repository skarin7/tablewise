<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import ModalBox from './ModalBox.vue'
import CustomerScreen from './CustomerScreen.vue'
import Seg from './Seg.vue'
import Icon from './Icon.vue'
import Totals from './Totals.vue'
import { api } from '../lib/api'
import { billOf } from '../lib/bill'
import type { Screen } from '../lib/display'
import { cdown, inr, inr2 } from '../lib/format'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const props = defineProps<{ order: any; onDone: () => void }>()
const s = useSession(), ui = useUi()
const o = props.order, b = billOf(o)
const method = ref<'upi_qr' | 'card' | 'cash'>('upi_qr')
const status = ref<'starting' | 'waiting' | 'failed' | 'expired'>('starting')
const payment = ref<any>(null)
const tendered = ref(''), approval = ref(''), busy = ref(false)
const displays = ref<any[]>([])
let paid = false, poll: any, off: (() => void)[] = []

const expiresAt = computed(() => (payment.value?.expiresAt ? new Date(payment.value.expiresAt).getTime() : 0))
const label = (m: string) => (m === 'upi_qr' ? 'UPI' : m === 'card' ? 'Card' : 'Cash')
const base = () => ({ counterId: s.counter?.id })

async function closePending() {
  const p = payment.value; payment.value = null
  if (p?.id) { try { await api.post(`/payments/${p.id}/cancel`) } catch { /* already settled or expired */ } }
}
async function start(m: typeof method.value) {
  await closePending()
  method.value = m; status.value = m === 'cash' ? 'waiting' : 'starting'
  if (m === 'cash') return
  try {
    const r = await api.post(`/orders/${o.id}/payments`, { method: m, ...base() })
    if (r.billStatus === 'paid') return settled(label(m)) // nothing to collect, e.g. a 100% discount
    // the start call returns only ids; the QR and its expiry are read back from the order's payments
    const list = await api.get(`/orders/${o.id}/payments`, { counterId: base().counterId })
    payment.value = list.payments.find((p: any) => p.id === r.paymentId)
    status.value = 'waiting'
  } catch (e: any) { status.value = 'failed'; ui.toast(e.message) }
}
function settled(mode: string, extra = '') {
  if (paid) return
  paid = true; payment.value = null
  ui.close(); props.onDone()
  ui.toast(`${inr(o.total)} received by ${mode} for #${o.orderNo}.${extra}`)
}
// Webhooks and the reconciliation poll mark the payment on the server; the screen only asks whether the order is paid yet.
async function check() {
  if (paid) return
  try {
    const d = await api.get(`/orders/${o.id}/payments`, { counterId: base().counterId })
    if (d.billStatus === 'paid') return settled(label(method.value))
    const p = payment.value && d.payments.find((x: any) => x.id === payment.value.id)
    if (p?.status === 'expired') status.value = 'expired'
    else if (p && ['failed', 'cancelled'].includes(p.status)) status.value = 'failed'
  } catch { /* try again on the next tick */ }
}
async function cashOk() {
  const change = (parseFloat(tendered.value) || 0) - o.total
  if (change < 0) return ui.toast('Cash received is less than the bill')
  busy.value = true
  try { await api.post(`/orders/${o.id}/payments`, { method: 'cash', tenderedAmount: parseFloat(tendered.value), ...base() }); settled('Cash', change > 0 ? ` Return ${inr(change)} change.` : '') }
  catch (e: any) { ui.toast(e.message) } finally { busy.value = false }
}
async function cardOk() {
  if (!approval.value.trim()) return ui.toast('Enter the approval code from the card machine')
  busy.value = true
  try { await api.post(`/orders/${o.id}/payments`, { method: 'card', approvalCode: approval.value.trim(), ...base() }); settled('Card') }
  catch (e: any) { ui.toast(e.message) } finally { busy.value = false }
}

const quick = computed(() => { const out: number[] = []; for (const v of [o.total, Math.ceil(o.total / 100) * 100, Math.ceil(o.total / 500) * 500]) if (!out.includes(v)) out.push(v); return out })
const t = computed(() => parseFloat(tendered.value) || 0)
const display = computed(() => displays.value.filter(d => d.counterId === s.counter?.id))
const online = computed(() => display.value.find(d => d.online))
const preview = computed<Screen>(() => ({ mode: 'bill', name: s.settings.name, no: o.orderNo, method: method.value, status: status.value === 'starting' ? 'waiting' : status.value, qr: payment.value?.qrPayload, expiresAt: expiresAt.value,
  items: o.items.map((i: any) => ({ n: i.name, q: i.quantity, p: +i.unitPrice })), bill: b }))

onMounted(async () => {
  start('upi_qr')
  api.get<any[]>('/devices').then(d => { displays.value = d }).catch(() => {})
  const rt = s.realtime()
  if (rt && s.me) off.push(rt.subscribe(`/topic/outlets/${s.me.outletId}/orders`, e => {
    if (e.orderId !== o.id) return
    check()
  }))
  // expiry and failure are not announced on the orders channel, so ask while a QR is pending
  poll = setInterval(() => { if (method.value === 'upi_qr' && status.value === 'waiting') { check(); if (expiresAt.value && Date.now() > expiresAt.value) status.value = 'expired' } }, 3000)
})
onUnmounted(() => { clearInterval(poll); off.forEach(f => f()); if (!paid) closePending() }) // closing the dialog frees the QR and the display
</script>

<template>
  <ModalBox :title="`Collect payment · #${o.orderNo}`" wide>
    <div class="paygrid">
      <div class="stack" style="gap:14px">
        <div><div v-for="(i, k) in o.items" :key="k" class="tr"><span>{{ i.quantity }}× {{ i.name }}</span><span>{{ inr2(i.unitPrice * i.quantity) }}</span></div></div>
        <Totals :b="b" grand="To collect" />
        <div class="fld"><span class="lb">Payment method</span><Seg :model-value="method" label="Payment method" :options="[['upi_qr', 'UPI QR'], ['card', 'Card'], ['cash', 'Cash']]" @update:model-value="start($event as any)" /></div>
        <template v-if="method === 'upi_qr'">
          <div v-if="status === 'waiting' || status === 'starting'" class="status"><span class="pill info">Waiting for customer to scan</span><span v-if="expiresAt" class="sub">QR valid for <b>{{ cdown(Math.max(0, expiresAt - ui.now)) }}</b></span></div>
          <div v-else-if="status === 'failed'" class="status"><span class="pill bad">Payment failed</span><span class="sub">Ask the customer to try again.</span></div>
          <div v-else class="status"><span class="pill warn">QR expired</span><span class="sub">Generate a new code for the customer.</span></div>
          <button v-if="status === 'failed' || status === 'expired'" class="btn" type="button" @click="start('upi_qr')">Generate new QR</button>
        </template>
        <template v-else-if="method === 'card'">
          <div class="status"><span class="pill info">Waiting for the card machine</span><span class="sub">Customer taps, inserts or swipes.</span></div>
          <div class="fld"><label for="appr">Approval code</label><input id="appr" v-model="approval" placeholder="From the card machine slip" autocomplete="off"></div>
          <button class="btn primary" type="button" :disabled="busy" @click="cardOk">Confirm card payment</button>
        </template>
        <template v-else>
          <div class="fld"><label for="tend">Cash received (₹)</label><input id="tend" v-model="tendered" type="number" min="0" step="any" placeholder="Amount handed over"></div>
          <div class="chips"><button v-for="v in quick" :key="v" class="chip" type="button" @click="tendered = String(v)">{{ v === o.total ? 'Exact ' + inr(v) : inr(v) }}</button></div>
          <div class="tr"><span>Change to return</span><b>{{ t >= o.total ? inr2(t - o.total) : '–' }}</b></div>
        </template>
      </div>
      <div class="stack" style="gap:8px">
        <span class="lb">Customer display</span>
        <div class="status">
          <span v-if="online" class="pill good"><Icon name="screen" :size="14" /> Showing on {{ online.name }}</span>
          <template v-else-if="display.length"><span class="pill warn">{{ display[0].name }} is offline</span><span class="sub">Check the tablet is on and online.</span></template>
          <template v-else><span class="pill">No display paired to {{ s.counter?.name }}</span><router-link v-if="s.role === 'owner'" class="btn sm" to="/admin" @click="ui.close()">Pair a display</router-link></template>
        </div>
        <CustomerScreen :sc="preview" mini />
      </div>
    </div>
    <template #footer>
      <button class="btn" type="button" @click="ui.close()">Cancel</button>
      <button v-if="method === 'cash'" class="btn primary" type="button" :disabled="t < o.total || busy" @click="cashOk">Mark as paid</button>
    </template>
  </ModalBox>
</template>
