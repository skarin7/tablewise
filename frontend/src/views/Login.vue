<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import Seg from '../components/Seg.vue'
import { DEV_USERS, endSession, localDev, signInLocal } from '../lib/auth'
import { supabase } from '../lib/supabase'
import { useSession } from '../stores/session'

const s = useSession(), router = useRouter(), route = useRoute()
const via = ref('email'), id = ref(''), code = ref(''), sent = ref(false), busy = ref(false), err = ref('')
const target = () => (via.value === 'email' ? { email: id.value.trim(), phone: undefined } : { phone: id.value.trim().startsWith('+') ? id.value.trim() : '+91' + id.value.replace(/\D/g, ''), email: undefined })

async function send() {
  busy.value = true; err.value = ''
  const { error } = await supabase.auth.signInWithOtp(target())
  busy.value = false
  if (error) err.value = error.message; else sent.value = true
}
async function verify() {
  busy.value = true; err.value = ''
  const token = code.value.trim()
  const { error } = await supabase.auth.verifyOtp(via.value === 'email' ? { email: id.value.trim(), token, type: 'email' } : { phone: target().phone!, token, type: 'sms' })
  if (error) { busy.value = false; err.value = error.message; return }
  try { await s.load() } catch (e: any) { await endSession(); busy.value = false; err.value = e.message || 'This account has no active staff profile at this restaurant.'; return }
  busy.value = false
  router.replace((route.query.next as string) || '/' + s.views[0])
}
async function devLogin(id: string) {
  signInLocal(id); err.value = ''
  try { await s.load(); router.replace('/' + s.views[0]) } catch (e: any) { await endSession(); err.value = e.message || 'No active staff profile for this user. Has the seed finished?' }
}
</script>

<template>
  <div class="login">
    <div v-if="localDev" class="card">
      <div class="brand"><span class="mark"><Icon name="fork" /></span><div><div>Tablewise</div><div class="rn">Local development sign-in</div></div></div>
      <p class="sub">Demo staff from the seed data. This mode only exists in the local docker build.</p>
      <p v-if="err" class="err" role="alert">{{ err }}</p>
      <button v-for="u in DEV_USERS" :key="u.id" class="btn block" type="button" @click="devLogin(u.id)">Sign in as {{ u.label }}</button>
    </div>
    <form v-else class="card" @submit.prevent="sent ? verify() : send()">
      <div class="brand"><span class="mark"><Icon name="fork" /></span><div><div>Tablewise</div><div class="rn">Sign in to your restaurant</div></div></div>
      <Seg v-model="via" label="Sign in with" :options="[['email', 'Email'], ['phone', 'Phone']]" />
      <div class="fld"><label for="id">{{ via === 'email' ? 'Email address' : 'Phone number' }}</label>
        <input id="id" v-model="id" :type="via === 'email' ? 'email' : 'tel'" required autocomplete="username" :disabled="sent" :placeholder="via === 'email' ? 'you@restaurant.com' : '10 digit mobile number'"></div>
      <div v-if="sent" class="fld"><label for="code">One-time code</label><input id="code" v-model="code" inputmode="numeric" autocomplete="one-time-code" required placeholder="Code we sent you"></div>
      <p v-if="err" class="err" role="alert">{{ err }}</p>
      <button class="btn primary block" type="submit" :disabled="busy">{{ sent ? 'Sign in' : 'Send code' }}</button>
      <button v-if="sent" class="btn block" type="button" @click="sent = false; code = ''">Use a different {{ via }}</button>
    </form>
  </div>
</template>
