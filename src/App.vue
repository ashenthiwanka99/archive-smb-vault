<template>
  <BiometricLock />
  <router-view />
  <!-- DebugOverlay is user-toggleable from Settings, plus an emergency
       ?debug=1 URL switch when settings can't be reached. -->
  <DebugOverlay v-if="debugEnabled" />
</template>

<script setup>
import { computed } from 'vue'
import BiometricLock from './components/BiometricLock.vue'
import DebugOverlay from './components/DebugOverlay.vue'
import { useSettingsStore } from './stores/settings'

const settingsStore = useSettingsStore()
const debugEnabled = computed(() => {
  if (settingsStore.debugConsoleEnabled) return true
  if (typeof window === 'undefined') return false
  return window.location.search.includes('debug=1') ||
         window.location.hash.includes('debug=1')
})
// Seeding is handled by the seed-defaults boot file so it runs BEFORE any
// Vue component mounts (children mount before parents, so if we seed in
// App.vue.onMounted, FileBrowserPage.onMounted fires first with no shares).
</script>

<style lang="scss">
// Global page-level transitions handled in app.scss; nothing app-scoped here.
</style>
