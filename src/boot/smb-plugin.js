import { boot } from 'quasar/wrappers'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { Capacitor } from '@capacitor/core'

export default boot(({ app }) => {
  app.config.globalProperties.$smb = SmbPlugin
  app.provide('smb', SmbPlugin)

  // Refresh backup status on app start
  if (Capacitor.isNativePlatform()) {
    setTimeout(async () => {
      try {
        const { useBackupStore } = await import('../stores/backup')
        const backupStore = useBackupStore()
        await backupStore.refreshStatus()
      } catch (e) {
        console.warn('Backup status refresh failed:', e)
      }
    }, 2000)
  }
})
