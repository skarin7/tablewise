<script setup lang="ts">
import { ref } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { PLAT } from '../lib/format'
import { useUi } from '../stores/ui'

const props = defineProps<{ e: any; onDone: () => void }>()
const ui = useUi()
const reasons = ['Item out of stock', 'Kitchen is too busy', 'Closing soon', 'Delivery area not served']
const reason = ref(props.e.problem ? reasons[0] : reasons[1])
async function submit() {
  try { await api.post(`/online-orders/${props.e.id}/reject`, { reason: reason.value }); ui.close(); props.onDone(); ui.toast(`Rejected. ${PLAT[props.e.platform].label} will tell the customer.`) }
  catch (x: any) { ui.close(); props.onDone(); ui.toast(x.message) }
}
</script>

<template>
  <ModalBox title="Reject this order?" form @submit="submit">
    <p>{{ PLAT[e.platform].label }} order <span class="mono">{{ e.externalOrderId }}</span>.</p>
    <div class="stack" style="gap:8px"><label v-for="r in reasons" :key="r" class="opt"><input v-model="reason" type="radio" name="reason" :value="r"><span>{{ r }}</span></label></div>
    <p class="sub">The platform tells the customer and refunds them. Frequent rejections can lower your ranking on the platform.</p>
    <template #footer><button class="btn" type="button" @click="ui.close()">Keep order</button><button class="btn primary" type="submit">Reject order</button></template>
  </ModalBox>
</template>
