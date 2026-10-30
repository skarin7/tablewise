<script setup lang="ts">
import { reactive } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { useUi } from '../stores/ui'

const props = defineProps<{ ing?: any; onDone: () => void }>()
const ui = useUi(), i = props.ing
const f = reactive({ name: i?.name ?? '', unit: i?.unit ?? 'kg', stock: i ? +i.currentStock : '' as any, reorder: i ? +i.reorderLevel : '' as any, cost: i ? +i.costPerUnit : '' as any, supplier: i?.supplier ?? '' })

async function submit() {
  if (!f.name.trim()) return ui.toast('Give the ingredient a name')
  const body = { name: f.name.trim(), unit: f.unit, reorderLevel: Math.max(0, +f.reorder || 0), costPerUnit: Math.max(0, +f.cost || 0), supplierName: f.supplier.trim() || undefined }
  const stock = Math.max(0, +f.stock || 0)
  try {
    if (i) {
      await api.put('/ingredients/' + i.id, body)
      const diff = Math.round((stock - +i.currentStock) * 1000) / 1000 // stock only changes through the ledger, never by editing the row
      if (diff !== 0) await api.post(`/ingredients/${i.id}/adjust`, { delta: diff, reason: 'manual_correction' })
    } else await api.post('/ingredients', { ...body, openingStock: stock })
    ui.close(); props.onDone(); ui.toast(f.name.trim() + (i ? ' updated' : ' added'))
  } catch (e: any) { ui.toast(e.message) }
}
</script>

<template>
  <ModalBox :title="i ? 'Edit ingredient' : 'Add ingredient'" form @submit="submit">
    <div class="fg">
      <div class="fld full"><label for="i-name">Ingredient name</label><input id="i-name" v-model="f.name" required></div>
      <div class="fld"><label for="i-unit">Unit</label><select id="i-unit" v-model="f.unit"><option v-for="u in ['kg', 'l', 'pcs']" :key="u">{{ u }}</option></select></div>
      <div class="fld"><label for="i-stock">{{ i ? 'Current stock' : 'Opening stock' }}</label><input id="i-stock" v-model="f.stock" type="number" min="0" step="any" required></div>
      <div class="fld"><label for="i-re">Reorder level</label><input id="i-re" v-model="f.reorder" type="number" min="0" step="any" required></div>
      <div class="fld"><label for="i-cost">Cost per unit (₹)</label><input id="i-cost" v-model="f.cost" type="number" min="0" step="any" required></div>
      <div class="fld full"><label for="i-sup">Supplier</label><input id="i-sup" v-model="f.supplier"></div>
    </div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit">Save ingredient</button></template>
  </ModalBox>
</template>
