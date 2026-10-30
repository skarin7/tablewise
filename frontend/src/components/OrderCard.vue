<script setup lang="ts">
import { computed } from 'vue'
import OrderActions from './OrderActions.vue'
import PayPill from './PayPill.vue'
import { elapsed, fmtTime, inr, ts } from '../lib/format'
import { canAdvance, canCancel, canPay, isOpen, waitingNote, whereLabel } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const props = defineProps<{ o: any }>()
defineEmits<{ changed: [] }>()
const s = useSession(), ui = useUi()
const late = computed(() => isOpen(props.o) && ui.now - ts(props.o.placedAt) > 20 * 60e3)
const hasActs = computed(() => canAdvance(props.o, s.role).ok || canPay(props.o, s.role).ok || canCancel(props.o, s.role).ok)
const note = computed(() => { const a = canAdvance(props.o, s.role) as any; return a.unpaid ? 'Collect payment before ' + (props.o.orderType === 'takeaway' ? 'handing over' : 'dispatch') : waitingNote(props.o, s.role) })
</script>

<template>
  <article class="oc" :data-order="o.orderNo">
    <div class="hd"><span class="no" :class="{ cx: o.status === 'cancelled' }">#{{ o.orderNo }}</span><span class="sub" :class="{ late }">{{ isOpen(o) ? elapsed(o.placedAt, ui.now) : fmtTime(o.placedAt) }}</span></div>
    <div class="row wrap">
      <span class="pill" :class="o.orderType === 'dine_in' ? 'accent' : o.orderType === 'takeaway' ? 'info' : 'warn'">{{ whereLabel(o) }}</span>
      <span v-if="o.billToName && s.can.prices" class="sub">{{ o.billToName }}</span>
    </div>
    <ul><li v-for="(i, k) in o.items" :key="k"><b>{{ i.quantity }}×</b><span>{{ i.name }}</span></li></ul>
    <p v-if="o.notes" class="onote">{{ o.notes }}</p>
    <div v-if="s.can.prices" class="ft"><span class="row"><b class="mono">{{ inr(o.total) }}</b><PayPill :o="o" /></span></div>
    <p v-if="note" class="sub">{{ note }}</p>
    <div v-if="hasActs" class="acts"><OrderActions :o="o" @changed="$emit('changed')" /></div>
  </article>
</template>
