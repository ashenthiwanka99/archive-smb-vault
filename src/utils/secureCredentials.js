// Secure credential storage abstraction.
// Uses Capacitor Preferences (kept separate from main persisted state so passwords
// don't end up in Pinia's blob). When an Android KeyStore-backed plugin is added,
// only the body of get/set/remove needs to change.

import { Capacitor } from '@capacitor/core'
import { Preferences } from '@capacitor/preferences'

// NOTE: do NOT return the Preferences plugin proxy from an async function.
// Capacitor plugin proxies intercept every property access, including `.then`,
// so awaiting a value that *is* the proxy makes the engine treat it as a
// thenable and invoke Preferences.then() — which rejects with
// "Preferences.then() is not implemented on android". Access Preferences
// directly from sync code instead.

const memoryStore = new Map()
const KEY_PREFIX = 'cred::'
const useNative = () => Capacitor.isNativePlatform()

function memKey(shareId) { return `${KEY_PREFIX}${shareId}` }

export async function saveCredentials(shareId, { username, password }) {
  if (!shareId) throw new Error('shareId required')
  const payload = JSON.stringify({ username: username || '', password: password || '' })
  if (useNative()) {
    await Preferences.set({ key: memKey(shareId), value: payload })
  } else {
    memoryStore.set(memKey(shareId), payload)
  }
}

export async function loadCredentials(shareId) {
  if (!shareId) return { username: '', password: '' }
  let raw = null
  if (useNative()) {
    const res = await Preferences.get({ key: memKey(shareId) })
    raw = res?.value ?? null
  } else {
    raw = memoryStore.get(memKey(shareId)) ?? null
  }
  if (!raw) return { username: '', password: '' }
  try {
    const parsed = JSON.parse(raw)
    return { username: parsed.username || '', password: parsed.password || '' }
  } catch {
    return { username: '', password: '' }
  }
}

export async function removeCredentials(shareId) {
  if (!shareId) return
  if (useNative()) {
    await Preferences.remove({ key: memKey(shareId) })
  } else {
    memoryStore.delete(memKey(shareId))
  }
}
