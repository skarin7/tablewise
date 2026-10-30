<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'
import KotModal from '../components/KotModal.vue'
import PageHead from '../components/PageHead.vue'
import PayModal from '../components/PayModal.vue'
import Totals from '../components/Totals.vue'
import { api } from '../lib/api'
import { calc } from '../lib/bill'
import { inr, TYPE, validPhone } from '../lib/format'
import { useResource } from '../lib/live'
import { dishState } from '../lib/stock'
import { useCart } from '../stores/cart'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const s = useSession(), ui = useUi(), c = useCart()
const cat = ref('all'), q = ref(''), veg = ref(false), busy = ref(false)
const { data: d, loading } = useResource(async () => { const [menu, tables] = await Promise.all([api.get('/menu'), api.get<any[]>('/tables')]); return { ...menu, tables } }, ['orders', 'inventory'])

const dish = (id: string) => d.value?.items.find((m: any) => m.id === id)
const items = computed(() => (d.value?.items ?? []).filter((m: any) => (cat.value === 'all' || m.categoryId === cat.value) && (!veg.value || m.veg) && (!q.value.trim() || m.name.toLowerCase().includes(q.value.trim().toLowerCase()))))
const lines = computed(() => c.items.filter(l => dish(l.id)).map(l => ({ ...l, m: dish(l.id) })))
const bill = computed(() => calc(lines.value.map(l => ({ price: +l.m.price, qty: l.qty })), { type: c.dtype, val: parseFloat(c.dval) || 0 }, s.settings))
const nQty = computed(() => c.items.reduce((t, l) => t + l.qty, 0))
const table = computed(() => d.value?.tables.find((t: any) => t.id === c.tableId))

function add(id: string) {
  const m = dish(id), have = c.qty(id)
  if (m.portionsLeft != null && have + 1 > m.portionsLeft) return ui.toast(`Not enough stock for another ${m.name}`)
  const l = c.items.find(x => x.id === id)
  l ? l.qty++ : c.items.push({ id, qty: 1 })
}
function dec(id: string) { const l = c.items.find(x => x.id === id); if (!l) return; l.qty--; if (l.qty <= 0) c.items = c.items.filter(x => x !== l) }
function setType(t: any) { c.type = t; if (t !== 'dine_in') c.tableId = '' }

async function place(payNow: boolean) {
  if (!c.items.length) return ui.toast('Add at least one dish first')
  if (c.type === 'dine_in' && !c.tableId) return ui.toast('Pick a table for this dine-in order')
  if (c.type === 'delivery' && (!c.name.trim() || !validPhone(c.phone))) return ui.toast('Delivery needs a customer name and a 10-digit phone number')
  if (c.phone && !validPhone(c.phone)) return ui.toast('Phone number should have 10 digits')
  busy.value = true
  try {
    const o = await api.post('/orders', { orderType: c.type, tableId: c.type === 'dine_in' ? c.tableId : undefined, counterId: s.counter?.id, customerName: c.name.trim() || (c.phone ? 'Guest' : undefined),
      customerPhone: c.phone.trim() || undefined, discountType: c.dtype, discountValue: parseFloat(c.dval) || 0, items: c.items.map(l => ({ menuItemId: l.id, quantity: l.qty })) })
    c.clear()
    payNow ? ui.open(PayModal, { order: o, onDone: () => {} }) : ui.open(KotModal, { order: o })
  } catch (e: any) { ui.toast(e.message) } finally { busy.value = false } // the server re-checks stock, so a clash shows "Not enough chicken"
}
const tocart = () => document.getElementById('cart')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
</script>

<template>
  <PageHead title="New order" sub="Take an order at the counter or table. Stock updates the moment it is sent.">
    <div class="seg" role="group" aria-label="Order type"><button v-for="k in ['dine_in', 'takeaway', 'delivery']" :key="k" :aria-pressed="c.type === k" @click="setType(k)">{{ TYPE[k] }}</button></div>
  </PageHead>
  <div v-if="loading" class="loading">Loading menu…</div>
  <div v-else class="pos">
    <div class="stack">
      <div v-if="c.type === 'dine_in'" class="card"><span class="lb">Select table</span>
        <div class="tbls"><button v-for="t in d.tables" :key="t.id" class="tb" :class="{ busy: t.busy }" :aria-pressed="c.tableId === t.id" @click="c.tableId = c.tableId === t.id ? '' : t.id">{{ t.label }}<small v-if="t.busy">busy</small></button></div></div>
      <div class="row wrap"><div class="search"><Icon name="search" :size="16" /><input v-model="q" type="search" placeholder="Search dishes" aria-label="Search dishes"></div>
        <button class="chip" :aria-pressed="veg" @click="veg = !veg"><i class="vm"></i> Veg only</button></div>
      <div class="chips"><button class="chip" :aria-pressed="cat === 'all'" @click="cat = 'all'">All</button><button v-for="x in d.categories" :key="x.id" class="chip" :aria-pressed="cat === x.id" @click="cat = x.id">{{ x.name }}</button></div>
      <div class="items">
        <div v-if="!items.length" class="empty" style="grid-column:1/-1">No dishes match. Clear the filters or try another search.</div>
        <button v-for="m in items" :key="m.id" class="item" :disabled="['off', 'out'].includes(dishState(m))" @click="add(m.id)">
          <span v-if="c.qty(m.id)" class="qb">{{ c.qty(m.id) }}</span>
          <span><i class="vm" :class="{ nv: !m.veg }" :title="m.veg ? 'Vegetarian' : 'Non-vegetarian'"></i></span><span class="nm">{{ m.name }}</span>
          <span class="meta"><span class="pr">{{ inr(m.price) }}</span>
            <span v-if="dishState(m) === 'off'" class="pill">Unavailable</span><span v-else-if="dishState(m) === 'out'" class="pill bad">Out of stock</span><span v-else-if="dishState(m) === 'low'" class="pill warn">{{ m.portionsLeft }} left</span></span>
        </button>
      </div>
    </div>
    <aside id="cart" class="card cart">
      <div class="row between"><h2>Current order</h2></div>
      <div class="sub" style="margin-top:-8px">{{ TYPE[c.type] }}<template v-if="c.type === 'dine_in'"> · {{ table ? 'Table ' + table.label : 'no table selected' }}</template></div>
      <div>
        <div v-for="l in lines" :key="l.id" class="cl"><div><div class="nm"><i class="vm" :class="{ nv: !l.m.veg }"></i> {{ l.m.name }}</div><div class="sub">{{ inr(l.m.price) }} each</div></div>
          <div class="step"><button :aria-label="'Remove one ' + l.m.name" @click="dec(l.id)">−</button><span>{{ l.qty }}</span><button :aria-label="'Add one ' + l.m.name" @click="add(l.id)">+</button></div><div class="lt">{{ inr(l.m.price * l.qty) }}</div></div>
        <div v-if="!lines.length" class="empty small">Tap a dish to start the order.</div>
      </div>
      <div class="fg"><div class="fld"><label for="cname">Customer name</label><input id="cname" v-model="c.name" placeholder="Optional" autocomplete="off"></div>
        <div class="fld"><label for="cphone">Phone</label><input id="cphone" v-model="c.phone" placeholder="10 digits" inputmode="numeric" autocomplete="off"></div></div>
      <div class="fg"><div class="fld"><label for="dtype">Discount</label><select id="dtype" v-model="c.dtype"><option value="flat">Flat ₹</option><option value="percent">Percent %</option></select></div>
        <div class="fld"><label for="dval">Amount</label><input id="dval" v-model="c.dval" type="number" min="0" step="any" placeholder="0"></div></div>
      <Totals :b="bill" />
      <button class="btn primary block" :disabled="!c.items.length || busy" @click="place(false)">Send to kitchen · {{ inr(bill.total) }}</button>
      <button v-if="c.type !== 'dine_in' && s.can.pay" class="btn block" :disabled="!c.items.length || busy" @click="place(true)">Send and pay now</button>
      <button class="btn block" :disabled="!c.items.length" @click="c.clear()">Clear order</button>
    </aside>
  </div>
  <button class="btn primary fab" @click="tocart"><Icon name="cart" :size="16" /> Order ({{ nQty }})</button>
</template>
