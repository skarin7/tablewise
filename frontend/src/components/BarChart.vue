<script setup lang="ts">
import { computed } from 'vue'
import { compact, inr, niceStep } from '../lib/format'

const props = defineProps<{ data: { label: string; full?: string; value: number }[] }>()
const W = 640, H = 240, ml = 46, mr = 8, mt = 18, mb = 28, iw = W - ml - mr, ih = H - mt - mb
const m = computed(() => {
  const max = Math.max(0, ...props.data.map(d => d.value))
  const step = niceStep(Math.max(max, 1) / 4), top = step * 4, bw = iw / Math.max(props.data.length, 1), mi = props.data.findIndex(d => d.value === max)
  return { max, step, top, bw, mi, lstep: Math.max(1, Math.ceil(props.data.length / 12)) }
})
const y = (i: number) => mt + ih - (ih * i) / 4
const peak = computed(() => ({ x: Math.min(Math.max(ml + m.value.mi * m.value.bw + m.value.bw / 2, ml + 20), W - 24), y: mt + ih - (ih * props.data[m.value.mi]?.value) / m.value.top - 6 }))
</script>

<template>
  <svg class="chart" :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="Sales chart">
    <template v-for="i in 5" :key="i"><line class="gl" :x1="ml" :x2="W - mr" :y1="y(i - 1)" :y2="y(i - 1)" /><text class="tx" :x="ml - 8" :y="y(i - 1) + 4" text-anchor="end">{{ compact(m.step * (i - 1)) }}</text></template>
    <template v-for="(d, k) in data" :key="k">
      <rect class="bar" :class="{ hi: k === m.mi && m.max > 0 }" :x="ml + k * m.bw + m.bw * 0.19" :y="mt + ih - (ih * d.value) / m.top" :width="m.bw * 0.62" :height="Math.max((ih * d.value) / m.top, 0)" rx="3"><title>{{ d.full || d.label }}: {{ inr(d.value) }}</title></rect>
      <text v-if="k % m.lstep === 0" class="tx" :x="ml + k * m.bw + m.bw / 2" :y="H - 9" text-anchor="middle">{{ d.label }}</text>
    </template>
    <text v-if="m.max > 0" class="tx hl" :x="peak.x" :y="peak.y" text-anchor="middle">{{ compact(m.max) }}</text>
  </svg>
</template>
