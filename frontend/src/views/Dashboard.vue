<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BarChart from '../components/BarChart.vue'
import Icon from '../components/Icon.vue'
import PageHead from '../components/PageHead.vue'
import RestockModal from '../components/RestockModal.vue'
import Seg from '../components/Seg.vue'
import { api } from '../lib/api'
import { elapsed, inr, num, startOfDay, ts, TYPE } from '../lib/format'
import { ymd } from '../lib/periods'
import { useResource } from '../lib/live'
import { isOpen, whereLabel } from '../lib/roles'
import { dishState, ingStatus } from '../lib/stock'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const s = useSession(), ui = useUi()
const range = ref('today')
const { data: d, loading, reload } = useResource(async () => {
  const day = startOfDay(0), orders = await api.get<any[]>('/orders') // open, unpaid and today's orders
  const [ings, dishes] = await Promise.all([api.get<any[]>('/ingredients'), api.get<any[]>('/menu-items')])
  const mine = orders.filter(o => o.status !== 'cancelled')
  let dash: any
  if (range.value === 'today') {
    const r = await api.get('/dashboard'), today = mine.filter(o => ts(o.placedAt) >= day)
    const hrs = today.map(o => new Date(o.placedAt).getHours()), lo = Math.min(10, ...hrs), hi = Math.max(22, ...hrs)
    const chart = Array.from({ length: hi - lo + 1 }, (_, k) => { const h = lo + k; return { label: (h % 12 || 12) + (h < 12 ? 'a' : 'p'), value: today.filter(o => new Date(o.placedAt).getHours() === h).reduce((t, o) => t + +o.total, 0) } })
    const mix: Record<string, number> = {}; today.forEach(o => { mix[o.orderType] = (mix[o.orderType] || 0) + 1 })
    dash = { sales: +r.sales.day.total, orderCount: +r.sales.day.orders, previousSales: +r.sales.sameTimeYesterday.total, chart, mix, topItems: r.topItems }
  } else {
    const r = (await api.get('/reports/summary', { from: ymd(startOfDay(6)), to: ymd(day) })).current
    const mix: Record<string, number> = { dine_in: 0, takeaway: 0, delivery: 0 }
    r.channels.forEach((c: any) => { const k = c.channel === 'dine_in' || c.channel === 'takeaway' ? c.channel : 'delivery'; mix[k] += +c.orders })
    dash = { sales: +r.totals.total, orderCount: +r.totals.orders, chart: r.series.map((x: any) => ({ label: new Date(x.date + 'T00:00').toLocaleDateString('en-IN', { weekday: 'short' }), value: +x.sales })), mix, topItems: r.topItems }
  }
  const open = orders.filter(isOpen), unpaid = mine.filter(o => o.paymentStatus === 'unpaid')
  return { dash, open, unpaid, ings, dishes }
}, ['orders', 'inventory'])
watch(range, reload)

const dash = computed(() => d.value?.dash)
const delta = computed(() => { const p = dash.value?.previousSales; return range.value === 'today' && p > 0 ? Math.round(((dash.value.sales - p) / p) * 100) : null })
const mix = computed(() => ['dine_in', 'takeaway', 'delivery'].map(k => `${TYPE[k]} ${dash.value?.mix?.[k] ?? 0}`).join(' · '))
const low = computed(() => (d.value?.ings ?? []).filter(i => +i.currentStock <= +i.reorderLevel).sort((a, b) => a.currentStock / a.reorderLevel - b.currentStock / b.reorderLevel).slice(0, 5))
const affected = computed(() => (d.value?.dishes ?? []).filter(m => ['out', 'low'].includes(dishState(m))))
const top = computed(() => (dash.value?.topItems ?? []).slice(0, 6))
const topMax = computed(() => top.value[0]?.quantity || 1)
const live = computed(() => [...(d.value?.open ?? [])].sort((a, b) => ts(a.placedAt) - ts(b.placedAt)).slice(0, 6))
const unpaidTotal = computed(() => (d.value?.unpaid ?? []).reduce((t, o) => t + +o.total, 0))
const restock = (i: any) => ui.open(RestockModal, { ing: i, onDone: reload })
</script>

<template>
  <PageHead title="Dashboard" :sub="new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' }) + ' at ' + s.settings.name">
    <Seg v-model="range" label="Range" :options="[['today', 'Today'], ['7d', 'Last 7 days']]" />
    <router-link class="btn primary" to="/pos"><Icon name="plus" :size="16" /> New order</router-link>
  </PageHead>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="dash" class="stack">
    <div class="kpis">
      <div class="card kpi"><span class="l">Sales</span><span class="v">{{ inr(dash.sales) }}</span><span class="s"><span v-if="delta !== null" class="pill" :class="delta >= 0 ? 'good' : 'bad'">{{ delta >= 0 ? '↑' : '↓' }} {{ Math.abs(delta) }}% vs same time yesterday</span><template v-else>Before tax adjustments, incl. GST</template></span></div>
      <div class="card kpi"><span class="l">Orders</span><span class="v">{{ dash.orderCount }}</span><span class="s">{{ mix }}</span></div>
      <div class="card kpi"><span class="l">Average bill</span><span class="v">{{ inr(dash.orderCount ? dash.sales / dash.orderCount : 0) }}</span><span class="s">Per order, all channels</span></div>
      <div class="card kpi"><span class="l">Open now</span><span class="v">{{ d!.open.length }}</span><span class="s">{{ d!.unpaid.length }} unpaid bills, {{ inr(unpaidTotal) }}</span></div>
    </div>
    <div class="dgrid">
      <div class="card"><div class="row between wrap" style="margin-bottom:10px"><h2>{{ range === 'today' ? 'Sales by hour' : 'Sales by day' }}</h2><span class="sub">{{ range === 'today' ? 'Today' : 'Last 7 days' }}</span></div><BarChart :data="dash.chart" /></div>
      <div class="card"><h2>Needs attention</h2>
        <template v-if="low.length"><div v-for="i in low" :key="i.id" class="li"><div><div class="nm">{{ i.name }}</div><div class="sub">{{ num(i.currentStock) }} {{ i.unit }} left, reorder at {{ num(i.reorderLevel) }}</div></div>
          <div class="row"><span class="pill" :class="ingStatus(i) === 'out' ? 'bad' : 'warn'">{{ ingStatus(i) === 'out' ? 'Out' : 'Low' }}</span><button class="btn sm" @click="restock(i)">Restock</button></div></div></template>
        <div v-else class="empty small">All ingredients are above their reorder level.</div>
        <template v-if="affected.length"><div class="lb" style="margin:14px 0 4px">Dishes affected</div>
          <div v-for="m in affected" :key="m.id" class="li"><span><i class="vm" :class="{ nv: !m.veg }"></i> {{ m.name }}</span><span class="pill" :class="dishState(m) === 'out' ? 'bad' : 'warn'">{{ dishState(m) === 'out' ? 'Unavailable' : m.portionsLeft + ' left' }}</span></div></template>
      </div>
    </div>
    <div class="cols2">
      <div class="card"><h2>Top sellers</h2>
        <div v-for="t in top" :key="t.name" class="li"><div class="grow"><div class="row between"><span class="nm">{{ t.name }}</span><span class="mono">{{ t.quantity }}</span></div><div class="sb"><i :style="{ width: Math.round((t.quantity / topMax) * 100) + '%' }"></i></div></div></div>
        <div v-if="!top.length" class="empty small">No orders in this range yet.</div></div>
      <div class="card"><div class="row between" style="margin-bottom:12px"><h2>Live orders</h2><router-link class="btn sm" to="/orders">Open board</router-link></div>
        <div v-for="o in live" :key="o.id" class="li"><div><span class="mono">#{{ o.orderNo }}</span> <span class="sub">{{ whereLabel(o) }}</span></div>
          <div class="row"><span class="pill" :class="o.status === 'new' ? 'info' : o.status === 'preparing' ? 'warn' : 'good'">{{ ({ new: 'New', preparing: 'Preparing', ready: 'Ready' } as any)[o.status] }}</span><span class="sub" :class="{ late: ui.now - ts(o.placedAt) > 20 * 60e3 }">{{ elapsed(o.placedAt, ui.now) }}</span></div></div>
        <div v-if="!live.length" class="empty small">No open orders. The kitchen is clear.</div></div>
    </div>
  </div>
</template>
