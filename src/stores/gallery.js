import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { BackupBridge } from '../plugins/BackupPlugin'
import { useBackupStore } from './backup'
import { friendlyError } from '../utils/sanitize'

// Derives a "backed-up vs awaiting intake" view from the device's MediaStore,
// using a heuristic: items captured before `backupStore.lastBackupAt` are assumed
// catalogued. Good enough for a status dashboard without touching native code.

function folderLabel(folderPath) {
  if (!folderPath) return 'Unknown'
  const parts = folderPath.replace(/\\/g, '/').split('/').filter(Boolean)
  const tail = parts.slice(-2).join(' · ')
  return tail || folderPath
}

export const useGalleryStore = defineStore('gallery', () => {
  const photos = ref([])
  const videos = ref([])
  const loading = ref(false)
  const error = ref(null)
  const lastScanAt = ref(null)

  const backupStore = useBackupStore()

  // "Active" = items from folders the user has explicitly picked.  Empty
  // selection now means "nothing queued" (matches the native worker's
  // behavior and the UI hint) so the status card's "X awaiting backup"
  // doesn't claim your entire device gallery after a fresh install.
  const activePhotos = computed(() => {
    const sel = backupStore.selectedFolders
    if (!sel || sel.length === 0) return []
    return photos.value.filter(p => sel.includes(p.folderPath))
  })
  const activeVideos = computed(() => {
    const sel = backupStore.selectedFolders
    if (!sel || sel.length === 0) return []
    return videos.value.filter(v => sel.includes(v.folderPath))
  })

  const totalCount = computed(() => activePhotos.value.length + activeVideos.value.length)

  // Authoritative per-item check: an item counts as backed up only if its
  // native-side `<type>_<id>` is in `backupStore.backedUpIdSet`.  Using a
  // date-cutoff heuristic here produced false "up-to-date" states when the
  // user added a new folder whose old photos predated the cutoff.
  function isItemBackedUp(item) {
    const key = `${item.type}_${item.id}`
    return backupStore.backedUpIdSet.has(key)
  }

  const backedUpCount = computed(() => {
    let n = 0
    for (const p of activePhotos.value) if (isItemBackedUp(p)) n++
    for (const v of activeVideos.value) if (isItemBackedUp(v)) n++
    return n
  })

  const pendingCount = computed(() => totalCount.value - backedUpCount.value)

  // Option sizes reflect the active selection too, so "Back up photos · 48 MB"
  // tracks what's actually queued for intake rather than a device total.
  const photoBytes = computed(() => activePhotos.value.reduce((s, p) => s + (p.size || 0), 0))
  const videoBytes = computed(() => activeVideos.value.reduce((s, v) => s + (v.size || 0), 0))

  const byFolder = computed(() => {
    const map = new Map()
    const add = (item) => {
      const key = item.folderPath || deriveFolderPath(item.path) || 'Unknown'
      const bucket = map.get(key) || {
        folderPath: key, label: folderLabel(key),
        total: 0, catalogued: 0, pending: 0,
        totalSize: 0
      }
      bucket.total++
      bucket.totalSize += Number(item.size) || 0
      // Authoritative check: an item counts as backed up only if the native
      // worker has actually uploaded it (id is in backupStore.backedUpIdSet).
      // Previous code used a `dateAdded <= lastBackupTimestamp` heuristic,
      // which stayed at 0 for folders the user had just added even after
      // uploads completed, because the per-run timestamp-advance and the
      // per-item upload were decoupled.
      if (isItemBackedUp(item)) bucket.catalogued++
      else bucket.pending++
      map.set(key, bucket)
    }
    for (const p of photos.value) add(p)
    for (const v of videos.value) add(v)
    return [...map.values()].sort((a, b) => b.total - a.total)
  })

  const recent = computed(() => {
    const all = [...photos.value, ...videos.value]
    all.sort((a, b) => (b.dateAdded || 0) - (a.dateAdded || 0))
    return all.slice(0, 20)
  })

  function deriveFolderPath(path) {
    if (!path) return ''
    const clean = path.replace(/\\/g, '/')
    const idx = clean.lastIndexOf('/')
    return idx >= 0 ? clean.slice(0, idx) : ''
  }

  async function refresh() {
    loading.value = true
    error.value = null
    try {
      // Always scan every folder — the byFolder bucket list powers the folder
      // picker UI, and users need to see folders they haven't selected in order
      // to pick them. The `selectedFolders` filter is applied at backup time
      // in BackupWorker.java, not here.
      const [photoResult, videoResult] = await Promise.all([
        BackupBridge.getPhotos(0, []).catch(() => ({ media: [] })),
        BackupBridge.getVideos(0, []).catch(() => ({ media: [] }))
      ])
      const normalise = (item) => ({
        id: String(item.id || ''),
        name: item.name || '',
        path: item.path || '',
        uri: item.uri || '',
        size: Number(item.size) || 0,
        dateAdded: Number(item.dateAdded) || 0,
        mimeType: item.mimeType || '',
        type: item.type || 'photo',
        folderPath: deriveFolderPath(item.path)
      })
      photos.value = (photoResult.media || []).map(normalise)
      videos.value = (videoResult.media || []).map(normalise)
      lastScanAt.value = Date.now()
    } catch (e) {
      error.value = friendlyError(e)
    } finally {
      loading.value = false
    }
  }

  return {
    photos, videos, loading, error, lastScanAt,
    totalCount, backedUpCount, pendingCount,
    photoBytes, videoBytes,
    byFolder, recent,
    refresh
  }
})
