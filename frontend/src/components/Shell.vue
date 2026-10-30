<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import { useSession } from '../stores/session'
import { api } from '../lib/api'
import { useResource } from '../lib/live'
import { NAV, ROLES } from '../lib/roles'

const s = useSession(), route = useRoute(), router = useRouter()
const nav = computed(() => NAV.filter(n => s.views.includes(n.id)))
// Badge counts refresh from the same channels the screens use.
const counts = useResource(async () => ({
  orders: (await api.get<any[]>('/orders', { status: 'open' })).length,
  online: s.role === 'kitchen' ? 0 : (await api.get<any[]>('/online-orders', { tab: 'incoming' }).catch(() => [])).length,
  inventory: s.role === 'owner' ? (await api.get<any[]>('/ingredients').catch(() => [])).filter(i => +i.currentStock <= +i.reorderLevel).length : 0
}), ['orders', 'online', 'inventory'])
const badge = (id: string) => (counts.data.value as any)?.[id === 'orders' ? 'orders' : id === 'online' ? 'online' : id === 'inventory' ? 'inventory' : ''] || 0
async function signOut() { await s.signOut(); router.replace('/login') }
</script>

<template>
  <div class="shell">
    <aside class="rail">
      <div class="brand"><span class="mark"><Icon name="fork" /></span><div><div>Tablewise</div><div class="rn">{{ s.settings.name || s.me?.outletName }}</div></div></div>
      <nav class="nav" aria-label="Main">
        <router-link v-for="n in nav" :key="n.id" :to="'/' + n.id" :aria-current="route.path === '/' + n.id ? 'page' : undefined">
          <Icon :name="n.icon" /><span>{{ n.label }}</span>
          <span v-if="badge(n.id)" class="pill badge" :class="n.id === 'orders' ? 'info' : 'warn'">{{ badge(n.id) }}</span>
        </router-link>
      </nav>
      <div class="rail-foot">
        <div v-if="s.can.pay && s.counters.length" class="fld"><label for="term">This terminal</label>
          <select id="term" :value="s.counter?.id" @change="s.setCounter(($event.target as HTMLSelectElement).value)"><option v-for="c in s.counters" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        <div class="fld"><span class="lb">Signed in</span><div>{{ s.me?.name || ROLES[s.role].label }}</div><div v-if="s.me?.name" class="sub">{{ ROLES[s.role].label }}</div></div>
        <button class="btn sm" type="button" @click="signOut">Sign out</button>
      </div>
    </aside>
    <main id="main"><router-view /></main>
  </div>
</template>
