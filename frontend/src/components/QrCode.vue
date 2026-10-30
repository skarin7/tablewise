<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import QRCode from 'qrcode'
const props = defineProps<{ value: string }>()
const svg = ref('')
watchEffect(async () => { svg.value = props.value ? await QRCode.toString(props.value, { type: 'svg', margin: 1 }) : '' })
</script>

<template><div class="qr" role="img" aria-label="UPI QR code" v-html="svg" /></template>
