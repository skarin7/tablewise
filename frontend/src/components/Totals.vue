<script setup lang="ts">
import { inr, inr2 } from '../lib/format'
import type { Totals } from '../lib/bill'
defineProps<{ b: Totals; grand?: string }>()
</script>

<template>
  <div class="tot">
    <div class="tr"><span>Subtotal</span><span>{{ inr2(b.sub) }}</span></div>
    <div v-if="b.disc > 0" class="tr m"><span>Discount</span><span>−{{ inr2(b.disc) }}</span></div>
    <div v-if="b.svcPct > 0" class="tr m"><span>Service charge {{ b.svcPct }}%</span><span>{{ inr2(b.svc) }}</span></div>
    <div class="tr m"><span>GST{{ b.gstPct ? " " + b.gstPct + "%" : "" }}</span><span>{{ inr2(b.gst) }}</span></div>
    <div v-if="Math.abs(b.round) >= 0.005" class="tr m"><span>Round off</span><span>{{ b.round > 0 ? '+' : '−' }}{{ inr2(Math.abs(b.round)) }}</span></div>
    <div class="tr grand"><span>{{ grand || 'Total' }}</span><span>{{ inr(b.total) }}</span></div>
  </div>
</template>
