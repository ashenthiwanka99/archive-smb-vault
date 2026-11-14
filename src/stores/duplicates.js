import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from './settings'
import { friendlyError } from '../utils/sanitize'

/**
 * Streamed duplicate-file scan state.  The native walker emits groups as it
 * finds them; this store just collects them and exposes cancellation.
 *
 * A group looks like: { id, hash, size, files: [{ name, path, size, modified, selected }] }
 * We seed `selected` to false — user ticks checkboxes to mark copies for
 * deletion (we default-check all but the newest inside a group later when
 * we wire the UI).
 */
export const useDuplicatesStore = defineStore('duplicates', () => {
  const groups = ref([])
  const running = ref(false)
  const phase = ref('')        // 'walking' | 'hashing' | ''
  const walked = ref(0)        // files scanned (phase dependent)
  const groupsFound = ref(0)
  const bytesWasted = ref(0)   // sum of (group.size * (group.files.length - 1))
  const error = ref(null)
  const activeTaskId = ref('')
  const lastRunShareId = ref('')
  const lastRunPath = ref('')

  let subs = []

  function clear() {
    groups.value = []
    phase.value = ''
    walked.value = 0
    groupsFound.value = 0
    bytesWasted.value = 0
    error.value = null
  }

  function tearDownSubs() {
    for (const unsub of subs) {
      try { unsub() } catch { /* non-fatal */ }
    }
    subs = []
  }

  async function ensureSubs() {
    if (subs.length > 0) return
    subs.push(await SmbPlugin.onDupScanProgress((evt) => {
      if (evt.taskId !== activeTaskId.value) return
      phase.value = evt.phase || ''
      walked.value = Number(evt.walked) || 0
      groupsFound.value = Number(evt.groupsFound) || 0
      bytesWasted.value = Number(evt.bytesWasted) || 0
    }))
    subs.push(await SmbPlugin.onDupScanGroup((evt) => {
      if (evt.taskId !== activeTaskId.value) return
      const files = Array.isArray(evt.files) ? evt.files : []
      // Default: keep the most recently-modified, check the rest.  That's
      // the "safe" delete default — a destructive action the user still
      // has to confirm.
      const sorted = [...files].sort((a, b) => (b.modified || 0) - (a.modified || 0))
      const decorated = sorted.map((f, i) => ({
        name: f.name,
        path: f.path,
        size: Number(f.size) || 0,
        modified: Number(f.modified) || 0,
        selected: i > 0
      }))
      groups.value.push({
        id: evt.hash,
        hash: evt.hash,
        size: Number(evt.size) || 0,
        files: decorated,
        deleted: false
      })
    }))
    subs.push(await SmbPlugin.onDupScanDone((evt) => {
      if (evt.taskId !== activeTaskId.value) return
      running.value = false
      phase.value = evt.canceled ? 'canceled' : 'done'
      if (evt.error) error.value = evt.error
    }))
  }

  async function startScan({ shareId, path = '' }) {
    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === shareId) || settingsStore.shares[0]
    if (!share) { error.value = 'No shelf configured'; return }
    const creds = await settingsStore.getShareCredentials(share.id)
    await ensureSubs()
    clear()
    running.value = true
    phase.value = 'walking'
    lastRunShareId.value = share.id
    lastRunPath.value = path || ''
    // Pre-generate the task id so listeners are in place before the first event.
    const taskId = 'd' + Math.random().toString(36).slice(2) + Date.now().toString(36)
    activeTaskId.value = taskId
    try {
      await SmbPlugin.findDuplicates({
        host: share.host,
        share: share.shareName,
        path,
        username: creds.username || '',
        password: creds.password || '',
        taskId
      })
    } catch (e) {
      error.value = friendlyError(e)
      running.value = false
    }
  }

  async function cancel() {
    if (!activeTaskId.value) return
    try { await SmbPlugin.cancelDuplicateScan({ taskId: activeTaskId.value }) } catch { /* non-fatal */ }
  }

  /**
   * Delete every file with `selected=true` in every group.  Skips the last
   * surviving copy in a group as a safety rail — if the user somehow ticks
   * every file in a group we leave the newest alone rather than wiping the
   * content entirely.
   */
  async function deleteSelected() {
    const settingsStore = useSettingsStore()
    const share = settingsStore.shares.find(s => s.id === lastRunShareId.value)
    if (!share) return { deleted: 0, failed: 0, errors: [] }
    const creds = await settingsStore.getShareCredentials(share.id)
    let deleted = 0, failed = 0
    const errors = []
    for (const g of groups.value) {
      if (g.deleted) continue
      const keeping = g.files.filter(f => !f.selected)
      // Safety: if the user marked ALL files, spare the newest (first in sorted order).
      const targets = keeping.length === 0 ? g.files.slice(1) : g.files.filter(f => f.selected)
      for (const f of targets) {
        try {
          await SmbPlugin.deleteFile({
            host: share.host,
            share: share.shareName,
            path: f.path,
            username: creds.username || '',
            password: creds.password || ''
          })
          f.deleted = true
          deleted++
        } catch (e) {
          failed++
          errors.push(`${f.name}: ${friendlyError(e)}`)
        }
      }
      // A group whose last non-selected file survived is still valid; mark
      // groups where nothing remains as fully-handled so the UI can hide them.
      g.files = g.files.filter(f => !f.deleted)
      if (g.files.length < 2) g.deleted = true
      // Update waste accounting after the deletions.
      bytesWasted.value = groups.value
        .filter(x => !x.deleted)
        .reduce((s, x) => s + x.size * (x.files.length - 1), 0)
    }
    return { deleted, failed, errors }
  }

  const selectedCount = computed(() =>
    groups.value.reduce((s, g) => s + g.files.filter(f => f.selected).length, 0)
  )

  const selectedBytes = computed(() =>
    groups.value.reduce((s, g) =>
      s + g.files.filter(f => f.selected).reduce((a, f) => a + (f.size || 0), 0)
    , 0)
  )

  const visibleGroups = computed(() => groups.value.filter(g => !g.deleted))

  return {
    groups, visibleGroups, running, phase, walked, groupsFound, bytesWasted,
    error, activeTaskId, lastRunShareId, lastRunPath,
    selectedCount, selectedBytes,
    clear, startScan, cancel, deleteSelected, tearDownSubs
  }
})
