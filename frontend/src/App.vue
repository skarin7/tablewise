<script setup lang="ts">
import { useUi } from './stores/ui'
import Modal from './components/Modal.vue'
const ui = useUi()
</script>

<template>
  <router-view />
  <Modal />
  <div class="toasts" role="status" aria-live="polite"><div v-for="t in ui.toasts" :key="t.id" class="toast">{{ t.msg }}</div></div>
</template>
