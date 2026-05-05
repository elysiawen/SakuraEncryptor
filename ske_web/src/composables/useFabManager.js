import { reactive, computed } from 'vue'

export const FAB_SIZE = 44
export const FAB_GAP = 12
export const FAB_BASE_DESKTOP = 20
export const FAB_BASE_MOBILE = 16

const registry = reactive([])

export function useFabManager() {
  function register(id, order) {
    if (!registry.find(f => f.id === id)) {
      registry.push({ id, order })
      registry.sort((a, b) => a.order - b.order)
    }
  }

  function unregister(id) {
    const i = registry.findIndex(f => f.id === id)
    if (i !== -1) registry.splice(i, 1)
  }

  function getOrder(id) {
    const fab = registry.find(f => f.id === id)
    return fab ? fab.order : 0
  }

  const count = computed(() => registry.length)
  const ids = computed(() => registry.map(f => f.id))

  return { registry, register, unregister, getOrder, count, ids }
}
