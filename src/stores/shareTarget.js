import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Transient holder for items delivered via Android's share target.  The
 * layout listens on app boot, pushes to this store, then routes the user to
 * /share-in which reads `items` and uploads them.  No persistence — if the
 * user force-quits mid-flow, the share intent is gone.
 */
export const useShareTargetStore = defineStore('shareTarget', () => {
  const items = ref([])
  function set(next) { items.value = Array.isArray(next) ? next : [] }
  function clear() { items.value = [] }
  return { items, set, clear }
})
