import { registerPlugin, Capacitor } from '@capacitor/core'

const SmbNative = registerPlugin('SmbPlugin')

const isNativePlatform = Capacitor.isNativePlatform()

/**
 * SMB Plugin - Bridge to native JCIFS-NG operations.
 * Only works on native Android. Browser/dev mode returns proper errors.
 */
export const SmbPlugin = {
  /**
   * List directory contents on SMB share
   */
  async listDirectory({ host, share, path = '', username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android. Connect a device or use an emulator.')
    }
    const result = await SmbNative.listDirectory({ host, share, path, username, password })
    return result.files || []
  },

  /**
   * Read file from SMB share (returns base64)
   */
  async readFile({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.readFile({ host, share, path, username, password })
  },

  /**
   * Download file from SMB share to local storage
   */
  async downloadFile({ host, share, path, localPath, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.downloadFile({ host, share, path, localPath, username, password })
  },

  /**
   * Upload local file to SMB share
   */
  async uploadFile({ host, share, path, localUri, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.uploadFile({ host, share, path, localUri, username, password })
  },

  /**
   * Delete file or directory
   */
  async deleteFile({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.deleteFile({ host, share, path, username, password })
  },

  /**
   * Create directory on SMB share
   */
  async createDirectory({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.createDirectory({ host, share, path, username, password })
  },

  /**
   * Rename file or directory
   */
  async rename({ host, share, oldPath, newPath, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.rename({ host, share, oldPath, newPath, username, password })
  },

  /**
   * Copy file on SMB share
   */
  async copyFile({ host, share, sourcePath, destPath, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.copyFile({ host, share, sourcePath, destPath, username, password })
  },

  /**
   * Move file on SMB share
   */
  async moveFile({ host, share, sourcePath, destPath, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.moveFile({ host, share, sourcePath, destPath, username, password })
  },

  /**
   * Get total size of a directory (recursive)
   */
  async getDirectorySize({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.getDirectorySize({ host, share, path, username, password })
  },

  /**
   * Return the direct children of `path` with recursive-total size per entry.
   * Used by the storage treemap on the gallery page.  Items at the target
   * level are aggregated under a bucket named `(files)`.
   */
  async getFolderBreakdown({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) throw new Error('SMB requires native Android.')
    const res = await SmbNative.getFolderBreakdown({ host, share, path, username, password })
    return { items: Array.isArray(res?.items) ? res.items : [] }
  },

  /**
   * Get a thumbnail for an image file.  Returns `{ uri, hit }` where `uri`
   * is a `file://` to a cached downscaled JPEG (empty string when the file
   * can't be thumbnailed — e.g. HEIC, or over the 2 MB fallback cap).
   * `hit` is true when the result came straight from disk cache.
   * `mtime` is optional but recommended — passing it makes the cache key
   * invalidate cleanly when the remote file is overwritten.
   */
  async getThumbnail({ host, share, path, username = '', password = '', maxSize = 192, mtime = 0 }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    const res = await SmbNative.getThumbnail({ host, share, path, username, password, maxSize, mtime })
    // The native side returns `file:///data/user/0/...cache/thumbs/X.jpg`.
    // Capacitor's WebView (androidScheme: https) blocks file:// URIs — wrap
    // through Capacitor.convertFileSrc so it becomes a proxy URL the WebView
    // actually loads (https://localhost/_capacitor_file_/...).
    if (res?.uri && typeof res.uri === 'string' && res.uri.startsWith('file://')) {
      res.uri = Capacitor.convertFileSrc(res.uri)
    }
    return res
  },

  /**
   * Get a streamable local URL for a media file
   */
  async getStreamUrl({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.getStreamUrl({ host, share, path, username, password })
  },

  /**
   * Drop a previously-registered stream so the native-side file handle can
   * be GC'd.  Best-effort — safe to call with an empty id.
   */
  async releaseStream({ streamId }) {
    if (!isNativePlatform || !streamId) return
    try { await SmbNative.releaseStream({ streamId }) } catch { /* non-fatal */ }
  },

  /**
   * Start a recursive search.  Returns { taskId }.  Results stream in via
   * the 'searchResults' event; 'searchComplete' fires at the end.
   */
  async recursiveSearch({ host, share, path = '', username = '', password = '', query, types = '', taskId = '' }) {
    if (!isNativePlatform) throw new Error('SMB requires native Android.')
    return await SmbNative.recursiveSearch({ host, share, path, username, password, query, types, taskId })
  },

  async cancelSearch({ taskId }) {
    if (!isNativePlatform || !taskId) return
    try { await SmbNative.cancelSearch({ taskId }) } catch { /* non-fatal */ }
  },

  /** Subscribe to streaming search results.  Returns an unsubscribe fn. */
  async onSearchResults(handler) {
    if (!isNativePlatform) return () => {}
    const sub = await SmbNative.addListener('searchResults', handler)
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  async onSearchComplete(handler) {
    if (!isNativePlatform) return () => {}
    const sub = await SmbNative.addListener('searchComplete', handler)
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  /**
   * Start a duplicate-file scan.  Returns { taskId }.  Groups stream in via
   * 'dupScanGroup'; 'dupScanProgress' ticks the walker + hasher; 'dupScanDone'
   * fires at the end (with a `canceled` flag on early termination).
   */
  async findDuplicates({ host, share, path = '', username = '', password = '', taskId = '', minBytes = 1, maxBytes = 0 }) {
    if (!isNativePlatform) throw new Error('SMB requires native Android.')
    const args = { host, share, path, username, password, taskId, minBytes }
    if (maxBytes > 0) args.maxBytes = maxBytes
    return await SmbNative.findDuplicates(args)
  },

  async cancelDuplicateScan({ taskId }) {
    if (!isNativePlatform || !taskId) return
    try { await SmbNative.cancelDuplicateScan({ taskId }) } catch { /* non-fatal */ }
  },

  async onDupScanProgress(handler) {
    if (!isNativePlatform) return () => {}
    const sub = await SmbNative.addListener('dupScanProgress', handler)
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  async onDupScanGroup(handler) {
    if (!isNativePlatform) return () => {}
    const sub = await SmbNative.addListener('dupScanGroup', handler)
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  async onDupScanDone(handler) {
    if (!isNativePlatform) return () => {}
    const sub = await SmbNative.addListener('dupScanDone', handler)
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  /**
   * Share file via Android share intent
   */
  async shareFile({ host, share, path, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.shareFile({ host, share, path, username, password })
  },

  /**
   * Get share info (total/used/free space)
   */
  async getShareInfo({ host, share, username = '', password = '' }) {
    if (!isNativePlatform) {
      throw new Error('SMB requires native Android.')
    }
    return await SmbNative.getShareInfo({ host, share, username, password })
  },

  /**
   * Ping SMB host
   */
  async ping({ host }) {
    if (!isNativePlatform) {
      return { reachable: false, responseTime: -1 }
    }
    try {
      return await SmbNative.ping({ host })
    } catch {
      return { reachable: false, responseTime: -1 }
    }
  }
}
