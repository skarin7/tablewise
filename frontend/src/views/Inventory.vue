<script setup lang="ts">
import { computed, ref } from 'vue'
import Confirm from '../components/Confirm.vue'
import Icon from '../components/Icon.vue'
import IngredientForm from '../components/IngredientForm.vue'
import PageHead from '../components/PageHead.vue'
import RestockModal from '../components/RestockModal.vue'
import Seg from '../components/Seg.vue'
import { api } from '../lib/api'
import { fmtDay, inr, num } from '../lib/format'
import { useResource } from '../lib/live'
import { ingStatus } from '../lib/stock'
import { useUi } from '../stores/ui'

const ui = useUi()
const tab = ref('stock'), q = ref(''), filter = ref('all')
const { data: d, loading, reload } = useResource(async () => {
  const [ings, log] = await Promise.all([api.get<any[]>('/ingredients'), api.get<any[]>('/stock-movements', { limit: 40 })])
  return { ings, log }
}, ['inventory', 'orders'])
const REASON: Record<string, string> = { restock: 'Restock', opening_stock: 'Opening stock', order_consumption: 'Order', order_cancellation: 'Order cancelled', manual_correction: 'Manual correction', wastage: 'Wastage' }

const ings = computed(() => d.value?.ings ?? [])
const count = (s: string) => ings.value.filter(i => ingStatus(i) === s).length
const value = computed(() => ings.value.reduce((t, i) => t + +i.currentStock * +i.costPerUnit, 0))
const rows = computed(() => ings.value.filter(i => (filter.value === 'all' || ingStatus(i) === filter.value) && (!q.value.trim() || i.name.toLowerCase().includes(q.value.trim().toLowerCase()) || (i.supplier ?? '').toLowerCase().includes(q.value.trim().toLowerCase()))))
const pct = (i: any) => Math.min(100, (+i.currentStock / ((+i.reorderLevel || 1) * 3)) * 100)

const form = (ing?: any) => ui.open(IngredientForm, { ing, onDone: reload })
const restock = (ing: any) => ui.open(RestockModal, { ing, onDone: reload })
const del = (i: any) => ui.open(Confirm, { title: `Delete ${i.name}?`, message: 'Its stock record and movement history stay in the log.', label: 'Delete ingredient',
  onYes: async () => { try { await api.del('/ingredients/' + i.id); ui.toast(i.name + ' deleted') } catch (e: any) { ui.toast(e.message) } reload() } }) // the server refuses while a recipe still uses it
const reason = (m: any) => (m.reason === 'order_consumption' || m.reason === 'order_cancellation') && m.orderNo ? `${REASON[m.reason]} #${m.orderNo}` : (REASON[m.reason] ?? m.reason) + (m.note ? ': ' + m.note : '')
</script>

<template>
  <PageHead title="Inventory" sub="Raw materials, reorder levels and every stock movement.">
    <Seg v-model="tab" label="Inventory view" :options="[['stock', 'Stock'], ['log', 'Movement log']]" />
    <button class="btn primary" @click="form()"><Icon name="plus" :size="16" /> Add ingredient</button>
  </PageHead>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="d" class="stack">
    <div class="kpis"><div class="card kpi"><span class="l">Ingredients</span><span class="v">{{ ings.length }}</span></div><div class="card kpi"><span class="l">Low stock</span><span class="v">{{ count('low') }}</span></div>
      <div class="card kpi"><span class="l">Out of stock</span><span class="v">{{ count('out') }}</span></div><div class="card kpi"><span class="l">Stock value</span><span class="v">{{ inr(value) }}</span></div></div>
    <div v-if="tab === 'stock'" class="card">
      <div class="tools"><div class="search"><Icon name="search" :size="16" /><input v-model="q" type="search" placeholder="Search ingredient or supplier" aria-label="Search ingredients"></div>
        <div class="chips"><button v-for="x in [['all', 'All'], ['low', 'Low'], ['out', 'Out']]" :key="x[0]" class="chip" :aria-pressed="filter === x[0]" @click="filter = x[0]">{{ x[1] }}</button></div></div>
      <div class="tw"><table><thead><tr><th>Ingredient</th><th>In stock</th><th class="n">Reorder at</th><th class="n">Cost</th><th>Status</th><th></th></tr></thead><tbody>
        <tr v-if="!rows.length"><td colspan="6"><div class="empty">Nothing matches this filter.</div></td></tr>
        <tr v-for="i in rows" :key="i.id"><td><div class="nm">{{ i.name }}</div><div class="sub">{{ i.supplier }}</div></td>
          <td><div class="mono">{{ num(i.currentStock) }} {{ i.unit }}</div><div class="lvl" :class="ingStatus(i)"><i :style="{ width: pct(i) + '%' }"></i></div></td>
          <td class="n">{{ num(i.reorderLevel) }} {{ i.unit }}</td><td class="n">{{ inr(i.costPerUnit) }}/{{ i.unit }}</td>
          <td><span class="pill" :class="ingStatus(i) === 'out' ? 'bad' : ingStatus(i) === 'low' ? 'warn' : 'good'">{{ ingStatus(i) === 'out' ? 'Out of stock' : ingStatus(i) === 'low' ? 'Low' : 'OK' }}</span></td>
          <td class="n"><div class="row" style="justify-content:flex-end"><button class="btn sm" @click="restock(i)"><Icon name="plus" :size="14" /> Restock</button><button class="btn sm" :aria-label="'Edit ' + i.name" @click="form(i)"><Icon name="edit" :size="14" /></button><button class="btn sm danger" :aria-label="'Delete ' + i.name" @click="del(i)"><Icon name="trash" :size="14" /></button></div></td></tr>
      </tbody></table></div>
    </div>
    <div v-else class="card"><div class="tw"><table><thead><tr><th>When</th><th>Ingredient</th><th class="n">Change</th><th>Reason</th><th>By</th></tr></thead><tbody>
      <tr v-if="!d.log.length"><td colspan="5"><div class="empty">No stock movements yet.</div></td></tr>
      <tr v-for="m in d.log" :key="m.id"><td>{{ fmtDay(m.createdAt) }}</td><td>{{ m.ingredient }}</td><td class="n" :style="{ color: `var(--${+m.quantityDelta >= 0 ? 'good' : 'bad'})` }">{{ +m.quantityDelta >= 0 ? '+' : '−' }}{{ num(Math.abs(m.quantityDelta)) }} {{ m.unit }}</td><td>{{ reason(m) }}</td><td>{{ m.by || 'System' }}</td></tr>
    </tbody></table></div></div>
  </div>
</template>
