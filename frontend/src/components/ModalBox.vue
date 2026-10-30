<script setup lang="ts">
import Icon from './Icon.vue'
import { useUi } from '../stores/ui'
// Shared dialog frame: title bar, body, footer. `form` turns it into a <form> whose submit emits `submit`.
defineProps<{ title: string; wide?: boolean; form?: boolean }>()
defineEmits<{ submit: [] }>()
const ui = useUi()
</script>

<template>
  <div class="md" :class="{ wide }" role="dialog" aria-modal="true" :aria-label="title">
    <component :is="form ? 'form' : 'div'" @submit.prevent="$emit('submit')">
      <div class="mh"><h2>{{ title }}</h2><button class="btn sm" type="button" aria-label="Close" @click="ui.close()"><Icon name="x" :size="16" /></button></div>
      <div class="mb"><slot /></div>
      <div class="mf"><slot name="footer" /></div>
    </component>
  </div>
</template>
