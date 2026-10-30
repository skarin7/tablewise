<script setup lang="ts">
defineProps<{ o: any }>()
const MODE: Record<string, string> = { cash: 'Cash', upi_qr: 'UPI', card: 'Card', payment_link: 'Link', platform: 'Online' }
</script>

<template>
  <span v-if="o.status === 'cancelled'" class="pill bad">Cancelled</span>
  <span v-else-if="o.paymentStatus === 'paid'" class="pill good">Paid{{ o.payMethod ? ' · ' + (MODE[o.payMethod] || o.payMethod) : '' }}</span>
  <span v-else class="pill warn">Unpaid</span>
</template>
