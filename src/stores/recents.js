import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { pickSafe } from '../utils/sanitize'

const RECENT_FIELDS = ['name', 'path', 'shareId', 'type', 'isDirectory', 'size']
// Known origins — anything outside this set falls back to 'browsed' so a
// typo in a caller can't pollute the UI's badge rendering.
const ORIGINS = new Set(['browsed', 'uploaded', 'downloaded', 'backed-up'])

export const useRecentStore = defineStore('recents', () => {
  const items = ref([])
  const MAX_ITEMS = 50

  function addRecent(file, meta = {}) {
    if (!file?.path || !file?.shareId) return
    const origin = ORIGINS.has(meta.origin) ? meta.origin : 'browsed'
    items.value = items.value.filter(
      i => !(i.path === file.path && i.shareId === file.shareId)
    )
    items.value.unshift({
      ...pickSafe(file, RECENT_FIELDS),
      origin,
      accessedAt: Date.now()
    })
    if (items.value.length > MAX_ITEMS) {
      items.value = items.value.slice(0, MAX_ITEMS)
    }
  }

  function clearHistory() {
    items.value = []
  }

  const grouped = computed(() => {
    const now = new Date()
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const yesterday = today - 86400000
    const weekAgo = today - 7 * 86400000

    const groups = { today: [], yesterday: [], thisWeek: [], older: [] }
    for (const item of items.value) {
      const t = item.accessedAt
      if (t >= today) groups.today.push(item)
      else if (t >= yesterday) groups.yesterday.push(item)
      else if (t >= weekAgo) groups.thisWeek.push(item)
      else groups.older.push(item)
    }
    return groups
  })

  return { items, grouped, addRecent, clearHistory }
}, {
  persist: true
})
