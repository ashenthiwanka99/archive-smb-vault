import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { BackupBridge } from '../plugins/BackupPlugin'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from './settings'
import { validateRelativePath } from '../utils/validation'
import { friendlyError } from '../utils/sanitize'

export const useBackupStore = defineStore('backup', () => {
  const enabled = ref(false)
  const backupShareId = ref('')
  const backupPath = ref('Backup/Phone')
  const lastBackupAt = ref(null)
  const backedUpCount = ref(0)
  const lastBackupFileCount = ref(0)
  const backupInProgress = ref(false)
  const backupStatus = ref('idle')
  const wifiOnly = ref(true)
  const homeNetworkOnly = ref(false)
  const chargerOnly = ref(false)
  // Time-window: optional "only run between X and Y". Hours are local time,
  // 0-23. When `windowEnabled` is false the hour fields are ignored; the
  // defaults (2–6) only matter when the user flips the toggle on.
  const windowEnabled = ref(false)
  const windowStartHour = ref(2)
  const windowEndHour = ref(6)
  // NAS layout: 'source' = <source-folder>/<YYYY-MM>/ (default, preserves the
  // phone's DCIM / Pictures / Screenshots split). 'year-month' = <YYYY>/<MM>/.
  // 'flat' = dump at the backup root.  The underlying worker handles the rest.
  const backupOrganization = ref('source')
  // Safe delete-from-phone: after a successful upload+verify, the worker
  // appends entries to a native-side queue with `eligibleAt = now + grace`.
  // The UI reads this queue and fires MediaStore.createDeleteRequest when
  // the user confirms.
  const autoCleanup = ref(false)
  const cleanupGraceDays = ref(7)
  const cleanupQueue = ref([])   // full entries, sorted newest-first
  const cleanupEligible = ref(0) // count eligible for delete right now
  const cleanupTotalBytes = ref(0)
  const cleanupEligibleBytes = ref(0)
  // WorkManager's periodic minimum is 15 minutes; we default to 1 hour.
  const scheduleInterval = ref(60 * 60 * 1000)
  const includeVideos = ref(true)
  const includePhotos = ref(true)
  // Glob-style filename or path patterns the backup should skip.  Useful for
  // keeping the shelf clean of screenshot_* noise, app caches, or the
  // .thumbnails directories Android creates everywhere.  See
  // globToRegex() in BackupPlugin.java for the supported pattern syntax.
  const excludePatterns = ref(['.thumbnails/*', '*.tmp', 'Trash/*'])
  const backupError = ref(null)
  const selectedFolders = ref([])
  const availableFolders = ref([])
  // Storage stats for the "how much space does the backup take" card.
  const backupSize = ref(0)          // bytes used by files under backupPath
  const backupFileCount = ref(0)     // count of files under backupPath
  const shelfFreeBytes = ref(0)      // free bytes on the NAS share
  const statsLoading = ref(false)
  const statsUpdatedAt = ref(0)
  // Reflects the native WorkManager state — true when the backup worker is
  // running OR queued. backupInProgress only reflects the tiny window of the
  // JS runNow() call, so UI needs this for the stop-button visibility.
  const isWorkRunning = ref(false)
  const isWorkQueued = ref(false)
  // Per-file live progress from WorkManager — populated by refreshWorkStatus.
  // Reset to empty/0 whenever work is not active so the UI doesn't linger on
  // the last filename from a completed run.
  const currentFile = ref('')
  const currentIndex = ref(0)
  const totalFiles = ref(0)
  const workPhase = ref('')
  let workPollTimer = null

  // Rolling-window rate + ETA for the backup worker.  Each time the poll
  // sees a new `currentIndex`, we log a {t, idx} sample; ETA computed from
  // the first and last samples inside a 60s window.  `etaSeconds` is the
  // number of seconds the remaining files are expected to take.
  const rateSamples = ref([])
  const RATE_WINDOW_MS = 60_000
  const etaSeconds = ref(0)
  // Fallback ETA uses wall-clock elapsed since the first uploading tick,
  // divided by completed files, × remaining.  Gives the UI an estimate on
  // the very first tick after at least one file finished — the rolling-
  // window sampler needs 2 file transitions before it fires and that can
  // take minutes on large videos.
  let uploadRunStartedAt = 0

  // Per-run history from the native worker, newest-last. Each record:
  //   { startedAt, finishedAt, uploaded, failed, skippedDup, bytes, trigger, error? }
  // Feeds the timeline list and bytes-per-day sparkline.
  const history = ref([])

  // Authoritative set of "<type>_<mediaStoreId>" strings that the native worker
  // has already uploaded.  Feeding this to the gallery store lets the UI
  // compute pending counts per-item instead of via a date-cutoff heuristic,
  // which was wrong whenever the user added a new folder with older photos.
  const backedUpIdSet = ref(new Set())

  // Verify-against-shelf state — mirror of the backup progress fields.
  const verifyRunning = ref(false)
  const verifyQueued = ref(false)
  const verifyPhase = ref('')
  const verifyDetail = ref('')
  const verifyCurrent = ref(0)
  const verifyTotal = ref(0)
  const verifyDropped = ref(0)
  const verifyLastRunAt = ref(null)
  const verifyError = ref(null)

  function setBackupPath(path) {
    // Strip any leading slash; store as a relative SMB path.
    const trimmed = String(path || '').replace(/^[/\\]+/, '')
    const check = validateRelativePath(trimmed)
    if (!check.ok) {
      backupError.value = `Invalid backup path: ${check.error}`
      return false
    }
    backupError.value = null
    backupPath.value = check.value
    return true
  }

  async function syncToNative() {
    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === backupShareId.value) || settingsStore.shares[0]
    if (!share) {
      backupError.value = 'No share configured for backup'
      return false
    }
    const pathCheck = validateRelativePath(backupPath.value)
    if (!pathCheck.ok) {
      backupError.value = `Invalid backup path: ${pathCheck.error}`
      return false
    }
    const creds = await settingsStore.getShareCredentials(share.id)

    try {
      await BackupBridge.configure({
        enabled: enabled.value,
        host: share.host,
        shareName: share.shareName,
        backupPath: pathCheck.value,
        username: creds.username || '',
        password: creds.password || '',
        includePhotos: includePhotos.value,
        includeVideos: includeVideos.value,
        wifiOnly: wifiOnly.value,
        homeNetworkOnly: homeNetworkOnly.value,
        chargerOnly: chargerOnly.value,
        windowEnabled: windowEnabled.value,
        windowStartHour: windowStartHour.value,
        windowEndHour: windowEndHour.value,
        backupOrganization: backupOrganization.value,
        autoCleanup: autoCleanup.value,
        cleanupGraceDays: cleanupGraceDays.value,
        scheduleInterval: scheduleInterval.value,
        selectedFolders: selectedFolders.value,
        excludePatterns: excludePatterns.value
      })
      backupError.value = null
      return true
    } catch (e) {
      backupError.value = friendlyError(e)
      return false
    }
  }

  async function toggleBackup() {
    enabled.value = !enabled.value
    await syncToNative()
  }

  async function ensureMediaPermissions() {
    const check = await BackupBridge.checkMediaPermissions()
    if (check.granted) return true
    const req = await BackupBridge.requestMediaPermissions()
    return !!req.granted
  }

  async function getNetworkState() {
    try { return await BackupBridge.getNetworkStatus() }
    catch { return { connected: false, wifi: false, metered: true } }
  }

  /**
   * Checks network against the user's wifi-only preference.
   * Returns { ok: true } if OK to proceed, or { ok: false, reason: 'no-network'|'mobile-data' }
   * so the caller (UI) can decide whether to prompt/confirm.
   */
  async function checkNetworkForBackup() {
    const net = await getNetworkState()
    if (!net.connected) return { ok: false, reason: 'no-network', net }
    if (wifiOnly.value && !net.wifi) return { ok: false, reason: 'mobile-data', net }
    return { ok: true, net }
  }

  /**
   * Start the backup. `forceOverMobile` lets the caller override the wifi-only
   * guard after prompting the user.
   */
  async function startBackupNow({ forceOverMobile = false } = {}) {
    if (backupInProgress.value) return
    backupInProgress.value = true
    backupStatus.value = 'uploading'
    backupError.value = null
    try {
      const hasPerms = await ensureMediaPermissions()
      if (!hasPerms) {
        throw new Error('Photo/video permission denied. Grant media access in system settings.')
      }
      if (!forceOverMobile) {
        const netCheck = await checkNetworkForBackup()
        if (!netCheck.ok) {
          if (netCheck.reason === 'mobile-data') {
            throw new Error('On mobile data — turn off Wi-Fi only or connect to Wi-Fi to start.')
          }
          throw new Error('No network connection.')
        }
      }
      const synced = await syncToNative()
      if (!synced) throw new Error(backupError.value || 'Backup configuration failed')
      await BackupBridge.runNow()
      // Optimistically mark as queued so the Stop button appears instantly —
      // the poll will correct it once the real WM state comes back.
      isWorkQueued.value = true
      backupStatus.value = 'done'
      setTimeout(refreshWorkStatus, 400)
      setTimeout(refreshStatus, 3000)
    } catch (e) {
      backupStatus.value = 'error'
      backupError.value = friendlyError(e)
    } finally {
      backupInProgress.value = false
    }
  }

  async function stopBackup() {
    try {
      await BackupBridge.stopNow()
      backupStatus.value = 'idle'
      isWorkRunning.value = false
      isWorkQueued.value = false
    } catch (e) {
      backupError.value = friendlyError(e)
    }
  }

  async function refreshWorkStatus() {
    try {
      const s = await BackupBridge.getWorkStatus()
      const wasActive = isWorkRunning.value || isWorkQueued.value
      isWorkRunning.value = !!s.running
      isWorkQueued.value = !!s.queued
      if (s.running || s.queued) {
        workPhase.value = s.phase || ''
        currentFile.value = s.currentFile || ''
        const prev = currentIndex.value
        currentIndex.value = Number(s.currentIndex) || 0
        totalFiles.value = Number(s.totalFiles) || 0

        // Update ETA samples only on real progress, and only during the
        // actual upload phase (pre-scan is too irregular to model).
        if (s.phase === 'uploading' && totalFiles.value > 0) {
          const nowMs = Date.now()
          if (uploadRunStartedAt === 0) uploadRunStartedAt = nowMs
          if (currentIndex.value !== prev) {
            rateSamples.value.push({ t: nowMs, idx: currentIndex.value })
            // Drop samples older than the window.
            const cutoff = nowMs - RATE_WINDOW_MS
            rateSamples.value = rateSamples.value.filter(s => s.t >= cutoff)
            const first = rateSamples.value[0]
            const last = rateSamples.value[rateSamples.value.length - 1]
            const dIdx = last.idx - first.idx
            const dMs = last.t - first.t
            if (dIdx > 0 && dMs > 2000) {
              const ratePerSec = dIdx / (dMs / 1000)
              const remaining = totalFiles.value - currentIndex.value
              etaSeconds.value = Math.max(0, Math.round(remaining / ratePerSec))
            }
          }
          // Fallback: if the rolling sampler hasn't produced a value yet,
          // compute from the wall-clock rate of the whole run so the UI
          // stops showing nothing on long videos / slow connections.
          if (etaSeconds.value === 0 && currentIndex.value > 0) {
            const elapsedSec = Math.max(1, (nowMs - uploadRunStartedAt) / 1000)
            const ratePerSec = currentIndex.value / elapsedSec
            if (ratePerSec > 0) {
              const remaining = totalFiles.value - currentIndex.value
              etaSeconds.value = Math.max(0, Math.round(remaining / ratePerSec))
            }
          }
        } else {
          // Phase flipped away from uploading — reset the run stamp so a
          // subsequent run starts with a fresh baseline.
          uploadRunStartedAt = 0
          etaSeconds.value = 0
        }
      } else {
        workPhase.value = ''
        currentFile.value = ''
        currentIndex.value = 0
        totalFiles.value = 0
        rateSamples.value = []
        etaSeconds.value = 0
        uploadRunStartedAt = 0
        // A backup just finished — refresh status + ID set so the UI flips
        // from "pending" to "up-to-date" immediately without the user pulling.
        // Also re-scan the device gallery so "Recently added" + pendingCount
        // pick up any photos taken during the run.
        if (wasActive) {
          refreshStatus()
          try {
            const { useGalleryStore } = await import('./gallery')
            const g = useGalleryStore()
            g.refresh()
          } catch { /* non-fatal */ }
          refreshStorageStats()
          refreshHistory()
          refreshCleanupQueue()
        }
      }
    } catch { /* non-fatal */ }
  }

  async function refreshVerifyStatus() {
    try {
      const s = await BackupBridge.getVerifyStatus()
      const wasRunning = verifyRunning.value || verifyQueued.value
      verifyRunning.value = !!s.running
      verifyQueued.value = !!s.queued
      verifyPhase.value = s.phase || ''
      verifyDetail.value = s.detail || ''
      verifyCurrent.value = Number(s.current) || 0
      verifyDropped.value = Number(s.dropped) || 0
      verifyTotal.value = Number(s.total) || 0
      if (s.phase === 'error') {
        verifyError.value = s.detail || 'Verify failed'
      } else if (s.phase === 'done') {
        verifyError.value = null
      }
      // Detect run completion: we were active last tick, now we aren't.
      if (wasRunning && !s.running && !s.queued) {
        verifyLastRunAt.value = Date.now()
        // Pull updated backed-up count so the status card reflects the drop.
        refreshStatus()
      }
    } catch { /* non-fatal */ }
  }

  function startWorkStatusPolling() {
    if (workPollTimer) return
    // Poll fast (every 2 s). When nothing is active we back off after a while
    // to avoid hammering the bridge while the user stares at the page.
    refreshWorkStatus()
    refreshVerifyStatus()
    workPollTimer = setInterval(() => {
      refreshWorkStatus()
      refreshVerifyStatus()
    }, 2000)
  }

  function stopWorkStatusPolling() {
    if (workPollTimer) { clearInterval(workPollTimer); workPollTimer = null }
  }

  async function verifyAgainstShelf() {
    if (verifyRunning.value || verifyQueued.value) return
    verifyError.value = null
    verifyDropped.value = 0
    try {
      await BackupBridge.verifyNow()
      verifyQueued.value = true
      setTimeout(refreshVerifyStatus, 300)
    } catch (e) {
      verifyError.value = friendlyError(e)
    }
  }

  async function stopVerify() {
    try { await BackupBridge.stopVerify() } catch { /* ignore */ }
    verifyRunning.value = false
    verifyQueued.value = false
  }

  /**
   * Recursively sizes the backup folder on the NAS and reads free space.
   * Silent on errors — UI shows stale/zero values and a "—" when unavailable.
   */
  async function refreshStorageStats() {
    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === backupShareId.value) || settingsStore.shares[0]
    if (!share) return
    const creds = await settingsStore.getShareCredentials(share.id)

    statsLoading.value = true
    try {
      const [sizeRes, info] = await Promise.all([
        SmbPlugin.getDirectorySize({
          host: share.host, share: share.shareName, path: backupPath.value,
          username: creds.username, password: creds.password
        }).catch(() => ({ size: 0, fileCount: 0 })),
        SmbPlugin.getShareInfo({
          host: share.host, share: share.shareName,
          username: creds.username, password: creds.password
        }).catch(() => ({ freeSpace: 0 }))
      ])
      backupSize.value = Number(sizeRes?.size || 0)
      backupFileCount.value = Number(sizeRes?.fileCount || 0)
      shelfFreeBytes.value = Number(info?.freeSpace || 0)
      statsUpdatedAt.value = Date.now()
    } finally {
      statsLoading.value = false
    }
  }

  async function refreshStatus() {
    try {
      const status = await BackupBridge.getStatus()
      lastBackupAt.value = status.lastBackupTimestamp || null
      lastBackupFileCount.value = status.lastBackupCount || 0
      backedUpCount.value = status.backedUpCount || 0
      enabled.value = status.enabled
    } catch (e) {
      backupError.value = friendlyError(e)
    }
    // Refresh the ID set whenever status changes (cheap enough — a single
    // native call returning a flat array).
    refreshBackedUpIds()
  }

  async function refreshHistory() {
    try {
      const res = await BackupBridge.getBackupHistory()
      // Newest-first for the UI; the native array is oldest-first (append order).
      history.value = Array.isArray(res?.records)
        ? [...res.records].reverse()
        : []
    } catch { /* non-fatal */ }
  }

  async function clearHistory() {
    try {
      await BackupBridge.clearBackupHistory()
      history.value = []
    } catch { /* non-fatal */ }
  }

  async function refreshCleanupQueue() {
    try {
      const res = await BackupBridge.getCleanupQueue()
      // Native returns oldest-first (insertion order) — UI flips to
      // newest-first so the most recent backups surface at the top.
      cleanupQueue.value = [...(res.entries || [])].reverse()
      cleanupEligible.value = res.eligible || 0
      cleanupTotalBytes.value = res.totalBytes || 0
      cleanupEligibleBytes.value = res.eligibleBytes || 0
    } catch { /* non-fatal */ }
  }

  async function removeCleanupEntries(ids) {
    if (!ids || ids.length === 0) return 0
    try {
      const res = await BackupBridge.removeCleanupEntries(ids)
      await refreshCleanupQueue()
      return res?.removed || 0
    } catch { return 0 }
  }

  async function clearCleanupQueue() {
    try {
      await BackupBridge.clearCleanupQueue()
      await refreshCleanupQueue()
    } catch { /* non-fatal */ }
  }

  async function executeCleanup({ ids = [], onlyEligible = true } = {}) {
    try {
      const res = await BackupBridge.executeCleanup({ ids, onlyEligible })
      // Queue refresh follows either way so UI reflects the post-delete state.
      await refreshCleanupQueue()
      return res
    } catch (e) {
      return { accepted: false, deleted: 0, failed: 0, error: friendlyError(e) }
    }
  }

  async function refreshBackedUpIds() {
    try {
      const { ids } = await BackupBridge.getBackedUpIds()
      backedUpIdSet.value = new Set(ids)
    } catch { /* non-fatal */ }
  }

  async function scanDeviceFolders() {
    try {
      const result = await BackupBridge.getMediaFolders()
      availableFolders.value = (result.folders || []).sort((a, b) => b.totalCount - a.totalCount)
    } catch (e) {
      backupError.value = friendlyError(e)
    }
  }

  function toggleFolder(folderPath) {
    const idx = selectedFolders.value.indexOf(folderPath)
    if (idx !== -1) selectedFolders.value.splice(idx, 1)
    else selectedFolders.value.push(folderPath)
    syncToNative()
  }

  function isFolderSelected(folderPath) {
    return selectedFolders.value.includes(folderPath)
  }

  async function resetBackup() {
    try {
      await BackupBridge.resetHistory()
      lastBackupAt.value = null
      backedUpCount.value = 0
      lastBackupFileCount.value = 0
    } catch (e) {
      backupError.value = friendlyError(e)
    }
  }

  // Debounce to a single call per burst so store hydration / rapid user
  // edits don't re-call configure on every micro-tick.  The native side is
  // now smart enough to only re-schedule when something actually changed,
  // but debouncing still avoids needless bridge chatter.
  let syncTimer = null
  watch([enabled, wifiOnly, homeNetworkOnly, chargerOnly,
    windowEnabled, windowStartHour, windowEndHour, backupOrganization,
    autoCleanup, cleanupGraceDays,
    scheduleInterval, includePhotos, includeVideos, backupPath, backupShareId,
    excludePatterns], () => {
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = setTimeout(() => { syncTimer = null; syncToNative() }, 300)
  }, { deep: true })

  return {
    enabled, backupShareId, backupPath, lastBackupAt, backedUpCount,
    lastBackupFileCount, backupInProgress, backupStatus, wifiOnly,
    homeNetworkOnly, chargerOnly, windowEnabled, windowStartHour, windowEndHour,
    backupOrganization, scheduleInterval, excludePatterns,
    includeVideos, includePhotos, backupError,
    selectedFolders, availableFolders,
    backupSize, backupFileCount, shelfFreeBytes, statsLoading, statsUpdatedAt,
    isWorkRunning, isWorkQueued, currentFile, currentIndex, totalFiles, workPhase,
    etaSeconds,
    verifyRunning, verifyQueued, verifyPhase, verifyDetail,
    verifyCurrent, verifyTotal, verifyDropped, verifyLastRunAt, verifyError,
    backedUpIdSet, refreshBackedUpIds,
    history, refreshHistory, clearHistory,
    autoCleanup, cleanupGraceDays,
    cleanupQueue, cleanupEligible, cleanupTotalBytes, cleanupEligibleBytes,
    refreshCleanupQueue, removeCleanupEntries, clearCleanupQueue, executeCleanup,
    toggleBackup, startBackupNow, stopBackup, refreshStatus, resetBackup,
    scanDeviceFolders, toggleFolder, isFolderSelected, syncToNative,
    setBackupPath, ensureMediaPermissions,
    getNetworkState, checkNetworkForBackup, refreshStorageStats,
    refreshWorkStatus, refreshVerifyStatus,
    startWorkStatusPolling, stopWorkStatusPolling,
    verifyAgainstShelf, stopVerify
  }
}, {
  persist: {
    pick: ['enabled', 'backupShareId', 'backupPath', 'wifiOnly',
      'homeNetworkOnly', 'chargerOnly', 'windowEnabled',
      'windowStartHour', 'windowEndHour', 'backupOrganization',
      'autoCleanup', 'cleanupGraceDays',
      'scheduleInterval', 'excludePatterns',
      'includeVideos', 'includePhotos', 'selectedFolders',
      'lastBackupAt', 'backedUpCount', 'lastBackupFileCount',
      'verifyLastRunAt']
  }
})
