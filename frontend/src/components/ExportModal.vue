<script setup lang="ts">
import { ref } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { fmtRange } from '../lib/periods'
import { useUi } from '../stores/ui'

const props = defineProps<{ range: { from: number; to: number }; label: string; from: string; to: string }>()
const ui = useUi(), kind = ref('summary'), busy = ref(false)
const KINDS = [['summary', 'Sales summary', 'Totals, channels, payment modes and GST'], ['daywise', 'Day-wise sales', 'One row per day with taxable value, GST and total'], ['itemwise', 'Dish-wise sales', 'Quantity and sales for every dish']]
async function submit() {
  busy.value = true
  try {
    const blob = await api.blob('/reports/export', { kind: kind.value, from: props.from, to: props.to })
    const name = `tablewise-${kind.value}-${props.from}-to-${props.to}.csv`, a = document.createElement('a')
    a.href = URL.createObjectURL(blob); a.download = name; a.click(); URL.revokeObjectURL(a.href)
    ui.close(); ui.toast('Saved ' + name)
  } catch (e: any) { busy.value = false; ui.toast(e.message) }
}
</script>

<template>
  <ModalBox title="Export report" form @submit="submit">
    <p><b>{{ label }}</b> · {{ fmtRange(range.from, range.to) }}</p>
    <div class="stack" style="gap:8px"><label v-for="k in KINDS" :key="k[0]" class="opt"><input v-model="kind" type="radio" name="kind" :value="k[0]"><span><b>{{ k[1] }}</b><br><span class="sub">{{ k[2] }}</span></span></label></div>
    <p class="sub">Files are CSV, which opens in Excel and Google Sheets.</p>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit" :disabled="busy">Download CSV</button></template>
  </ModalBox>
</template>
