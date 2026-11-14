import { registerPlugin, Capacitor } from '@capacitor/core'

const ShareTargetNative = registerPlugin('ShareTarget')
const isNative = Capacitor.isNativePlatform()

/**
 * Incoming share-target bridge.
 *
 *   ShareTarget.onFiles(handler)   — subscribe to ACTION_SEND(+_MULTIPLE) events
 *   ShareTarget.getPending()       — drain any events that arrived before listeners attached
 */
export const ShareTarget = {
  async onFiles(handler) {
    if (!isNative) return () => {}
    const sub = await ShareTargetNative.addListener('files', (payload) => {
      const items = Array.isArray(payload?.items) ? payload.items : []
      if (items.length > 0) handler(items)
    })
    // Tell native the listener is ready so it drains any queued intents.
    try { await ShareTargetNative.markListenerReady() } catch { /* non-fatal */ }
    return () => { try { sub.remove() } catch { /* non-fatal */ } }
  },

  async getPending() {
    if (!isNative) return { items: [] }
    try { return await ShareTargetNative.getPending() } catch { return { items: [] } }
  }
}
