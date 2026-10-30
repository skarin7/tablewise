<script setup lang="ts">
import { reactive } from 'vue'
import Icon from './Icon.vue'
import ModalBox from './ModalBox.vue'
import { api } from '../lib/api'
import { useUi } from '../stores/ui'

const props = defineProps<{ dish?: any; categories: any[]; ingredients: any[]; onDone: () => void }>()
const ui = useUi()
const d = props.dish
const f = reactive({ name: d?.name ?? '', categoryId: d?.categoryId ?? props.categories[0]?.id ?? '', price: d?.price ?? '', prepMinutes: d?.prepMinutes ?? '', veg: d?.veg ?? true,
  recipe: (d?.recipe ?? [{ ingredientId: '', qtyPerPortion: '' }]).map((r: any) => ({ ...r })) as { ingredientId: string; qtyPerPortion: any }[] })

async function submit() {
  if (!f.name.trim()) return ui.toast('Give the dish a name')
  if (!(+f.price > 0)) return ui.toast('Enter a price above zero')
  const recipe = f.recipe.filter(r => r.ingredientId), seen = new Set<string>()
  for (const r of recipe) {
    if (!(+r.qtyPerPortion > 0)) return ui.toast('Enter a quantity for each ingredient')
    if (seen.has(r.ingredientId)) return ui.toast('Each ingredient can appear once per dish')
    seen.add(r.ingredientId)
  }
  const body = { name: f.name.trim(), categoryId: f.categoryId, price: +f.price, prepMinutes: +f.prepMinutes || 0, veg: f.veg, recipe: recipe.map(r => ({ ingredientId: r.ingredientId, qtyPerPortion: +r.qtyPerPortion })) }
  try { d ? await api.put('/menu-items/' + d.id, body) : await api.post('/menu-items', body); ui.close(); props.onDone(); ui.toast(f.name.trim() + (d ? ' updated' : ' added to the menu')) }
  catch (e: any) { ui.toast(e.message) }
}
</script>

<template>
  <ModalBox :title="d ? 'Edit dish' : 'Add dish'" form @submit="submit">
    <div class="fg">
      <div class="fld full"><label for="f-name">Dish name</label><input id="f-name" v-model="f.name" required></div>
      <div class="fld"><label for="f-cat">Category</label><select id="f-cat" v-model="f.categoryId"><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
      <div class="fld"><label for="f-price">Price (₹, before GST)</label><input id="f-price" v-model="f.price" type="number" min="1" step="any" required></div>
      <div class="fld"><label for="f-prep">Prep time (minutes)</label><input id="f-prep" v-model="f.prepMinutes" type="number" min="0"></div>
      <div class="fld"><span class="lb">Type</span><div class="row" style="min-height:36px;gap:16px">
        <label class="row"><input v-model="f.veg" type="radio" :value="true"> <i class="vm"></i> Veg</label><label class="row"><input v-model="f.veg" type="radio" :value="false"> <i class="vm nv"></i> Non-veg</label></div></div>
    </div>
    <div class="stack" style="gap:8px">
      <div class="row between"><span class="lb">Ingredients used per portion</span><button class="btn sm" type="button" @click="f.recipe.push({ ingredientId: '', qtyPerPortion: '' })"><Icon name="plus" :size="14" /> Add ingredient</button></div>
      <div v-for="(r, i) in f.recipe" :key="i" class="rr">
        <select v-model="r.ingredientId" aria-label="Ingredient"><option value="">Choose ingredient</option><option v-for="g in ingredients" :key="g.id" :value="g.id">{{ g.name }} ({{ g.unit }})</option></select>
        <input v-model="r.qtyPerPortion" type="number" step="any" min="0" placeholder="Per portion" aria-label="Quantity per portion">
        <button class="btn sm" type="button" aria-label="Remove ingredient" @click="f.recipe.splice(i, 1)"><Icon name="x" :size="14" /></button>
      </div>
      <div v-if="!f.recipe.length" class="empty small">No ingredients linked. Stock will not be tracked for this dish.</div>
    </div>
    <template #footer><button class="btn" type="button" @click="ui.close()">Cancel</button><button class="btn primary" type="submit">Save dish</button></template>
  </ModalBox>
</template>
