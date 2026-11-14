import { defineStore } from 'pinia'
import { ref } from 'vue'
import { pickSafe } from '../utils/sanitize'

const STAR_FIELDS = ['name', 'path', 'shareId', 'type', 'isDirectory', 'size']

export const useStarredStore = defineStore('starred', () => {
  const items = ref([])

  function toggleStar(file) {
    if (!file?.path || !file?.shareId) return
    const idx = items.value.findIndex(
      i => i.path === file.path && i.shareId === file.shareId
    )
    if (idx !== -1) {
      items.value.splice(idx, 1)
    } else {
      items.value.push({
        ...pickSafe(file, STAR_FIELDS),
        starredAt: Date.now()
      })
    }
  }

  function isStarred(path, shareId) {
    return items.value.some(i => i.path === path && i.shareId === shareId)
  }

  function removeStar(path, shareId) {
    items.value = items.value.filter(
      i => !(i.path === path && i.shareId === shareId)
    )
  }

  return { items, toggleStar, isStarred, removeStar }
}, {
  persist: true
})
