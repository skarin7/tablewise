<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import Icon from '../components/Icon.vue'
import PageHead from '../components/PageHead.vue'
import { api } from '../lib/api'
import { fmtDay, inr } from '../lib/format'
import { useUi } from '../stores/ui'

const ui = useUi()
const q = ref(''), list = ref<any[]>([]), walkIns = ref(0), loading = ref(true)
let t: any
async function load() {
  try { const r = await api.get('/customers', { q: q.value.trim() }); list.value = r.customers; walkIns.value = r.walkIns } catch (e: any) { ui.toast(e.message) } finally { loading.value = false }
}
onMounted(load)
watch(q, () => { clearTimeout(t); t = setTimeout(load, 250) })
</script>

<template>
  <PageHead title="Customers" :sub="`Built from orders that carry a name and phone number. ${walkIns} walk-in orders have no contact details.`" />
  <div class="card">
    <div class="tools"><div class="search"><Icon name="search" :size="16" /><input v-model="q" type="search" placeholder="Search name or phone" aria-label="Search customers"></div></div>
    <div v-if="loading" class="loading">Loading…</div>
    <div v-else class="tw"><table><thead><tr><th>Customer</th><th class="n">Orders</th><th class="n">Total spent</th><th class="n">Avg bill</th><th>Favourite</th><th>Last order</th></tr></thead><tbody>
      <tr v-if="!list.length"><td colspan="6"><div class="empty">No customers found.</div></td></tr>
      <tr v-for="c in list" :key="c.id"><td><div class="nm">{{ c.name }}<template v-if="c.orders >= 5"> <span class="pill accent">Regular</span></template></div><div class="sub mono">{{ c.phone }}</div></td>
        <td class="n">{{ c.orders }}</td><td class="n">{{ inr(c.totalSpent) }}</td><td class="n">{{ inr(c.averageBill) }}</td><td>{{ c.favouriteItem || '–' }}</td><td>{{ c.lastOrderAt ? fmtDay(c.lastOrderAt) : '–' }}</td></tr>
    </tbody></table></div>
  </div>
</template>
