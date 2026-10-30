<script setup lang="ts">
import { onMounted, onUnmounted, reactive, watch } from 'vue'
import Confirm from '../components/Confirm.vue'
import Icon from '../components/Icon.vue'
import PageHead from '../components/PageHead.vue'
import PairModal from '../components/PairModal.vue'
import StaffModal from '../components/StaffModal.vue'
import Switch from '../components/Switch.vue'
import { api } from '../lib/api'
import { fmtDay } from '../lib/format'
import { useResource } from '../lib/live'
import { NAV, ROLES } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const s = useSession(), ui = useUi()
const { data: d, loading, reload } = useResource(async () => {
  const [categories, staff, devices, items, tables] = await Promise.all([api.get<any[]>('/categories'), api.get<any[]>('/staff'), api.get<any[]>('/devices'), api.get<any[]>('/menu-items'), api.get<any[]>('/tables')])
  return { categories, staff, devices, items, tables }
})
const f = reactive({ name: '', gstPercent: 0, serviceChargePercent: 0 })
const newTable = reactive({ label: '', seats: '' }), newCounter = reactive({ name: '' })
watch(() => s.settings, v => Object.assign(f, v), { immediate: true })
const newCat = reactive({ name: '' })
let poll: any
onMounted(() => { poll = setInterval(async () => { if (d.value && !ui.modal && !(document.activeElement as HTMLElement)?.closest('#dev-card')) d.value.devices = await api.get('/devices').catch(() => d.value!.devices) }, 3000) }) // online status
onUnmounted(() => clearInterval(poll))

const run = async (fn: () => Promise<any>, ok?: string) => { try { await fn(); if (ok) ui.toast(ok) } catch (e: any) { ui.toast(e.message) } reload() }
async function saveSettings() {
  if (!f.name.trim()) return ui.toast('Restaurant name cannot be empty')
  // PUT /outlet overwrites every field, so the details this form does not show are sent back unchanged (empty ones as null: the GSTIN and FSSAI patterns reject "")
  const o = s.settings
  const body = { name: f.name.trim(), address: o.address || null, phone: o.phone || null, gstin: o.gstin || null, fssaiLicenseNo: o.fssaiLicenseNo || null,
    gstPercent: Math.min(28, Math.max(0, +f.gstPercent || 0)), serviceChargePercent: Math.min(20, Math.max(0, +f.serviceChargePercent || 0)) }
  try { await api.put('/outlet', body); await s.loadOutlet(); ui.toast('Settings saved. New bills use these rates.') } catch (e: any) { ui.toast(e.message) }
}
const afterCounters = async () => { await s.loadCounters(); reload() }
async function addTable() { await run(() => api.post('/tables', { label: newTable.label.trim(), seats: +newTable.seats || undefined }), 'Table ' + newTable.label.trim() + ' added'); newTable.label = ''; newTable.seats = '' }
async function addCounter() { const name = newCounter.name.trim(); await run(() => api.post('/counters', { name }).then(afterCounters), name + ' added'); newCounter.name = '' }
const renameCounter = (c: any, v: string) => { v = v.trim(); if (v && v !== c.name) run(() => api.put('/counters/' + c.id, { name: v }).then(afterCounters), 'Counter renamed') }
const delCounter = (c: any) => ui.open(Confirm, { title: `Remove ${c.name}?`, message: 'The counter stops appearing in the terminal list. Past orders and payments keep it.', label: 'Remove counter', onYes: () => run(() => api.del('/counters/' + c.id).then(afterCounters), c.name + ' removed') })
const dishCount = (id: string) => d.value!.items.filter(m => m.categoryId === id).length
const rename = (c: any, v: string) => { v = v.trim(); if (!v) return reload(); if (v !== c.name) run(() => api.put('/categories/' + c.id, { name: v }), 'Category renamed') }
function delCat(c: any) { const n = dishCount(c.id); if (n) return ui.toast(`Move or delete the ${n} dishes in ${c.name} first.`); run(() => api.del('/categories/' + c.id), c.name + ' removed') }
async function addCat() { const name = newCat.name.trim(); if (!name) return; await run(() => api.post('/categories', { name }), name + ' added'); newCat.name = '' }
const pair = () => ui.open(PairModal, { counters: s.counters, used: d.value!.devices.map(x => x.counterId), onDone: reload })
const delDev = (x: any) => ui.open(Confirm, { title: `Remove ${x.name}?`, message: `The tablet is signed out straight away and shows a new pairing code. Until you pair another display, payments at ${s.counters.find(c => c.id === x.counterId)?.name ?? 'that counter'} show only on the cashier screen.`, label: 'Remove display',
  onYes: () => run(() => api.del('/devices/' + x.id), x.name + ' removed and signed out') })
const lastSeen = (x: any) => x.online ? 'Connected now' : x.lastSeenAt ? 'Last seen ' + fmtDay(x.lastSeenAt) : 'Never connected'
const ACTS = [['take', 'Take new orders'], ['take', 'Accept or reject online orders'], ['start', 'Start preparing'], ['ready', 'Mark ready'], ['serve', 'Serve, hand over or dispatch'], ['pay', 'Collect payment'], ['cancelNew', 'Cancel a new order'], ['cancelPrep', 'Cancel an order in preparation'], ['prices', 'See bill amounts and payment status']]
const PLANNED = [['UPI and card payments', 'Payment gateway with automatic bill matching'], ['Swiggy and Zomato orders', 'Online orders arrive on the same board'], ['KOT and bill printing', 'Thermal printers in kitchen and at the counter'], ['WhatsApp receipts', 'Bill and feedback link sent to the customer'], ['Accounting export', 'Daily sales and GST summary for your accountant']]
</script>

<template>
  <PageHead title="Admin" sub="Restaurant settings, categories, staff and access." />
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="d" class="cols2" style="align-items:start">
    <form class="card stack" style="gap:14px" @submit.prevent="saveSettings"><h2>Restaurant and billing</h2>
      <div class="fg"><div class="fld full"><label for="s-name">Restaurant name</label><input id="s-name" v-model="f.name" required></div>
        <div class="fld"><label for="s-gst">GST on bills (%)</label><input id="s-gst" v-model="f.gstPercent" type="number" min="0" max="28" step="any"></div>
        <div class="fld"><label for="s-svc">Service charge (%)</label><input id="s-svc" v-model="f.serviceChargePercent" type="number" min="0" max="20" step="any"></div>
</div>
      <div><button class="btn primary" type="submit">Save settings</button></div></form>

    <div class="card"><h2>Tables and counters</h2>
      <div class="lb" style="margin-bottom:4px">Tables</div>
      <div v-for="t in d.tables" :key="t.id" class="li"><span>{{ t.label }} <span class="sub">{{ t.seats }} seats</span></span><button class="btn sm danger" :aria-label="'Remove table ' + t.label" @click="run(() => api.del('/tables/' + t.id), 'Table ' + t.label + ' removed')"><Icon name="trash" :size="14" /></button></div>
      <form class="row" style="margin:10px 0 16px" @submit.prevent="addTable"><input v-model="newTable.label" placeholder="Label, e.g. T13" maxlength="10" aria-label="Table label" required><input v-model="newTable.seats" type="number" min="1" placeholder="Seats" aria-label="Seats" style="width:90px"><button class="btn" type="submit"><Icon name="plus" :size="14" /> Add</button></form>
      <div class="lb" style="margin-bottom:4px">Billing counters</div>
      <div v-for="c in s.counters" :key="c.id" class="li"><div class="grow"><input :value="c.name" aria-label="Counter name" @change="renameCounter(c, ($event.target as HTMLInputElement).value)"></div><button class="btn sm danger" :aria-label="'Remove ' + c.name" @click="delCounter(c)"><Icon name="trash" :size="14" /></button></div>
      <form class="row" style="margin-top:10px" @submit.prevent="addCounter"><input v-model="newCounter.name" placeholder="New counter" aria-label="New counter name" required><button class="btn" type="submit"><Icon name="plus" :size="14" /> Add</button></form></div>

    <div class="card"><h2>Categories</h2>
      <div v-for="c in d.categories" :key="c.id" class="li"><div class="grow"><input :value="c.name" aria-label="Category name" @change="rename(c, ($event.target as HTMLInputElement).value)"></div>
        <div class="row"><span class="sub">{{ dishCount(c.id) }} dishes</span><button class="btn sm danger" :aria-label="'Delete ' + c.name" @click="delCat(c)"><Icon name="trash" :size="14" /></button></div></div>
      <form class="row" style="margin-top:14px" @submit.prevent="addCat"><input v-model="newCat.name" placeholder="New category" aria-label="New category name" required><button class="btn" type="submit"><Icon name="plus" :size="14" /> Add</button></form></div>

    <div class="card"><div class="row between" style="margin-bottom:8px"><h2>Staff</h2><button class="btn sm" @click="ui.open(StaffModal, { onDone: reload })"><Icon name="plus" :size="14" /> Invite</button></div>
      <div class="tw"><table><thead><tr><th>Name</th><th>Role</th><th>Last active</th><th>Active</th></tr></thead><tbody>
        <tr v-for="m in d.staff" :key="m.id"><td class="nm">{{ m.fullName }}</td><td>{{ (ROLES as any)[m.role]?.label }}</td><td>{{ m.lastActiveAt ? fmtDay(m.lastActiveAt) : 'Never' }}</td>
          <td><Switch :on="m.active" :label="'Active: ' + m.fullName" @toggle="run(() => api.put('/staff/' + m.id, { fullName: m.fullName, phone: m.phone, role: m.role, active: !m.active }))" /></td></tr></tbody></table></div></div>

    <div id="dev-card" class="card">
      <div class="row between wrap" style="margin-bottom:8px"><h2>Customer displays</h2><button class="btn sm primary" @click="pair"><Icon name="plus" :size="14" /> Add customer display</button></div>
      <div v-if="d.devices.length" class="tw"><table><thead><tr><th>Display</th><th>Shows payments from</th><th>Status</th><th></th></tr></thead><tbody>
        <tr v-for="x in d.devices" :key="x.id"><td><div class="nm">{{ x.name }}</div><div class="sub">Paired {{ fmtDay(x.pairedAt) }}</div></td>
          <td>{{ x.counter }}</td>
          <td><span class="pill" :class="{ good: x.online }">{{ x.online ? 'Online' : 'Offline' }}</span><div class="sub">{{ lastSeen(x) }}</div></td>
          <td class="n"><button class="btn sm danger" @click="delDev(x)">Remove</button></td></tr></tbody></table></div>
      <div v-else class="empty small">No displays paired yet.</div>
      <div class="lb" style="margin-top:14px">How pairing works</div>
      <ol class="howto"><li>Open the customer display page on the tablet. It shows a 6-digit code.</li><li>Click Add customer display, enter the code and pick the counter.</li><li>The tablet gets its own display login, so nobody signs in on it. It can only show bills and payments for its counter.</li><li>Payments collected at that counter appear on its display. To move a display to another counter, remove it and pair it again.</li></ol></div>

    <div class="card"><h2>Who can open what</h2><div class="tw"><table><thead><tr><th>Screen</th><th v-for="r in ROLES" :key="r.label">{{ r.label }}</th></tr></thead><tbody>
      <tr v-for="m in [...NAV.slice(0, 8), { id: 'display', label: 'Customer display' }, NAV[8]]" :key="m.id"><td>{{ m.label }}</td>
        <td v-for="(r, k) in ROLES" :key="k"><span class="pill" :class="{ good: r.views.includes(m.id) || (m.id === 'display' && k !== 'kitchen') }">{{ r.views.includes(m.id) || (m.id === 'display' && k !== 'kitchen') ? 'Allowed' : '–' }}</span></td></tr></tbody></table></div>
      <p class="sub" style="margin-top:10px">Roles come from each person’s staff profile. To change someone’s access, edit their role here.</p></div>

    <div class="card"><h2>Order actions by role</h2><div class="tw"><table><thead><tr><th>Action</th><th v-for="r in ROLES" :key="r.label">{{ r.label }}</th></tr></thead><tbody>
      <tr v-for="a in ACTS" :key="a[1]"><td>{{ a[1] }}</td><td v-for="(r, k) in ROLES" :key="k"><span class="pill" :class="{ good: r.can[a[0]] }">{{ r.can[a[0]] ? 'Allowed' : '–' }}</span></td></tr></tbody></table></div>
      <p class="sub" style="margin-top:10px">Takeaway and delivery orders must be paid before they are handed over or dispatched. Dine-in guests can pay after they are served.</p></div>

    <div class="card"><h2>Planned integrations</h2><div v-for="x in PLANNED" :key="x[0]" class="li"><div><div class="nm">{{ x[0] }}</div><div class="sub">{{ x[1] }}</div></div><span class="pill">Planned</span></div></div>
  </div>
</template>
