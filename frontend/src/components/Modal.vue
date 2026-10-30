<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, watch } from 'vue'
import { useUi } from '../stores/ui'
const ui = useUi()
const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && ui.modal) ui.close() }
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
watch(() => ui.modal, async m => {
  if (!m) return
  await nextTick()
  ;(document.querySelector('.ov input:not([type=hidden]),.ov select') as HTMLElement | null)?.focus()
})
</script>

<template>
  <div v-if="ui.modal" class="ov" @click.self="ui.close()">
    <component :is="ui.modal.comp" v-bind="ui.modal.props" />
  </div>
</template>
