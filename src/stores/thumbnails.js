import { defineStore } from 'pinia'
import { ref, markRaw } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSmbStore } from './smb'
import { useSettingsStore } from './settings'

/**
 * Thumbnail resolution & caching.
 *
 * Every file row/grid cell asks for its thumb via `get(file)`.  The store
 * returns a URI synchronously if we already resolved this file this session
 * (hit in the in-memory map), otherwise `null` — the component shows a type
 * icon and awaits the store's promise, which is de-duplicated per key.
 *
 * In-memory map caps at `MEM_CAP` entries; oldest entries are evicted.  The
 * native side owns the on-disk cache (LRU, ~200 MB cap), so this just avoids
 * re-calling the bridge for things we've already resolved.
 */
const MEM_CAP = 1024
const MAX_CONCURRENT = 3

// Which extensions are worth even attempting.  Anything else returns null
// immediately without hitting the bridge.
const SUPPORTED_EXT = new Set([
  'jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif'
])

function extOf(name) {
  if (!name) return ''
  const dot = name.lastIndexOf('.')
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
}

function isSupportedImage(file) {
  if (!file || file.isDirectory) return false
  return SUPPORTED_EXT.has(extOf(file.name))
}

function keyFor(shareId, file) {
  // mtime in the key so a rename/overwrite invalidates cleanly.
  return `${shareId}\u0001${file.path || file.name}\u0001${file.modified || 0}`
}

export const useThumbnailStore = defineStore('thumbnails', () => {
  // key -> uri  (empty string = tried, no thumb possible)
  const cache = ref(new Map())
  // key -> Promise<string>  (in-flight)
  const inflight = ref(markRaw(new Map()))
  // FIFO queue so we can bound concurrency without blocking callers.
  const queue = []
  let active = 0

  /**
   * Returns the cached URI if present (or '' if previously tried + failed),
   * otherwise undefined — meaning not yet resolved.
   */
  function peek(shareId, file) {
    if (!isSupportedImage(file)) return ''
    return cache.value.get(keyFor(shareId, file))
  }

  /**
   * Resolves to a file:// URI or '' if no thumb available.  Safe to call
   * concurrently for the same key; only one fetch actually runs.
   */
  async function get(file, opts = {}) {
    if (!isSupportedImage(file)) return ''
    const smb = useSmbStore()
    const settings = useSettingsStore()
    const shareId = opts.shareId || smb.activeShareId
    const share = settings.shares.find(s => s.id === shareId) || smb.activeShare
    if (!share) return ''
    const path = opts.path || (smb.currentPath ? `${smb.currentPath}/${file.name}` : file.name)

    const key = `${shareId}\u0001${path}\u0001${file.modified || 0}`
    if (cache.value.has(key)) return cache.value.get(key)
    if (inflight.value.has(key)) return inflight.value.get(key)

    const promise = new Promise(resolve => {
      queue.push({ key, path, share, file, resolve })
      pump()
    })
    inflight.value.set(key, promise)
    return promise
  }

  function pump() {
    while (active < MAX_CONCURRENT && queue.length > 0) {
      const job = queue.shift()
      active++
      run(job).finally(() => {
        active--
        pump()
      })
    }
  }

  async function run(job) {
    const settings = useSettingsStore()
    let uri = ''
    try {
      const creds = await settings.getShareCredentials(job.share.id)
      const res = await SmbPlugin.getThumbnail({
        host: job.share.host,
        share: job.share.shareName,
        path: job.path,
        username: creds.username || '',
        password: creds.password || '',
        maxSize: 192,
        mtime: job.file.modified || 0
      })
      uri = typeof res?.uri === 'string' ? res.uri : ''
    } catch {
      uri = ''  // treat every error as "no thumb"; caller shows type icon
    }
    put(job.key, uri)
    inflight.value.delete(job.key)
    job.resolve(uri)
  }

  function put(key, uri) {
    if (cache.value.size >= MEM_CAP) {
      // Evict the oldest insertion — Map preserves insertion order.
      const first = cache.value.keys().next().value
      if (first !== undefined) cache.value.delete(first)
    }
    cache.value.set(key, uri)
  }

  /** Wipe the in-memory cache.  Useful after a logout / share removal. */
  function clear() {
    cache.value.clear()
    inflight.value.clear()
    queue.length = 0
    active = 0
  }

  return { peek, get, clear }
})
