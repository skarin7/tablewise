import { defineStore } from 'pinia'
import { ref, shallowRef, type Component } from 'vue'

export const useUi = defineStore('ui', () => {
  const toasts = ref<{ id: number; msg: string }[]>([])
  let n = 0
  function toast(msg: string) { const id = ++n; toasts.value.push({ id, msg }); setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id) }, 3400) }
  const now = ref(Date.now())
  setInterval(() => { now.value = Date.now() }, 1000)

  // One modal at a time, like the prototype. `open` takes a component and its props; the component calls `close`.
  const modal = shallowRef<{ comp: Component; props: Record<string, any> } | null>(null)
  const open = (comp: Component, props: Record<string, any> = {}) => { modal.value = { comp, props } }
  const close = () => { modal.value = null }
  return { toasts, toast, now, modal, open, close }
})
