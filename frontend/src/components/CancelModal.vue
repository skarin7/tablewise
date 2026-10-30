<script setup lang="ts">
import { ref } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { useUi } from '../stores/ui'

const props = defineProps<{ order: any; onDone: () => void }>()
const ui = useUi(), reason = ref(''), busy = ref(false)
const started = props.order.status === 'preparing'
async function submit() {
  if (!reason.value.trim()) return ui.toast('Give a reason for cancelling')
  busy.value = true
  try {
    await api.post(`/orders/${props.order.id}/cancel`, { reason: reason.value.trim() })
    ui.close(); props.onDone()
    ui.toast(`Order #${props.order.orderNo} cancelled` + (started ? '. Ingredients stay deducted.' : ' and stock restored'))
  } catch (e: any) { busy.value = false; ui.toast(e.message); props.onDone() }
}
</script>

<template>
  <ModalBox :title="`Cancel order #${order.orderNo}?`" form @submit="submit">
    <p>{{ started ? 'The kitchen has already started this order, so its ingredients are counted as used and stay deducted from stock.' : 'The ticket is removed from the kitchen and its ingredients go back into stock.' }}</p>
    <div class="fld"><label for="why">Reason</label><input id="why" v-model="reason" required placeholder="For example: customer left, wrong table" autocomplete="off"></div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Keep order</button><button class="btn primary" type="submit" :disabled="busy">Cancel order</button></template>
  </ModalBox>
</template>
