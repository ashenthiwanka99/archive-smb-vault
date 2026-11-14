import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from './settings'
import { useRecentStore } from './recents'
import { validateFilename, validateRelativePath } from '../utils/validation'
import { friendlyError, pickSafe } from '../utils/sanitize'
import { getFileType } from '../utils/helpers'

const DOWNLOAD_FIELDS = [
  'id', 'name', 'path', 'shareId', 'host', 'shareName',
  'size', 'type', 'progress', 'status', 'startedAt',
  'completedAt', 'localPath', 'error'
]

const MAX_CONCURRENT = 3
const MAX_HISTORY = 100
const HISTORY_TTL_MS = 30 * 24 * 60 * 60 * 1000 // 30 days
const MIN_DISPATCH_INTERVAL_MS = 100

export const useDownloadStore = defineStore('downloads', () => {
  const queue = ref([])
  const history = ref([])
  let lastDispatchAt = 0

  const activeCount = computed(() =>
    queue.value.filter(d => d.status === 'downloading').length
  )

  function pruneHistory() {
    const cutoff = Date.now() - HISTORY_TTL_MS
    history.value = history.value.filter(h => (h.completedAt || h.startedAt || 0) > cutoff)
    if (history.value.length > MAX_HISTORY) {
      history.value = history.value.slice(0, MAX_HISTORY)
    }
  }

  function enqueue(file) {
    const nameCheck = validateFilename(file?.name || '')
    if (!nameCheck.ok) return null
    const pathCheck = validateRelativePath(file?.path || '')
    if (!pathCheck.ok) return null
    if (!file?.shareId) return null

    const exists = queue.value.some(d => d.path === pathCheck.value && d.shareId === file.shareId)
    if (exists) return null

    const item = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      name: nameCheck.value,
      path: pathCheck.value,
      shareId: String(file.shareId),
      host: String(file.host || ''),
      shareName: String(file.shareName || ''),
      size: Number(file.size) || 0,
      type: String(file.type || 'unknown'),
      progress: 0,
      status: 'queued',
      startedAt: null,
      completedAt: null,
      localPath: null,
      error: null
    }
    queue.value.push(item)
    processQueue()
    return item.id
  }

  async function processQueue() {
    if (activeCount.value >= MAX_CONCURRENT) return
    const next = queue.value.find(d => d.status === 'queued')
    if (!next) return

    const now = Date.now()
    const wait = Math.max(0, MIN_DISPATCH_INTERVAL_MS - (now - lastDispatchAt))
    if (wait > 0) {
      setTimeout(processQueue, wait)
      return
    }
    lastDispatchAt = Date.now()

    next.status = 'downloading'
    next.startedAt = Date.now()
    next.progress = 5

    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === next.shareId)
    let creds = { username: '', password: '' }
    if (share) {
      try { creds = await settingsStore.getShareCredentials(share.id) } catch { /* use empty */ }
    }

    try {
      const result = await SmbPlugin.downloadFile({
        host: next.host,
        share: next.shareName,
        path: next.path,
        localPath: `/Download/${next.name}`,
        username: creds.username,
        password: creds.password
      })
      next.status = 'completed'
      next.progress = 100
      next.completedAt = Date.now()
      next.localPath = result.localPath || `/Download/${next.name}`
      moveToHistory(next)
      // Feed Recents so finishing a download surfaces the file in the
      // unified "recent" stream, not just inside the Downloads tab.
      try {
        const recents = useRecentStore()
        recents.addRecent(
          {
            name: next.name,
            path: next.path,
            shareId: next.shareId,
            type: getFileType(next.name || '', false),
            isDirectory: false,
            size: next.size || 0
          },
          { origin: 'downloaded' }
        )
      } catch { /* non-fatal */ }
    } catch (e) {
      next.status = 'failed'
      next.error = friendlyError(e)
      next.completedAt = Date.now()
      moveToHistory(next)
    }

    processQueue()
  }

  function moveToHistory(item) {
    history.value.unshift(pickSafe(item, DOWNLOAD_FIELDS))
    queue.value = queue.value.filter(d => d.id !== item.id)
    pruneHistory()
  }

  function retry(id) {
    const item = history.value.find(d => d.id === id)
    if (item && item.status === 'failed') {
      history.value = history.value.filter(d => d.id !== id)
      enqueue({
        name: item.name,
        path: item.path,
        shareId: item.shareId,
        host: item.host,
        shareName: item.shareName,
        size: item.size,
        type: item.type
      })
    }
  }

  function clearHistory() {
    history.value = []
  }

  function cancelDownload(id) {
    queue.value = queue.value.filter(d => d.id !== id)
  }

  /**
   * Recursively walks a folder on the shelf and enqueues every non-directory
   * file into the download queue.  Returns a Promise that resolves to
   * `{ taskId, enqueued }` once the walker finishes (or is cancelled).
   *
   * The native walker streams results in batches via `onSearchResults`; each
   * match we get is converted into a download queue entry.  Folders are
   * skipped — only files are downloadable.
   */
  async function enqueueFolder({ share, folderPath }) {
    if (!share) return { taskId: '', enqueued: 0, error: 'No shelf' }
    const pathCheck = validateRelativePath(folderPath || '')
    if (!pathCheck.ok) return { taskId: '', enqueued: 0, error: pathCheck.error }

    const settingsStore = useSettingsStore()
    const creds = await settingsStore.getShareCredentials(share.id)

    let enqueued = 0
    let taskId = ''
    let completed = false

    return await new Promise(async (resolve) => {
      // Subscribe BEFORE kicking the walker so we don't miss the first batch.
      const offResults = await SmbPlugin.onSearchResults(({ taskId: t, matches }) => {
        if (t !== taskId || !matches?.length) return
        for (const m of matches) {
          if (m.isDirectory) continue
          const id = enqueue({
            name: m.name,
            path: m.path,
            shareId: share.id,
            host: share.host,
            shareName: share.shareName,
            size: m.size,
            type: m.type
          })
          if (id) enqueued++
        }
      })

      const offComplete = await SmbPlugin.onSearchComplete(({ taskId: t, canceled, error }) => {
        if (t !== taskId || completed) return
        completed = true
        offResults?.()
        offComplete?.()
        resolve({ taskId, enqueued, canceled: !!canceled, error: error || null })
      })

      try {
        const res = await SmbPlugin.recursiveSearch({
          host: share.host,
          share: share.shareName,
          path: pathCheck.value,
          username: creds.username,
          password: creds.password,
          query: '',         // empty = enumerate everything
          types: ''          // no type filter
        })
        taskId = res?.taskId || ''
        if (!taskId) {
          completed = true
          offResults?.()
          offComplete?.()
          resolve({ taskId: '', enqueued, error: 'No task id' })
        }
      } catch (e) {
        completed = true
        offResults?.()
        offComplete?.()
        resolve({ taskId: '', enqueued, error: friendlyError(e) })
      }
    })
  }

  // Prune once on store creation so old rehydrated history gets cleaned.
  pruneHistory()

  return {
    queue,
    history,
    activeCount,
    enqueue,
    enqueueFolder,
    retry,
    clearHistory,
    cancelDownload
  }
}, {
  persist: {
    pick: ['history']
  }
})
