<script setup lang="ts">
import { computed } from 'vue'
import PayModal from './PayModal.vue'
import CancelModal from './CancelModal.vue'
import { api } from '../lib/api'
import { inr } from '../lib/format'
import { canAdvance, canCancel, canPay, stepLabel, waitingNote } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

// Buttons for one order. Used by board cards, search results and in-progress online orders; `note` is the text shown when no button applies.
const props = defineProps<{ o: any; showNote?: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const s = useSession(), ui = useUi()
const adv = computed(() => canAdvance(props.o, s.role))
const note = computed(() => (!adv.value.ok && (adv.value as any).unpaid ? 'Collect payment before ' + (props.o.orderType === 'takeaway' ? 'handing over' : 'dispatch') : waitingNote(props.o, s.role)))
defineExpose({ note })

async function advance() {
  const label = stepLabel(props.o)
  try { await api.post(`/orders/${props.o.id}/advance`); ui.toast(`#${props.o.orderNo}: ${label.toLowerCase()} done`) } catch (e: any) { ui.toast(e.message) }
  emit('changed')
}
const pay = () => ui.open(PayModal, { order: props.o, onDone: () => emit('changed') })
const cancel = () => ui.open(CancelModal, { order: props.o, onDone: () => emit('changed') })
</script>

<template>
  <button v-if="adv.ok" class="btn sm primary" @click="advance">{{ stepLabel(o) }}</button>
  <button v-if="canPay(o, s.role).ok" class="btn sm" :class="{ primary: (adv as any).unpaid }" @click="pay">Collect {{ inr(o.total) }}</button>
  <button v-if="canCancel(o, s.role).ok" class="btn sm danger" @click="cancel">Cancel</button>
</template>
