<script setup lang="ts">
import { useRouter } from 'vue-router'
import ModalBox from './ModalBox.vue'
import PayModal from './PayModal.vue'
import { fmtTime } from '../lib/format'
import { whereLabel } from '../lib/roles'
import { useSession } from '../stores/session'
import { useUi } from '../stores/ui'

const props = defineProps<{ order: any }>()
const s = useSession(), ui = useUi(), router = useRouter()
const o = props.order
const board = () => { ui.close(); router.push('/orders') }
const pay = () => ui.open(PayModal, { order: o, onDone: () => {} })
</script>

<template>
  <ModalBox title="Sent to kitchen">
    <div class="kot"><div class="row between"><span class="big">#{{ o.orderNo }}</span><span class="pill accent">{{ whereLabel(o) }}</span></div>
      <div class="sub">{{ fmtTime(o.placedAt) }}<template v-if="o.billToName"> · {{ o.billToName }}</template></div>
      <ul><li v-for="(i, k) in o.items" :key="k"><b>{{ i.quantity }}×</b><span>{{ i.name }}</span></li></ul></div>
    <p class="sub">The kitchen board now shows this ticket under New. Ingredient stock has been reduced.</p>
    <p v-if="o.orderType === 'dine_in'" class="sub">Dine-in guests usually pay after the meal. Collect payment from the Orders board when they ask for the bill.</p>
    <template #footer>
      <button class="btn" type="button" @click="ui.close()">New order</button><button class="btn" type="button" @click="board">Open orders board</button>
      <button v-if="s.can.pay" class="btn primary" type="button" @click="pay">Collect payment</button>
    </template>
  </ModalBox>
</template>
