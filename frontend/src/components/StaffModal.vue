<script setup lang="ts">
import { reactive } from 'vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { ROLES } from '../lib/roles'
import { useUi } from '../stores/ui'

const props = defineProps<{ onDone: () => void }>()
const ui = useUi(), f = reactive({ fullName: '', email: '', role: 'cashier' })
async function submit() {
  if (!f.fullName.trim()) return ui.toast('Enter the staff member’s name')
  try { await api.post('/staff', { fullName: f.fullName.trim(), email: f.email.trim(), role: f.role }); ui.close(); props.onDone(); ui.toast(`${f.fullName.trim()} added as ${(ROLES as any)[f.role].label}`) }
  catch (e: any) { ui.toast(e.message) }
}
</script>

<template>
  <ModalBox title="Invite staff" form @submit="submit">
    <div class="fg">
      <div class="fld"><label for="n-name">Name</label><input id="n-name" v-model="f.fullName" required></div>
      <div class="fld"><label for="n-role">Role</label><select id="n-role" v-model="f.role"><option v-for="(r, k) in ROLES" :key="k" :value="k">{{ r.label }}</option></select></div>
      <div class="fld full"><label for="n-mail">Email</label><input id="n-mail" v-model="f.email" type="email" required placeholder="They sign in with this address"></div>
    </div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit">Add staff member</button></template>
  </ModalBox>
</template>
