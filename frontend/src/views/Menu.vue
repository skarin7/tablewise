<script setup lang="ts">
import { computed, ref } from 'vue'
import Confirm from '../components/Confirm.vue'
import Icon from '../components/Icon.vue'
import MenuForm from '../components/MenuForm.vue'
import PageHead from '../components/PageHead.vue'
import Switch from '../components/Switch.vue'
import { api } from '../lib/api'
import { inr } from '../lib/format'
import { useResource } from '../lib/live'
import { dishState } from '../lib/stock'
import { useUi } from '../stores/ui'

const ui = useUi()
const q = ref(''), cat = ref('all')
const { data: d, loading, reload } = useResource(async () => {
  const [items, categories, ingredients] = await Promise.all([api.get<any[]>('/menu-items'), api.get<any[]>('/categories'), api.get<any[]>('/ingredients')])
  return { items, categories, ingredients }
}, ['inventory'])
const catName = (id: string) => d.value?.categories.find(c => c.id === id)?.name ?? 'Uncategorised'
const list = computed(() => (d.value?.items ?? []).filter(m => (cat.value === 'all' || m.categoryId === cat.value) && (!q.value.trim() || m.name.toLowerCase().includes(q.value.trim().toLowerCase()))))

const form = (dish?: any) => ui.open(MenuForm, { dish, categories: d.value!.categories, ingredients: d.value!.ingredients, onDone: reload })
async function toggle(m: any) {
  try { await api.patch(`/menu-items/${m.id}/availability`, { available: !m.available }); ui.toast(m.name + (m.available ? ' is switched off' : ' is available again')) } catch (e: any) { ui.toast(e.message) }
  reload()
}
const del = (m: any) => ui.open(Confirm, { title: `Delete ${m.name}?`, message: 'The dish disappears from the menu. Past orders keep their line items.', label: 'Delete dish',
  onYes: async () => { try { await api.del('/menu-items/' + m.id); ui.toast(m.name + ' deleted') } catch (e: any) { ui.toast(e.message) } reload() } })
</script>

<template>
  <PageHead title="Menu" :sub="(d?.items.length ?? 0) + ' dishes. Each dish lists the ingredients it uses, so stock drops with every order.'">
    <button class="btn primary" :disabled="!d" @click="form()"><Icon name="plus" :size="16" /> Add dish</button>
  </PageHead>
  <div v-if="loading" class="loading">Loading…</div>
  <div v-else-if="d" class="card">
    <div class="tools"><div class="search"><Icon name="search" :size="16" /><input v-model="q" type="search" placeholder="Search dishes" aria-label="Search dishes"></div>
      <div style="width:200px;max-width:100%"><select v-model="cat" aria-label="Category"><option value="all">All categories</option><option v-for="c in d.categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div></div>
    <div class="tw"><table><thead><tr><th>Dish</th><th>Category</th><th class="n">Price</th><th class="n">Prep</th><th>Stock</th><th>Available</th><th></th></tr></thead><tbody>
      <tr v-if="!list.length"><td colspan="7"><div class="empty">No dishes found.</div></td></tr>
      <tr v-for="m in list" :key="m.id"><td><span class="nm"><i class="vm" :class="{ nv: !m.veg }"></i> {{ m.name }}</span></td><td>{{ catName(m.categoryId) }}</td><td class="n">{{ inr(m.price) }}</td><td class="n">{{ m.prepMinutes }} min</td>
        <td><span v-if="dishState(m) === 'off'" class="pill">Switched off</span><span v-else-if="dishState(m) === 'out'" class="pill bad">Out of stock</span><span v-else-if="dishState(m) === 'low'" class="pill warn">{{ m.portionsLeft }} portions left</span>
          <span v-else class="pill good">{{ dishState(m) === 'untracked' ? 'Not tracked' : m.portionsLeft + ' portions' }}</span></td>
        <td><Switch :on="m.available" :label="'Available: ' + m.name" @toggle="toggle(m)" /></td>
        <td class="n"><div class="row" style="justify-content:flex-end"><button class="btn sm" :aria-label="'Edit ' + m.name" @click="form(m)"><Icon name="edit" :size="14" /></button><button class="btn sm danger" :aria-label="'Delete ' + m.name" @click="del(m)"><Icon name="trash" :size="14" /></button></div></td></tr>
    </tbody></table></div>
  </div>
</template>
