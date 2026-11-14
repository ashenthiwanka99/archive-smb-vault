import { boot } from 'quasar/wrappers'
import { createPinia, setActivePinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'

export default boot(({ app }) => {
  const pinia = createPinia()
  pinia.use(piniaPluginPersistedstate)
  app.use(pinia)

  // Fire-and-forget seed. Must NOT await — seedDefaultShares calls into
  // @capacitor/preferences which may block until the Capacitor bridge is
  // fully up, and awaiting here deadlocks app boot → blank screen.
  setActivePinia(pinia)
  Promise.resolve().then(async () => {
    try {
      const { useSettingsStore } = await import('../stores/settings')
      const settings = useSettingsStore(pinia)
      await settings.seedDefaultShares()
    } catch (e) {
      console.warn('seedDefaultShares failed:', e)
    }
  })
})
