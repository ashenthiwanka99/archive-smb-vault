import { defineStore } from 'pinia'
import { ref } from 'vue'
import { loadCredentials, saveCredentials, removeCredentials } from '../utils/secureCredentials'

// Share record shape persisted to Pinia:
//   { id, name, host, shareName, isGuest }
// NOTE: username/password are NEVER stored here. They live in secureCredentials,
// keyed by share id, and are fetched on demand via getShareCredentials(id).

// Default shelves, seeded once on first install. The public source ships none:
// add your NAS from Settings. For a private build you can list shares here as
// { name, host, shareName } and set DEFAULT_CREDS to { username, password }.
// Credentials go through secureCredentials (Capacitor Preferences), NOT the
// persisted Pinia state, so they don't sit in localStorage plaintext.
const DEFAULT_SHELVES = []
const DEFAULT_CREDS = null

export const useSettingsStore = defineStore('settings', () => {
  const shares = ref([])
  const defaultsSeeded = ref(false)

  const defaultView = ref('list')
  const connectionTimeout = ref(10000)
  const autoReconnect = ref(true)
  const biometricEnabled = ref(false)
  // On-screen debug console — captures console.* + window errors + Vue
  // errors and surfaces them in a draggable overlay.  Off by default,
  // useful when troubleshooting "the app says it can't connect but the
  // server clearly works" type issues.
  const debugConsoleEnabled = ref(false)
  // NOTE: lockPin is a convenience lock, NOT a security boundary. Stored
  // locally in plain text alongside other persisted state. For actual security,
  // biometric identity is used; the PIN is just the fallback when biometric
  // hardware isn't available / is unenrolled / the user dismisses the prompt.
  const lockPin = ref(null)

  function setLockPin(pin) {
    lockPin.value = pin || null
  }

  function addShare(share) {
    const id = Date.now().toString(36)
    shares.value.push({
      id,
      name: share.name,
      host: share.host,
      shareName: share.shareName,
      isGuest: share.isGuest !== false
    })
    return id
  }

  function updateShare(id, data) {
    const idx = shares.value.findIndex(s => s.id === id)
    if (idx === -1) return
    const allowed = ['name', 'host', 'shareName', 'isGuest']
    const next = { ...shares.value[idx] }
    for (const key of allowed) {
      if (key in data) next[key] = data[key]
    }
    shares.value[idx] = next
  }

  async function removeShare(id) {
    shares.value = shares.value.filter(s => s.id !== id)
    await removeCredentials(id)
  }

  function getShareById(id) {
    return shares.value.find(s => s.id === id)
  }

  // All three credential ops must never throw — if the native Preferences plugin
  // is missing or hangs, UI flows (dialog open/close) would be blocked otherwise.
  async function getShareCredentials(id) {
    try {
      return await loadCredentials(id)
    } catch {
      return { username: '', password: '' }
    }
  }

  async function setShareCredentials(id, { username, password }) {
    try {
      await saveCredentials(id, { username, password })
    } catch {
      // swallow — persistence failure should not block the UI
    }
    updateShare(id, { isGuest: !username })
  }

  async function clearShareCredentials(id) {
    try {
      await removeCredentials(id)
    } catch {
      // swallow
    }
    updateShare(id, { isGuest: true })
  }

  // Seed the home NAS shelves. Two behaviours:
  //   1. First run (defaultsSeeded=false) — add any missing defaults, set the flag.
  //   2. Reconcile (defaultsSeeded=true, but a default was removed from an old
  //      build and we shipped a new one) — silently re-add any missing default.
  // Never touches existing shelves; only ADDS what's missing by host+shareName.
  async function seedDefaultShares() {
    const existsFor = (def) =>
      shares.value.some(s => s.host === def.host && s.shareName === def.shareName)
    let added = false
    for (const def of DEFAULT_SHELVES) {
      if (existsFor(def)) continue
      const id = addShare({ ...def, isGuest: !DEFAULT_CREDS })
      if (DEFAULT_CREDS) await setShareCredentials(id, DEFAULT_CREDS)
      added = true
    }
    if (!defaultsSeeded.value) defaultsSeeded.value = true
    return added
  }

  // Additive: re-create any missing default shelf without touching the user's
  // own shelves.  Earlier this wiped every share (including custom ones) —
  // silent data loss on a single "Reset" tap.  If the user needs to actually
  // remove a shelf they can do that from its card.
  async function resetToDefaultShares() {
    defaultsSeeded.value = false
    return await seedDefaultShares()
  }

  return {
    shares,
    defaultView,
    connectionTimeout,
    autoReconnect,
    biometricEnabled,
    debugConsoleEnabled,
    lockPin,
    setLockPin,
    defaultsSeeded,
    addShare,
    updateShare,
    removeShare,
    getShareById,
    getShareCredentials,
    setShareCredentials,
    clearShareCredentials,
    seedDefaultShares,
    resetToDefaultShares
  }
}, {
  persist: {
    // Only persist non-sensitive config. Credentials go through secureCredentials.
    pick: ['shares', 'defaultView', 'connectionTimeout', 'autoReconnect', 'biometricEnabled', 'debugConsoleEnabled', 'lockPin', 'defaultsSeeded']
  }
})
