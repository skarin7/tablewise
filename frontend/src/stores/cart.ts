import { defineStore } from 'pinia'
import { ref } from 'vue'

// Lives in a store so a half-built order survives a trip to another screen, as in the prototype.
export const useCart = defineStore('cart', () => {
  const type = ref<'dine_in' | 'takeaway' | 'delivery'>('dine_in')
  const tableId = ref('')
  const items = ref<{ id: string; qty: number }[]>([])
  const name = ref(''), phone = ref(''), dtype = ref<'flat' | 'percent'>('flat'), dval = ref('')
  function clear() { type.value = 'dine_in'; tableId.value = ''; items.value = []; name.value = ''; phone.value = ''; dtype.value = 'flat'; dval.value = '' }
  const qty = (id: string) => items.value.find(l => l.id === id)?.qty ?? 0
  return { type, tableId, items, name, phone, dtype, dval, clear, qty }
})
