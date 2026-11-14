import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from './settings'
import { getFileType, CONNECTION } from '../utils/helpers'
import { validateFilename, validateRelativePath, joinPath } from '../utils/validation'
import { withTimeout } from '../utils/timeout'
import { friendlyError } from '../utils/sanitize'

export const useSmbStore = defineStore('smb', () => {
  const settingsStore = useSettingsStore()

  const activeShareId = ref('')
  const currentPath = ref('')
  const pathHistory = ref([])
  const files = ref([])
  const loading = ref(false)
  const operating = ref(false)
  const error = ref(null)
  const connectionStatus = ref(CONNECTION.OFFLINE)
  const sortBy = ref('name')
  const sortAsc = ref(true)
  const viewMode = ref('list')
  const searchQuery = ref('')
  const lastConnectedAt = ref(null)
  const sortPreferences = ref({})
  const uploading = ref(false)

  // Multi-select state.  selectionMode flips on via long-press in file rows;
  // while active, every tap toggles selection instead of navigating/opening.
  const selectionMode = ref(false)
  const selectedNames = ref(new Set())

  function isSelected(name) { return selectedNames.value.has(name) }

  function toggleSelection(file) {
    const name = file?.name
    if (!name) return
    const next = new Set(selectedNames.value)
    if (next.has(name)) next.delete(name)
    else next.add(name)
    selectedNames.value = next
    // If the user deselects the last row, exit selection mode entirely.
    if (next.size === 0) selectionMode.value = false
  }

  function enterSelection(file) {
    selectionMode.value = true
    if (file?.name) {
      const next = new Set(selectedNames.value)
      next.add(file.name)
      selectedNames.value = next
    }
  }

  function clearSelection() {
    selectionMode.value = false
    selectedNames.value = new Set()
  }

  function selectedFilesList() {
    return files.value.filter(f => selectedNames.value.has(f.name))
  }

  function selectAll() {
    selectionMode.value = true
    selectedNames.value = new Set(files.value.map(f => f.name))
  }
  const uploadProgress = ref({ current: 0, total: 0, fileName: '' })

  // Race-protection: each loadDirectory call bumps this and only the latest wins.
  let loadRequestId = 0

  const activeShare = computed(() => {
    const byId = settingsStore.shares.find(s => s.id === activeShareId.value)
    if (byId) return byId
    // Prefer the Media shelf by name when the user hasn't picked one — list
    // order in settingsStore.shares can shift after reorders / re-seeding,
    // so "shares[0]" isn't a stable "default."
    const preferred = settingsStore.shares.find(s => s.shareName === 'Media')
    return preferred || settingsStore.shares[0] || null
  })

  const breadcrumbs = computed(() => {
    const parts = currentPath.value.split('/').filter(Boolean)
    const crumbs = [{ label: activeShare.value?.shareName || 'Root', path: '' }]
    let accumulated = ''
    for (const part of parts) {
      accumulated += (accumulated ? '/' : '') + part
      crumbs.push({ label: part, path: accumulated })
    }
    return crumbs
  })

  const canGoBack = computed(() => pathHistory.value.length > 0)
  const isConnected = computed(() => connectionStatus.value === CONNECTION.ONLINE)

  const filteredFiles = computed(() => {
    let result = [...files.value]
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      result = result.filter(f => f.name.toLowerCase().includes(q))
    }
    result.sort((a, b) => {
      if (a.isDirectory && !b.isDirectory) return -1
      if (!a.isDirectory && b.isDirectory) return 1
      let cmp = 0
      switch (sortBy.value) {
        case 'name': cmp = a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }); break
        case 'size': cmp = (a.size || 0) - (b.size || 0); break
        case 'date': cmp = (a.modified || 0) - (b.modified || 0); break
        case 'type': cmp = (a.type || '').localeCompare(b.type || ''); break
      }
      return sortAsc.value ? cmp : -cmp
    })
    return result
  })

  const folderCount = computed(() => files.value.filter(f => f.isDirectory).length)
  const fileCount = computed(() => files.value.filter(f => !f.isDirectory).length)

  async function resolveShareAndCreds() {
    const share = activeShare.value
    if (!share) return { share: null, creds: null }
    const creds = await settingsStore.getShareCredentials(share.id)
    return { share, creds }
  }

  function timeoutMs() {
    return settingsStore.connectionTimeout || 10000
  }

  async function loadDirectory(path = '', pushHistory = false) {
    console.info('[smb] loadDirectory called', { path, pushHistory })
    const pathCheck = validateRelativePath(path)
    if (!pathCheck.ok) {
      console.error('[smb] path validation failed', pathCheck.error)
      error.value = pathCheck.error
      return
    }
    const safePath = pathCheck.value

    const { share, creds } = await resolveShareAndCreds()
    if (!share) {
      console.error('[smb] no share configured')
      error.value = 'No share configured'
      return
    }
    console.info('[smb] resolved share', {
      host: share.host, shareName: share.shareName,
      hasUser: !!creds.username, hasPass: !!creds.password
    })

    if (pushHistory && currentPath.value !== safePath) {
      pathHistory.value.push(currentPath.value)
    }

    const reqId = ++loadRequestId
    loading.value = true
    error.value = null
    currentPath.value = safePath
    // Clear the search filter when the folder changes — users were seeing
    // "empty folder" because a filter from a previous screen was silently
    // filtering out the new folder's contents.
    searchQuery.value = ''
    // Selection is per-folder; carrying it across a nav would act on the
    // wrong files.
    selectionMode.value = false
    selectedNames.value = new Set()

    try {
      console.info('[smb] calling native listDirectory', { path: safePath, timeout: timeoutMs() })
      const result = await withTimeout(
        SmbPlugin.listDirectory({
          host: share.host,
          share: share.shareName,
          path: safePath,
          username: creds.username,
          password: creds.password
        }),
        timeoutMs(),
        'list directory'
      )
      console.info('[smb] native listDirectory returned', {
        isArray: Array.isArray(result),
        count: Array.isArray(result) ? result.length : 'n/a',
        sample: Array.isArray(result) ? result.slice(0, 3) : result
      })
      if (reqId !== loadRequestId) {
        console.warn('[smb] request superseded, dropping result', { reqId, current: loadRequestId })
        return
      }
      files.value = (result || []).map(f => ({ ...f, type: f.type || getFileType(f.name, f.isDirectory) }))
      console.info('[smb] files assigned to store', { count: files.value.length })
      connectionStatus.value = CONNECTION.ONLINE
      lastConnectedAt.value = Date.now()

      const prefKey = `${share.id}:${safePath}`
      const savedPref = sortPreferences.value[prefKey]
      if (savedPref) {
        sortBy.value = savedPref.sortBy
        sortAsc.value = savedPref.sortAsc
      }
    } catch (e) {
      console.error('[smb] loadDirectory error', e)
      if (reqId !== loadRequestId) return
      error.value = friendlyError(e)
      // Keep the previous listing visible on transient network/timeout errors
      // so the user isn't staring at a blank page during a flaky moment
      // (e.g. the native backup worker hogging the SMB session).  A real
      // server-side error (auth, not found) is still treated as "listing
      // invalid" and clears the list.
      if (isNetworkError(e)) {
        connectionStatus.value = CONNECTION.OFFLINE
        // files.value intentionally preserved
      } else {
        files.value = []
      }
    } finally {
      if (reqId === loadRequestId) loading.value = false
    }
  }

  function isNetworkError(e) {
    const raw = (e?.message || String(e || '')).toLowerCase()
    return /timed? ?out|timeout|etimedout|enotfound|unknown host|dns|econnrefused|connection refused|no route|unreachable|network/i.test(raw)
  }

  async function switchShare(shareId) {
    activeShareId.value = shareId
    currentPath.value = ''
    pathHistory.value = []
    await loadDirectory('')
  }

  async function navigateInto(folderName) {
    const name = validateFilename(folderName)
    if (!name.ok) { error.value = name.error; return }
    await loadDirectory(joinPath(currentPath.value, name.value), true)
  }

  async function goBack() {
    if (pathHistory.value.length > 0) {
      const prevPath = pathHistory.value.pop()
      await loadDirectory(prevPath, false)
      return true
    }
    return false
  }

  async function navigateTo(path) {
    await loadDirectory(path, true)
  }

  async function deleteItem(fileName) {
    const name = validateFilename(fileName)
    if (!name.ok) return { success: false, error: name.error }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    const fullPath = joinPath(currentPath.value, name.value)
    operating.value = true
    try {
      await withTimeout(
        SmbPlugin.deleteFile({ host: share.host, share: share.shareName, path: fullPath, username: creds.username, password: creds.password }),
        timeoutMs(),
        'delete'
      )
      await loadDirectory(currentPath.value)
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      operating.value = false
    }
  }

  async function createFolder(name) {
    const checked = validateFilename(name)
    if (!checked.ok) return { success: false, error: checked.error }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    const fullPath = joinPath(currentPath.value, checked.value)
    operating.value = true
    try {
      await withTimeout(
        SmbPlugin.createDirectory({ host: share.host, share: share.shareName, path: fullPath, username: creds.username, password: creds.password }),
        timeoutMs(),
        'create folder'
      )
      await loadDirectory(currentPath.value)
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      operating.value = false
    }
  }

  async function renameItem(oldName, newName) {
    const oldCheck = validateFilename(oldName)
    const newCheck = validateFilename(newName)
    if (!oldCheck.ok) return { success: false, error: oldCheck.error }
    if (!newCheck.ok) return { success: false, error: newCheck.error }
    if (oldCheck.value === newCheck.value) return { success: true }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    operating.value = true
    try {
      await withTimeout(
        SmbPlugin.rename({
          host: share.host,
          share: share.shareName,
          oldPath: joinPath(currentPath.value, oldCheck.value),
          newPath: joinPath(currentPath.value, newCheck.value),
          username: creds.username,
          password: creds.password
        }),
        timeoutMs(),
        'rename'
      )
      await loadDirectory(currentPath.value)
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      operating.value = false
    }
  }

  async function uploadFiles(files) {
    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    uploading.value = true
    uploadProgress.value = { current: 0, total: files.length, fileName: '' }

    try {
      for (let i = 0; i < files.length; i++) {
        const file = files[i]
        const nameCheck = validateFilename(file.name)
        if (!nameCheck.ok) throw new Error(`Invalid filename "${file.name}": ${nameCheck.error}`)

        uploadProgress.value = { current: i + 1, total: files.length, fileName: nameCheck.value }
        const destPath = joinPath(currentPath.value, nameCheck.value)
        await withTimeout(
          SmbPlugin.uploadFile({ host: share.host, share: share.shareName, path: destPath, localUri: file.localUri, username: creds.username, password: creds.password }),
          Math.max(timeoutMs(), 60000),
          'upload'
        )
      }
      await loadDirectory(currentPath.value)
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      uploading.value = false
      uploadProgress.value = { current: 0, total: 0, fileName: '' }
    }
  }

  async function copyItem(fileName, destPath) {
    const nameCheck = validateFilename(fileName)
    if (!nameCheck.ok) return { success: false, error: nameCheck.error }
    const destCheck = validateRelativePath(destPath || '')
    if (!destCheck.ok) return { success: false, error: `Destination: ${destCheck.error}` }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    const sourcePath = joinPath(currentPath.value, nameCheck.value)
    const finalDest = joinPath(destCheck.value, nameCheck.value)
    operating.value = true
    try {
      await withTimeout(
        SmbPlugin.copyFile({ host: share.host, share: share.shareName, sourcePath, destPath: finalDest, username: creds.username, password: creds.password }),
        Math.max(timeoutMs(), 60000),
        'copy'
      )
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      operating.value = false
    }
  }

  async function moveItem(fileName, destPath) {
    const nameCheck = validateFilename(fileName)
    if (!nameCheck.ok) return { success: false, error: nameCheck.error }
    const destCheck = validateRelativePath(destPath || '')
    if (!destCheck.ok) return { success: false, error: `Destination: ${destCheck.error}` }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    const sourcePath = joinPath(currentPath.value, nameCheck.value)
    const finalDest = joinPath(destCheck.value, nameCheck.value)
    operating.value = true
    try {
      await withTimeout(
        SmbPlugin.moveFile({ host: share.host, share: share.shareName, sourcePath, destPath: finalDest, username: creds.username, password: creds.password }),
        timeoutMs(),
        'move'
      )
      await loadDirectory(currentPath.value)
      return { success: true }
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    } finally {
      operating.value = false
    }
  }

  async function getItemSize(fileName) {
    const nameCheck = validateFilename(fileName)
    if (!nameCheck.ok) return { success: false, error: nameCheck.error }

    const { share, creds } = await resolveShareAndCreds()
    if (!share) return { success: false, error: 'No share configured' }

    const fullPath = joinPath(currentPath.value, nameCheck.value)
    try {
      return await withTimeout(
        SmbPlugin.getDirectorySize({ host: share.host, share: share.shareName, path: fullPath, username: creds.username, password: creds.password }),
        Math.max(timeoutMs(), 30000),
        'get size'
      )
    } catch (e) {
      return { success: false, error: friendlyError(e) }
    }
  }

  async function checkConnection() {
    const share = activeShare.value
    if (!share) return { reachable: false, responseTime: -1 }
    connectionStatus.value = CONNECTION.CONNECTING
    const result = await withTimeout(SmbPlugin.ping({ host: share.host }), timeoutMs(), 'ping')
      .catch(() => ({ reachable: false, responseTime: -1 }))
    connectionStatus.value = result.reachable ? CONNECTION.ONLINE : CONNECTION.OFFLINE
    return result
  }

  function toggleSort(field) {
    if (sortBy.value === field) {
      sortAsc.value = !sortAsc.value
    } else {
      sortBy.value = field
      sortAsc.value = true
    }
    const share = activeShare.value
    if (!share) return
    const prefKey = `${share.id}:${currentPath.value}`
    sortPreferences.value[prefKey] = { sortBy: sortBy.value, sortAsc: sortAsc.value }
  }

  function resetNavigation() {
    currentPath.value = ''
    pathHistory.value = []
    files.value = []
    error.value = null
  }

  return {
    activeShareId,
    activeShare,
    currentPath,
    pathHistory,
    canGoBack,
    isConnected,
    files,
    filteredFiles,
    loading,
    operating,
    error,
    connectionStatus,
    sortBy,
    sortAsc,
    viewMode,
    searchQuery,
    breadcrumbs,
    folderCount,
    fileCount,
    lastConnectedAt,
    sortPreferences,
    uploading,
    uploadProgress,
    loadDirectory,
    switchShare,
    navigateInto,
    goBack,
    navigateTo,
    deleteItem,
    createFolder,
    renameItem,
    uploadFiles,
    copyItem,
    moveItem,
    getItemSize,
    checkConnection,
    toggleSort,
    resetNavigation,
    resolveShareAndCreds,
    selectionMode, selectedNames,
    isSelected, toggleSelection, enterSelection, clearSelection,
    selectedFilesList, selectAll
  }
}, {
  persist: {
    paths: ['sortPreferences', 'activeShareId']
  }
})
