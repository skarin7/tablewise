<script setup lang="ts">
defineProps<{ title: string; sub?: string }>()
</script>

<template>
  <div class="ph"><div><h1>{{ title }}</h1><p v-if="sub" class="sub">{{ sub }}</p></div><div v-if="$slots.default" class="row wrap"><slot /></div></div>
</template>
