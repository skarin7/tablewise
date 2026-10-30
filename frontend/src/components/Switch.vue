<script setup lang="ts">
defineProps<{ on: boolean; label: string }>()
defineEmits<{ toggle: [] }>()
</script>

<template><button class="sw" type="button" role="switch" :aria-checked="on" :aria-label="label" @click="$emit('toggle')"></button></template>
