<script setup lang="ts">
import Icon from './Icon.vue'
import QrCode from './QrCode.vue'
import Totals from './Totals.vue'
import { cdown, inr, inr2 } from '../lib/format'
import type { Screen } from '../lib/display'
import { useUi } from '../stores/ui'

defineProps<{ sc: Screen; mini?: boolean }>()
const ui = useUi()
</script>

<template>
  <section class="cs" :class="{ mini }" aria-label="Customer display">
    <div class="top"><div class="rest"><span class="mark"><Icon name="fork" :size="mini ? 14 : 18" /></span>{{ sc.name }}</div><span v-if="sc.no" class="pill accent mono">Order #{{ sc.no }}</span></div>
    <div v-if="sc.mode === 'paid'" class="pp">
      <span class="ok"><Icon name="check" :size="mini ? 26 : 40" /></span><div class="amt">{{ inr(sc.amount || 0) }}</div>
      <p class="msg"><b>Payment received</b><template v-if="sc.payMode"> by {{ sc.payMode }}</template></p><p v-if="sc.ref" class="sub mono">Invoice {{ sc.ref }}</p><p class="msg">Thank you for dining with us.</p>
    </div>
    <div v-else-if="sc.mode === 'failed'" class="pp"><p class="msg"><b>Payment did not go through.</b></p><p class="sub">{{ sc.reason || 'Please try again or ask our staff for help.' }}</p></div>
    <div v-else-if="sc.mode !== 'bill' || !sc.bill" class="pp"><p class="msg lg">Welcome. Your bill will appear here when you are ready to pay.</p></div>
    <div v-else class="body">
      <div class="left">
        <div v-for="(i, k) in sc.items" :key="k" class="tr"><span>{{ i.q }}× {{ i.n }}</span><span>{{ inr2(i.p * i.q) }}</span></div>
        <Totals :b="sc.bill" style="margin-top:10px" />
      </div>
      <div class="pp">
        <div class="amt">{{ inr(sc.bill.total) }}</div>
        <template v-if="sc.method === 'upi_qr'">
          <template v-if="sc.status === 'failed'"><p class="msg"><b>Payment did not go through.</b></p><p class="sub">Please try again or ask our staff for help.</p></template>
          <template v-else-if="sc.status === 'expired'"><p class="msg"><b>This QR code has expired.</b></p><p class="sub">Please ask our staff for a new one.</p></template>
          <template v-else><QrCode :value="sc.qr || ''" /><p class="msg">Scan with any UPI app to pay</p><p class="sub">Valid for <b>{{ cdown(Math.max(0, (sc.expiresAt || 0) - ui.now)) }}</b></p></template>
        </template>
        <template v-else-if="sc.method === 'card'">
          <span class="cardic"><Icon name="card" :size="mini ? 26 : 40" /></span>
          <template v-if="sc.status === 'failed'"><p class="msg"><b>Card declined.</b></p><p class="sub">Please try another card or ask our staff for help.</p></template>
          <p v-else class="msg">Tap, insert or swipe your card on the machine</p>
        </template>
        <p v-else class="msg">Please pay in cash at the counter</p>
      </div>
    </div>
  </section>
</template>
