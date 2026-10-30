<script setup lang="ts">
import ModalBox from './ModalBox.vue'
import { useUi } from '../stores/ui'
const props = defineProps<{ title: string; message: string; label: string; onYes: () => void | Promise<void> }>()
const ui = useUi()
const yes = () => { ui.close(); props.onYes() }
</script>

<template>
  <ModalBox :title="title">
    <p>{{ message }}</p>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="button" @click="yes">{{ label }}</button></template>
  </ModalBox>
</template>
