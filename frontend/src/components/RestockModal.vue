<script setup lang="ts">
import { ref } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { num } from '../lib/format'
import { useUi } from '../stores/ui'

const props = defineProps<{ ing: any; onDone: () => void }>()
const ui = useUi(), qty = ref(''), note = ref('')
async function submit() {
  const q = parseFloat(qty.value) || 0
  if (q <= 0) return ui.toast('Enter the quantity received')
  try { await api.post(`/ingredients/${props.ing.id}/restock`, { quantity: q, note: note.value.trim() || undefined }); ui.close(); props.onDone(); ui.toast(`Added ${num(q)} ${props.ing.unit} of ${props.ing.name}`) }
  catch (e: any) { ui.toast(e.message) }
}
</script>

<template>
  <ModalBox :title="'Restock ' + ing.name" form @submit="submit">
    <p>In stock now: <b class="mono">{{ num(ing.currentStock) }} {{ ing.unit }}</b> · reorder at {{ num(ing.reorderLevel) }} {{ ing.unit }}</p>
    <div class="fg">
      <div class="fld"><label for="r-q">Quantity received ({{ ing.unit }})</label><input id="r-q" v-model="qty" type="number" min="0" step="any" required></div>
      <div class="fld"><label for="r-n">Note</label><input id="r-n" v-model="note" placeholder="Invoice or supplier"></div>
    </div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit">Add to stock</button></template>
  </ModalBox>
</template>
