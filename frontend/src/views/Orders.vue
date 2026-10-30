<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import Icon from '../components/Icon.vue'
import OrderActions from '../components/OrderActions.vue'
import OrderCard from '../components/OrderCard.vue'
import PageHead from '../components/PageHead.vue'
import PayPill from '../components/PayPill.vue'
import { api } from '../lib/api'
import { elapsed, fmtDay, fmtTime, inr, STATUS_LABEL, STATUS_PILL, ts } from '../lib/format'
import { useResource } from '../lib/live'
import { canAdvance, canCancel, canPay, isOpen, waitingNote, whereLabel } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const s = useSession(), ui = useUi()
const kitchen = computed(() => !s.can.prices)
const type = ref('all'), unpaid = ref(false), q = ref('')
const { data: orders, loading, reload } = useResource(() => api.get<any[]>('/orders'), ['orders'])
// Search goes to the server so it can find older orders, platform ids and phone numbers.
const found = ref<any[] | null>(null)
let t: any
async function runSearch() { const v = q.value.trim(); found.value = v ? (await api.get<any[]>('/orders', { q: v }).catch(() => [])) : null }
watch(q, () => { clearTimeout(t); t = setTimeout(runSearch, 250) })
const refresh = () => { reload(); if (q.value.trim()) runSearch() }

const list = computed(() => (orders.value ?? []).filter(o => (type.value === 'all' || o.orderType === type.value) && (!unpaid.value || kitchen.value || (o.paymentStatus === 'unpaid' && o.status !== 'cancelled')) && (!q.value.trim() || !found.value || found.value.some(f => f.id === o.id))))
const defs = computed<[string, string][]>(() => [['new', 'New'], ['preparing', 'Preparing'], ['ready', 'Ready'], ...(kitchen.value ? [] : [['done', 'Completed'] as [string, string]])])
const cols = computed(() => defs.value.map(([k, label]) => {
  let items = list.value.filter(o => (k === 'done' ? o.status === 'completed' || o.status === 'cancelled' : o.status === k))
  items = items.sort((a, b) => (k === 'done' ? ts(b.placedAt) - ts(a.placedAt) : ts(a.placedAt) - ts(b.placedAt)))
  const total = items.length
  return { k, label, total, items: k === 'done' ? items.slice(0, 10) : items }
}))
const results = computed(() => (found.value ?? []).filter(o => !kitchen.value || isOpen(o)).sort((a, b) => Number(isOpen(b)) - Number(isOpen(a)) || ts(b.placedAt) - ts(a.placedAt)))
const shown = computed(() => results.value.slice(0, 8))

function statusLine(o: any) {
  const since = o.statusChangedAt ? elapsed(o.statusChangedAt, ui.now) : ''
  const eta = o.prepMinutes ? (() => { const rem = o.prepMinutes - Math.round((ui.now - ts(o.placedAt)) / 60000); return rem > 0 ? `About ${rem} min left` : `Running ${-rem} min over the usual prep time` })() : ''
  if (o.status === 'new') return { line: 'Waiting for the kitchen to start', sub: eta }
  if (o.status === 'preparing') return { line: 'Kitchen is preparing' + (since ? ` (for ${since})` : ''), sub: eta }
  if (o.status === 'ready') return { line: 'Ready' + (since ? ` for ${since}` : '') + ', ' + (o.orderType === 'delivery' ? 'waiting for the rider' : o.orderType === 'takeaway' ? 'waiting for the customer' : 'waiting to be served'),
    sub: o.orderType !== 'dine_in' && o.paymentStatus !== 'paid' ? 'Collect payment before ' + (o.orderType === 'takeaway' ? 'handing over' : 'dispatch') : '' }
  if (o.status === 'completed') return { line: (o.orderType === 'dine_in' ? 'Served' : o.orderType === 'takeaway' ? 'Handed over' : 'Dispatched') + (o.completedAt ? ' at ' + fmtTime(o.completedAt) : ''), sub: 'Placed ' + fmtDay(o.placedAt) }
  return { line: 'Cancelled', sub: 'Placed ' + fmtDay(o.placedAt) }
}
const hasActs = (o: any) => canAdvance(o, s.role).ok || canPay(o, s.role).ok || canCancel(o, s.role).ok

const input = ref<HTMLInputElement>()
function key(e: KeyboardEvent) {
  const a = document.activeElement as HTMLElement | null, typing = a && /^(INPUT|SELECT|TEXTAREA)$/.test(a.tagName)
  if (e.key === '/' && !typing && !ui.modal) { e.preventDefault(); input.value?.focus(); input.value?.select() }
  if (e.key === 'Escape' && a === input.value && q.value) q.value = ''
}
onMounted(() => window.addEventListener('keydown', key))
onUnmounted(() => window.removeEventListener('keydown', key))
const sub = computed(() => s.role === 'kitchen' ? 'Kitchen display. Start each ticket and mark it ready for pickup. Tickets older than 20 minutes turn red.'
  : s.role === 'cashier' ? 'Front of house. The kitchen moves tickets to Ready, then you serve, hand over or dispatch them and collect payment.' : 'Full control of every order. Orders older than 20 minutes turn red.')
</script>

<template>
  <PageHead :title="kitchen ? 'Kitchen tickets' : 'Orders'" :sub="sub">
    <div class="seg" role="group" aria-label="Order type"><button v-for="x in [['all', 'All'], ['dine_in', 'Dine-in'], ['takeaway', 'Takeaway'], ['delivery', 'Delivery']]" :key="x[0]" :aria-pressed="type === x[0]" @click="type = x[0]">{{ x[1] }}</button></div>
    <button v-if="!kitchen" class="chip" :aria-pressed="unpaid" @click="unpaid = !unpaid">Unpaid only</button>
  </PageHead>
  <div class="find"><div class="search"><Icon name="search" :size="18" /><input id="oq" ref="input" v-model="q" type="search" inputmode="search" autocomplete="off" :placeholder="kitchen ? 'Find an order number' : 'Order number, phone or customer name'" aria-label="Find an order"></div>
    <button v-if="q" class="btn" @click="q = ''; input?.focus()">Clear</button><span class="sub">Press <kbd>/</kbd> to search</span></div>

  <div v-if="q.trim() && found" class="card res" aria-live="polite">
    <template v-if="!shown.length"><div class="empty">No order matches “{{ q.trim() }}”. Check the number on the rider’s app or slip.</div></template>
    <template v-else>
      <div class="row between"><h2>{{ results.length === 1 ? '1 order found' : results.length + ' orders found' }}</h2><span v-if="results.length > shown.length" class="sub">Showing the latest 8. Type more digits to narrow it down.</span></div>
      <div v-for="o in shown" :key="o.id" class="rr2" :data-result="o.orderNo">
        <div class="big">#{{ o.orderNo }}</div>
        <div class="stack" style="gap:4px;min-width:0">
          <div class="row wrap"><span class="pill" :class="STATUS_PILL[o.status]">{{ STATUS_LABEL[o.status] }}</span><span class="pill" :class="o.orderType === 'dine_in' ? 'accent' : o.orderType === 'takeaway' ? 'info' : 'warn'">{{ whereLabel(o) }}</span>
            <template v-if="s.can.prices"><PayPill :o="o" /><span class="mono">{{ inr(o.total) }}</span></template></div>
          <div class="line">{{ statusLine(o).line }}</div>
          <div class="sub">{{ statusLine(o).sub ? statusLine(o).sub + ' · ' : '' }}{{ o.items.map((i: any) => i.quantity + '× ' + i.name).join(', ') }}<template v-if="o.billToName && s.can.prices"> · {{ o.billToName }}</template></div>
        </div>
        <div v-if="hasActs(o)" class="acts"><OrderActions :o="o" @changed="refresh" /></div><div v-else></div>
      </div>
    </template>
  </div>

  <div v-if="loading" class="loading">Loading orders…</div>
  <div v-else class="board" :style="kitchen ? 'grid-template-columns:repeat(auto-fit,minmax(240px,1fr))' : undefined">
    <section v-for="c in cols" :key="c.k" class="col" :data-s="c.k" :aria-label="c.label">
      <h3><span>{{ c.label }}</span><span class="pill">{{ c.total }}</span></h3>
      <OrderCard v-for="o in c.items" :key="o.id" :o="o" @changed="refresh" />
      <div v-if="!c.items.length" class="empty small">Nothing here</div>
      <p v-if="c.total > c.items.length" class="sub" style="text-align:center">Showing latest {{ c.items.length }} of {{ c.total }}</p>
    </section>
  </div>
</template>
