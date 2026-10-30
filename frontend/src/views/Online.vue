<script setup lang="ts">
import { computed, ref } from 'vue'
import OrderActions from '../components/OrderActions.vue'
import PageHead from '../components/PageHead.vue'
import RejectModal from '../components/RejectModal.vue'
import Switch from '../components/Switch.vue'
import { api } from '../lib/api'
import { cdown, fmtTime, inr, PLAT, STATUS_LABEL, STATUS_PILL, ts } from '../lib/format'
import { useResource } from '../lib/live'
import { canAdvance, canCancel, canPay, waitingNote } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const s = useSession(), ui = useUi()
const tab = ref('incoming')
const prep = ref<Record<string, number>>({})
const PREP = [10, 15, 20, 30, 45]
const { data: d, loading, reload } = useResource(async () => {
  const [incoming, progress, today, platforms, board] = await Promise.all([api.get<any[]>('/online-orders', { tab: 'incoming' }), api.get<any[]>('/online-orders', { tab: 'progress' }),
    api.get<any[]>('/online-orders', { tab: 'today' }), api.get<any[]>('/platforms'), api.get<any[]>('/orders', { status: 'open' })])
  // the platform owns customer and delivery data, so a card shows only items, instructions and our own kitchen progress
  const done = today.filter(e => e.status !== 'incoming' && !progress.some(p => p.id === e.id))
  // every platform gets a switch, even before its first order creates a row
  const all = Object.keys(PLAT).map(p => platforms.find(x => x.platform === p) ?? { platform: p, accepting: true })
  return { incoming, progress, done, platforms: all, board }
}, ['online', 'orders'])

const items = (e: any): any[] => e.payload?.items ?? []
const total = (e: any) => items(e).reduce((t, i) => t + +i.price * i.quantity, 0)
const kitchen = (e: any) => d.value?.board.find(o => o.id === e.orderId)
const defaultPrep = () => 15
const chosen = (e: any) => prep.value[e.id] ?? defaultPrep()
const left = (e: any) => ts(e.acceptBy) - ui.now
const itemsShort = (list: any[]) => list.map(i => i.quantity + '× ' + i.name).join(', ')
const incoming = computed(() => [...(d.value?.incoming ?? [])].sort((a, b) => ts(a.acceptBy) - ts(b.acceptBy)))

// Rider and delivery tracking belong to the platform; the outlet shows only its own kitchen progress against the promised time.
function riderLine(e: any) {
  if (!e.kitchenStatus) return ''
  const remaining = Math.round(e.prepMinutes - (ui.now - ts(e.decidedAt)) / 60000)
  if (e.kitchenStatus === 'cancelled') return 'Cancelled after acceptance'
  if (e.kitchenStatus === 'completed') return 'Handed to the rider' + (e.kitchenStatusAt ? ' at ' + fmtTime(e.kitchenStatusAt) : '')
  if (e.kitchenStatus === 'ready') return 'Ready. Hand it over when the rider arrives'
  return (e.kitchenStatus === 'new' ? 'Waiting for the kitchen' : 'Kitchen is preparing') + ', ' + (remaining > 0 ? `due in ${remaining} min` : `${-remaining} min past the promised time`)
}
const hasActs = (o: any) => !!o && (canAdvance(o, s.role).ok || canPay(o, s.role).ok || canCancel(o, s.role).ok)
const doneLine = (e: any) => e.status === 'rejected' ? 'Rejected: ' + e.rejectReason : e.status === 'missed' ? `Not accepted within 3 minutes, so ${PLAT[e.platform].label} cancelled it`
  : e.status === 'cancelled_by_platform' ? `${PLAT[e.platform].label} cancelled this order` : riderLine(e)
const donePill = (e: any) => e.kitchenStatus ? { cls: STATUS_PILL[e.kitchenStatus], txt: STATUS_LABEL[e.kitchenStatus] } : { cls: 'bad', txt: e.status === 'rejected' ? 'Rejected' : e.status === 'missed' ? 'Missed' : 'Cancelled' }

async function accept(e: any) {
  try { const r = await api.post(`/online-orders/${e.id}/accept`, { prepMinutes: chosen(e) }); ui.toast(`Accepted. ${PLAT[e.platform].label} order is now #${r.orderNo} in the kitchen, ready in ${chosen(e)} min.`) }
  catch (x: any) { ui.toast(x.message) } // e.g. "Not enough chicken" or "Paneer Tikka is not linked to a dish": the server refuses and the order stays incoming
  reload()
}
async function toggle(p: any) {
  try { await api.patch('/platforms/' + p.platform, { accepting: !p.accepting }); ui.toast(PLAT[p.platform].label + (p.accepting ? ' is paused. Customers cannot order until you switch it back on.' : ' is accepting orders again')) }
  catch (x: any) { ui.toast(x.message) }
  reload()
}
const tabs = computed<[string, string, number][]>(() => [['incoming', 'New', d.value?.incoming.length ?? 0], ['progress', 'In progress', d.value?.progress.length ?? 0], ['done', 'Done today', d.value?.done.length ?? 0]])
</script>

<template>
  <PageHead title="Online orders" sub="Orders from Swiggy, Zomato and your website. Accept each one within 3 minutes or the platform cancels it. Accepted orders go straight to the kitchen; the platform handles the rider and delivery tracking." />
  <div v-if="loading" class="loading">Loading…</div>
  <template v-else-if="d">
    <div class="plats"><div v-for="p in d.platforms" :key="p.platform" class="plat"><div><div class="nm">{{ PLAT[p.platform].label }}</div><div class="sub">{{ p.accepting ? 'Accepting orders' : 'Paused. Customers cannot order' }}</div></div>
      <Switch :on="p.accepting" :label="'Accept ' + PLAT[p.platform].label + ' orders'" @toggle="toggle(p)" /></div></div>
    <div class="seg" role="group" aria-label="Online order list" style="margin-bottom:14px"><button v-for="t in tabs" :key="t[0]" :aria-pressed="tab === t[0]" @click="tab = t[0]">{{ t[1] }} ({{ t[2] }})</button></div>

    <template v-if="tab === 'incoming'">
      <div v-if="!incoming.length" class="card"><div class="empty">No new online orders. New orders from Swiggy, Zomato and your website appear here with a 3-minute timer.</div></div>
      <div v-else class="incs">
        <article v-for="e in incoming" :key="e.id" class="inc" :class="{ urgent: left(e) < 60e3 }">
          <div class="row between wrap"><div class="row wrap"><span class="pill" :class="PLAT[e.platform].cls">{{ PLAT[e.platform].label }}</span><span class="mono">{{ e.externalOrderId }}</span><span class="sub">Received {{ fmtTime(e.receivedAt) }}</span></div>
            <span class="pill" :class="left(e) < 60e3 ? 'bad' : 'warn'">Accept within <b>{{ cdown(Math.max(0, left(e))) }}</b></span></div>
          <div class="incg">
            <div>
              <ul class="ilist"><li v-for="(i, k) in items(e)" :key="k"><span></span><span class="q">{{ i.quantity }}×</span><span>{{ i.name }}</span><span class="mono">{{ inr(i.price * i.quantity) }}</span></li></ul>
              <p v-if="e.payload?.instructions" class="onote" style="margin-top:10px">{{ e.payload.instructions }}</p></div>
            <div class="stack" style="gap:12px">
              <div class="tr grand" style="margin:0;padding:0;border:0"><span>Paid online</span><span>{{ inr(total(e)) }}</span></div>
              <div class="fld"><span class="lb">Food ready in</span><div class="chips"><button v-for="v in PREP" :key="v" class="chip" :aria-pressed="chosen(e) === v" @click="prep[e.id] = v">{{ v }} min</button></div></div>
              <div class="row wrap"><button class="btn primary" :disabled="!s.can.take" @click="accept(e)">Accept and send to kitchen</button>
                <button class="btn danger" :disabled="!s.can.take" @click="ui.open(RejectModal, { e: { ...e, customerName: '' }, onDone: reload })">Reject</button></div>
            </div>
          </div>
        </article>
      </div>
    </template>

    <div v-else-if="tab === 'progress'" class="card">
      <div v-for="e in d.progress" :key="e.id" class="rr2" :data-prog="e.id"><div class="big">#{{ e.orderNo }}</div>
        <div class="stack" style="gap:4px;min-width:0"><div class="row wrap"><span class="pill" :class="PLAT[e.platform].cls">{{ PLAT[e.platform].label }}</span><span class="mono sub">{{ e.externalOrderId }}</span><span class="pill" :class="STATUS_PILL[e.kitchenStatus]">{{ STATUS_LABEL[e.kitchenStatus] }}</span></div>
          <div class="line">{{ riderLine(e) }}</div><div class="sub">{{ itemsShort(items(e)) }} · ready in {{ e.prepMinutes }} min promised</div></div>
        <div v-if="hasActs(kitchen(e))" class="acts"><OrderActions :o="kitchen(e)" @changed="reload" /></div><div v-else class="sub">{{ kitchen(e) ? waitingNote(kitchen(e), s.role) : '' }}</div></div>
      <div v-if="!d.progress.length" class="empty">No accepted online orders are being prepared.</div>
    </div>

    <div v-else class="card">
      <div v-for="e in d.done" :key="e.id" class="rr2"><div class="big">{{ e.orderNo ? '#' + e.orderNo : '–' }}</div>
        <div class="stack" style="gap:4px;min-width:0"><div class="row wrap"><span class="pill" :class="PLAT[e.platform].cls">{{ PLAT[e.platform].label }}</span><span class="mono sub">{{ e.externalOrderId }}</span>
          <span class="pill" :class="donePill(e).cls">{{ donePill(e).txt }}</span></div>
          <div class="line">{{ doneLine(e) }}</div><div class="sub">{{ fmtTime(e.receivedAt) }} · {{ itemsShort(items(e)) }}</div></div><div></div></div>
      <div v-if="!d.done.length" class="empty">Nothing yet today.</div>
    </div>
  </template>
</template>
