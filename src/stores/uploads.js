import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { BackupBridge } from '../plugins/BackupPlugin'
import { useSettingsStore } from './settings'
import { useSmbStore } from './smb'
import { useRecentStore } from './recents'
import { friendlyError } from '../utils/sanitize'
import { getFileType } from '../utils/helpers'

function inferType(name) {
  // Keep parity with the native-side bucketing so Recents badges match
  // the file-browser icons; isDirectory is always false for uploads.
  return getFileType(name || '', false)
}

/**
 * Durable upload queue.  Actual persistence + uploading lives in the native
 * UploadWorker; this store is a JS-side mirror for the UI.
 *
 * A 2-second poll updates the list while a worker is running; when idle we
 * back off so we don't keep the bridge warm for nothing.
 */
export const useUploadsStore = defineStore('uploads', () => {
  // Mirror of native queue — items look like:
  //   { id, name, path, host, share, size, status, error, addedAt }
  // status: queued | uploading | failed
  const items = ref([])
  const running = ref(false)
  const queued = ref(false)
  const phase = ref('')
  const currentId = ref('')
  const currentName = ref('')
  const currentIndex = ref(0)
  const totalFiles = ref(0)
  // Session counters from the native worker — reset when a new run starts.
  const sessionUploadedCount = ref(0)
  const sessionFailedCount = ref(0)
  const currentBytes = ref(0)
  const currentSize = ref(0)
  const lastError = ref(null)

  // Byte-rate rolling window for ETA.  Samples are cleared when the worker
  // goes idle.  Throttled to poll-granularity — 2 s min between samples.
  const byteSamples = ref([])
  const BYTE_WINDOW_MS = 60_000
  const etaSeconds = ref(0)
  let lastCumulative = 0
  let sessionCumulativeBase = 0  // bytes already uploaded before this run

  let pollTimer = null
  // Previous poll's active-item snapshot by id.  Used to detect which items
  // disappeared from the native queue (indicating a successful upload) so
  // we can push them into Recents with origin='uploaded'.  Failed uploads
  // stay in the queue with status='failed', so they don't qualify.
  let prevActiveById = new Map()

  const activeCount = computed(() =>
    items.value.filter(i => i.status === 'uploading' || i.status === 'queued').length
  )

  const failedCount = computed(() =>
    items.value.filter(i => i.status === 'failed').length
  )

  async function refresh() {
    try {
      const s = await BackupBridge.getUploadStatus()
      const nextItems = Array.isArray(s.queue) ? s.queue : []

      // Detect successful uploads: any id that was active (queued/uploading)
      // in the previous tick and is no longer present in the current queue
      // finished successfully.  The native worker removes successful entries
      // from the queue but keeps failures with status='failed', so this
      // naturally excludes failures.
      const nextById = new Map()
      for (const it of nextItems) nextById.set(it.id, it)
      const settingsStore = useSettingsStore()
      const recentStore = useRecentStore()
      for (const [id, prev] of prevActiveById) {
        if (nextById.has(id)) continue
        const share = settingsStore.shares.find(
          sh => sh.host === prev.host && sh.shareName === prev.share
        )
        if (!share) continue
        recentStore.addRecent(
          {
            name: prev.name,
            path: prev.path,
            shareId: share.id,
            type: inferType(prev.name),
            isDirectory: false,
            size: prev.size
          },
          { origin: 'uploaded' }
        )
      }
      prevActiveById = new Map()
      for (const it of nextItems) {
        if (it.status === 'uploading' || it.status === 'queued') {
          prevActiveById.set(it.id, it)
        }
      }

      items.value = nextItems
      running.value = !!s.running
      queued.value = !!s.queued
      phase.value = s.phase || ''
      currentId.value = s.currentId || ''
      currentName.value = s.currentName || ''
      currentIndex.value = Number(s.currentIndex) || 0
      totalFiles.value = Number(s.totalFiles) || 0
      sessionUploadedCount.value = Number(s.uploadedCount) || 0
      sessionFailedCount.value = Number(s.failedCount) || 0
      currentBytes.value = Number(s.currentBytes) || 0
      currentSize.value = Number(s.currentSize) || 0

      // ETA: track a monotonic cumulative-bytes number across the run.
      // Every time we see `currentBytes` shrink (file boundary), we lock in
      // the outgoing file's size and add to `sessionCumulativeBase`.  Then
      // cumulative = base + currentBytes — always monotonically increasing.
      if (running.value && items.value.length > 0) {
        // Detect file boundary: currentBytes dropped.  We don't know the
        // exact size of the outgoing file after it's removed from the
        // queue, so use the peak of lastCumulative (which was base + old
        // currentBytes) as the pinned value going forward.
        if (currentBytes.value < lastCumulative - (sessionCumulativeBase || 0) - 1024) {
          // currentBytes fell meaningfully — new file started.  Pin the
          // previous peak into the session base.
          sessionCumulativeBase = lastCumulative
        }
        const cumulative = sessionCumulativeBase + currentBytes.value
        const now = Date.now()
        if (cumulative !== lastCumulative) {
          byteSamples.value.push({ t: now, b: cumulative })
          lastCumulative = cumulative
          const cutoff = now - BYTE_WINDOW_MS
          byteSamples.value = byteSamples.value.filter(x => x.t >= cutoff)
        }
        // Remaining = sum of all not-yet-done entries (queued + the in-flight
        // file's leftover bytes).  Failed items aren't counted — user will
        // retry or drop them.
        const remainingBytes = items.value.reduce((s, i) => {
          if (i.status === 'queued') return s + (i.size || 0)
          if (i.status === 'uploading') return s + Math.max(0, (i.size || 0) - currentBytes.value)
          return s
        }, 0)
        const first = byteSamples.value[0]
        const last = byteSamples.value[byteSamples.value.length - 1]
        if (first && last && last.b > first.b && (last.t - first.t) > 2000) {
          const bps = (last.b - first.b) / ((last.t - first.t) / 1000)
          if (bps > 0) {
            etaSeconds.value = Math.max(0, Math.round(remainingBytes / bps))
          }
        }
      } else {
        byteSamples.value = []
        lastCumulative = 0
        sessionCumulativeBase = 0
        etaSeconds.value = 0
      }
    } catch (e) {
      lastError.value = friendlyError(e)
    }
  }

  function startPolling() {
    if (pollTimer) return
    refresh()
    pollTimer = setInterval(refresh, 2000)
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  }

  /**
   * Enqueue files for upload.  `files` is an array of local file entries:
   *   { localUri, name, size? }
   * Together with a target share + path, they become persistent queue
   * entries the native worker drains.
   */
  async function enqueue({ files, share, path = '' }) {
    if (!share) return { success: false, error: 'No shelf' }
    if (!files || files.length === 0) return { success: false, error: 'Nothing to upload' }
    const settingsStore = useSettingsStore()
    const creds = await settingsStore.getShareCredentials(share.id)

    const trimmedPath = String(path || '').replace(/^[/\\]+/, '').replace(/[/\\]+$/, '')
    const items = files.map(f => ({
      localUri: f.localUri,
      name: f.name,
      size: Number(f.size || 0),
      host: share.host,
      share: share.shareName,
      path: (trimmedPath ? trimmedPath + '/' : '') + f.name,
      username: creds.username || '',
      password: creds.password || ''
    }))
    try {
      await BackupBridge.enqueueUpload(items)
      refresh()
      return { success: true, queued: items.length }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    }
  }

  async function remove(id) {
    await BackupBridge.removeUpload(id)
    refresh()
  }

  async function retry(id) {
    await BackupBridge.retryUpload(id)
    refresh()
  }

  async function stopAll() {
    await BackupBridge.stopUpload()
    refresh()
  }

  const queuedCount = computed(() => items.value.filter(i => i.status === 'queued').length)
  const uploadingCount = computed(() => items.value.filter(i => i.status === 'uploading').length)

  return {
    items, running, queued, phase,
    currentId, currentName, currentIndex, totalFiles,
    sessionUploadedCount, sessionFailedCount,
    currentBytes, currentSize,
    etaSeconds,
    activeCount, failedCount, queuedCount, uploadingCount,
    lastError,
    refresh, startPolling, stopPolling,
    enqueue, remove, retry, stopAll
  }
})
