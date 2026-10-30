<script setup lang="ts">
defineProps<{ modelValue: string; options: [string, string][]; label: string }>()
defineEmits<{ 'update:modelValue': [string] }>()
</script>

<template>
  <div class="seg" role="group" :aria-label="label"><button v-for="o in options" :key="o[0]" type="button" :aria-pressed="modelValue === o[0]" @click="$emit('update:modelValue', o[0])">{{ o[1] }}</button></div>
</template>
