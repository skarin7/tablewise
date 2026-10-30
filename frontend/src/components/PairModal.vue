<script setup lang="ts">
import { reactive } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { useUi } from '../stores/ui'

const props = defineProps<{ counters: { id: string; name: string }[]; used: string[]; onDone: () => void }>()
const ui = useUi()
const free = props.counters.find(c => !props.used.includes(c.id)) ?? props.counters[0]
const f = reactive({ code: '', counterId: free?.id ?? '', name: '' })
const nameOf = (id: string) => props.counters.find(c => c.id === id)?.name ?? ''

async function submit() {
  const code = f.code.replace(/\D/g, '')
  if (code.length !== 6) return ui.toast('Enter the 6-digit code shown on the tablet')
  const name = f.name.trim() || nameOf(f.counterId) + ' display'
  try { await api.post('/devices/pair', { code, counterId: f.counterId, name }); ui.close(); props.onDone(); ui.toast(`${name} paired. Payments at ${nameOf(f.counterId)} now show on it.`) }
  catch (e: any) { ui.toast(e.status === 404 || e.status === 410 ? 'That code was not found or has expired. Check the code on the tablet and try again.' : e.message) }
}
</script>

<template>
  <ModalBox title="Add customer display" form @submit="submit">
    <p class="sub">Open the customer display page on the tablet and enter the 6-digit code it shows.</p>
    <div class="fg">
      <div class="fld full"><label for="p-code">Pairing code</label><input id="p-code" v-model="f.code" inputmode="numeric" autocomplete="off" maxlength="7" placeholder="000 000" required style="font:500 22px var(--font-mono);letter-spacing:.1em"></div>
      <div class="fld"><label for="p-counter">Shows payments from</label><select id="p-counter" v-model="f.counterId"><option v-for="c in counters" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
      <div class="fld"><label for="p-name">Display name</label><input id="p-name" v-model="f.name" :placeholder="nameOf(f.counterId) + ' display'"></div>
    </div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit">Pair display</button></template>
  </ModalBox>
</template>
