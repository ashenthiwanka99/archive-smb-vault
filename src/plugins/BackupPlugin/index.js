import { registerPlugin, Capacitor } from '@capacitor/core'

const BackupNative = registerPlugin('BackupPlugin')
const MediaScannerNative = registerPlugin('MediaScanner')

const isNative = Capacitor.isNativePlatform()

export const BackupBridge = {
  async configure(config) {
    if (!isNative) return
    return await BackupNative.configure(config)
  },

  async runNow() {
    if (!isNative) throw new Error('Backup requires native Android')
    return await BackupNative.runNow()
  },

  async stopNow() {
    if (!isNative) return
    return await BackupNative.stopNow()
  },

  async getWorkStatus() {
    const stub = { running: false, queued: false, active: false, phase: '', currentFile: '', currentIndex: 0, totalFiles: 0 }
    if (!isNative) return stub
    try { return await BackupNative.getWorkStatus() }
    catch { return stub }
  },

  async getNetworkStatus() {
    if (!isNative) return { connected: true, wifi: true, metered: false }
    try { return await BackupNative.getNetworkStatus() }
    catch { return { connected: false, wifi: false, metered: true } }
  },

  async checkMediaPermissions() {
    if (!isNative) return { granted: true, images: true, videos: true }
    try { return await BackupNative.checkMediaPermissions() }
    catch { return { granted: false, images: false, videos: false } }
  },

  async requestMediaPermissions() {
    if (!isNative) return { granted: true, images: true, videos: true }
    try { return await BackupNative.requestMediaPermissions() }
    catch { return { granted: false, images: false, videos: false } }
  },

  async getStatus() {
    if (!isNative) return { enabled: false, lastBackupTimestamp: 0, lastBackupCount: 0, backedUpCount: 0 }
    return await BackupNative.getStatus()
  },

  async resetHistory() {
    if (!isNative) return
    return await BackupNative.resetHistory()
  },

  async getBackupHistory() {
    if (!isNative) return { records: [] }
    try {
      const res = await BackupNative.getBackupHistory()
      return { records: Array.isArray(res.records) ? res.records : [] }
    } catch { return { records: [] } }
  },

  async clearBackupHistory() {
    if (!isNative) return
    try { await BackupNative.clearBackupHistory() } catch { /* non-fatal */ }
  },

  async getCleanupQueue() {
    if (!isNative) return { entries: [], total: 0, eligible: 0, totalBytes: 0, eligibleBytes: 0 }
    try {
      const res = await BackupNative.getCleanupQueue()
      return {
        entries: Array.isArray(res.entries) ? res.entries : [],
        total: Number(res.total) || 0,
        eligible: Number(res.eligible) || 0,
        totalBytes: Number(res.totalBytes) || 0,
        eligibleBytes: Number(res.eligibleBytes) || 0
      }
    } catch {
      return { entries: [], total: 0, eligible: 0, totalBytes: 0, eligibleBytes: 0 }
    }
  },

  async removeCleanupEntries(ids) {
    if (!isNative) return { removed: 0 }
    try { return await BackupNative.removeCleanupEntries({ ids }) }
    catch { return { removed: 0 } }
  },

  async clearCleanupQueue() {
    if (!isNative) return
    try { await BackupNative.clearCleanupQueue() } catch { /* non-fatal */ }
  },

  /**
   * Fire Android's MediaStore delete prompt.  Returns {accepted, deleted,
   * failed} — on R+ the user must approve the system dialog, which resolves
   * this call; on pre-R the direct delete runs synchronously.  Passing ids
   * restricts the batch to a subset; omitting them deletes everything
   * currently eligible.
   */
  async executeCleanup({ ids = [], onlyEligible = true } = {}) {
    if (!isNative) return { accepted: false, deleted: 0, failed: 0 }
    return await BackupNative.executeCleanup({ ids, onlyEligible })
  },

  async getBackedUpIds() {
    if (!isNative) return { ids: [], count: 0 }
    try {
      const res = await BackupNative.getBackedUpIds()
      return { ids: Array.isArray(res.ids) ? res.ids : [], count: Number(res.count) || 0 }
    } catch {
      return { ids: [], count: 0 }
    }
  },

  async enqueueUpload(items) {
    if (!isNative) throw new Error('Upload requires native Android')
    return await BackupNative.enqueueUpload({ items })
  },

  async getUploadStatus() {
    const stub = { queue: [], running: false, queued: false, phase: '', currentId: '', currentName: '', currentIndex: 0, totalFiles: 0 }
    if (!isNative) return stub
    try { return await BackupNative.getUploadStatus() } catch { return stub }
  },

  async stopUpload() {
    if (!isNative) return
    try { await BackupNative.stopUpload() } catch { /* non-fatal */ }
  },

  async removeUpload(id) {
    if (!isNative) return
    try { await BackupNative.removeUpload({ id }) } catch { /* non-fatal */ }
  },

  async retryUpload(id) {
    if (!isNative) return
    try { await BackupNative.retryUpload({ id }) } catch { /* non-fatal */ }
  },

  async verifyNow() {
    if (!isNative) throw new Error('Verify requires native Android')
    return await BackupNative.verifyNow()
  },

  async stopVerify() {
    if (!isNative) return
    return await BackupNative.stopVerify()
  },

  async getVerifyStatus() {
    const stub = { running: false, queued: false, active: false, phase: '', detail: '', current: 0, dropped: 0, total: 0 }
    if (!isNative) return stub
    try { return await BackupNative.getVerifyStatus() }
    catch { return stub }
  },

  async getMediaFolders() {
    if (!isNative) return { folders: [] }
    return await MediaScannerNative.getMediaFolders()
  },

  async getPhotos(since = 0, folders = []) {
    if (!isNative) return { media: [], count: 0 }
    return await MediaScannerNative.getPhotos({ since, folders })
  },

  async getVideos(since = 0, folders = []) {
    if (!isNative) return { media: [], count: 0 }
    return await MediaScannerNative.getVideos({ since, folders })
  }
}
