<template>
  <q-layout view="hHh LpR fFf" class="main-layout">
    <q-header>
      <ArchiveHeader
        :connection-status="smbStore.connectionStatus"
        :download-badge="downloadStore.activeCount"
        @toggle-connection="toggleConnection"
        @open-search="goTo('/search')"
        @open-downloads="goTo('/downloads')"
        @open-starred="goTo('/starred')"
      />
      <!-- Inline connection banner — replaces the full-screen overlay so other
           tabs (Downloads, Starred, Settings) remain reachable while offline. -->
      <transition name="fade">
        <div
          v-if="showConnectionBanner"
          class="conn-banner"
          role="status"
        >
          <q-icon name="mdi-wifi-off" size="var(--icon-sm)" class="conn-banner__icon" />
          <span class="conn-banner__text">
            Cannot reach <span class="paper">{{ smbStore.activeShare?.host || 'shelf' }}</span>
          </span>
          <button
            class="btn-link conn-banner__action"
            :disabled="smbStore.loading"
            @click="reconnect"
          >
            {{ smbStore.loading ? 'retrying…' : 'retry' }}
          </button>
          <button
            class="conn-banner__close"
            aria-label="Dismiss"
            @click="dismissBanner"
          >
            <q-icon name="mdi-close" size="var(--icon-sm)" />
          </button>
        </div>
      </transition>
    </q-header>

    <q-page-container>
      <router-view v-slot="{ Component }">
        <transition :name="transitionName" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </q-page-container>

    <q-footer class="main-footer safe-bottom">
      <nav class="nav-bar" role="tablist" aria-label="Primary">
        <button
          v-for="item in navItems"
          :key="item.name"
          class="nav-bar__tab"
          :class="{ 'nav-bar__tab--active': activeTab === item.name }"
          role="tab"
          :aria-selected="activeTab === item.name"
          @click="goToTab(item.path)"
        >
          <span class="nav-bar__icon-wrap">
            <q-icon
              :name="activeTab === item.name ? item.iconActive : item.icon"
              size="24px"
            />
          </span>
          <span class="nav-bar__label">{{ item.label }}</span>
        </button>
      </nav>
    </q-footer>
  </q-layout>
</template>

<script setup>
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { App } from '@capacitor/app'
import ArchiveHeader from '../components/ArchiveHeader.vue'
import { useSmbStore } from '../stores/smb'
import { useDownloadStore } from '../stores/downloads'
import { useShareTargetStore } from '../stores/shareTarget'
import { ShareTarget } from '../plugins/ShareTarget'

const route = useRoute()
const router = useRouter()
const smbStore = useSmbStore()
const downloadStore = useDownloadStore()
const shareTargetStore = useShareTargetStore()
const activeTab = ref('files')
const transitionName = ref('slide-left')
const bannerDismissed = ref(false)

const tabOrder = ['files', 'recents', 'gallery', 'settings']

const navItems = [
  { name: 'files',    path: '/files',    label: 'Files',    icon: 'mdi-folder-outline',         iconActive: 'mdi-folder' },
  { name: 'recents',  path: '/recents',  label: 'Recents',  icon: 'mdi-clock-outline',          iconActive: 'mdi-clock' },
  { name: 'gallery',  path: '/gallery',  label: 'Gallery',  icon: 'mdi-image-multiple-outline', iconActive: 'mdi-image-multiple' },
  { name: 'settings', path: '/settings', label: 'Settings', icon: 'mdi-cog-outline',            iconActive: 'mdi-cog' }
]

// Connection banner shows on offline only after a successful connection has
// happened at least once (so a fresh install doesn't open with a red banner),
// and stays hidden if the user explicitly dismissed it this session.
const showConnectionBanner = computed(() =>
  smbStore.connectionStatus === 'offline' &&
  smbStore.lastConnectedAt &&
  !bannerDismissed.value
)

watch(() => route.name, (to, from) => {
  const toIdx = tabOrder.indexOf(to)
  const fromIdx = tabOrder.indexOf(from)
  transitionName.value = toIdx >= fromIdx ? 'slide-left' : 'slide-right'
  if (tabOrder.includes(to)) activeTab.value = to
}, { immediate: true })

watch(() => smbStore.connectionStatus, (status) => {
  if (status === 'online') bannerDismissed.value = false
})

function goToTab(path) {
  if (route.path !== path) {
    router.push(path)
    return
  }
  // Re-tapping the current tab — treat as a "please recover" signal.  On
  // Files that means: if the listing is still empty (e.g. first load
  // silently failed on cold boot), kick off a fresh directory fetch rather
  // than no-op.  Same idea could extend to other tabs that own their own
  // load lifecycle.
  if (path === '/files' && smbStore.files.length === 0 && !smbStore.loading) {
    smbStore.error = null
    smbStore.loadDirectory(smbStore.currentPath || '')
  }
}

function goTo(path) {
  if (route.path !== path) router.push(path)
}

async function reconnect() {
  await smbStore.loadDirectory(smbStore.currentPath || '')
  if (smbStore.isConnected) bannerDismissed.value = false
}

function dismissBanner() {
  bannerDismissed.value = true
}

async function toggleConnection() {
  if (smbStore.connectionStatus === 'connecting') return
  if (smbStore.isConnected) {
    smbStore.connectionStatus = 'offline'
    smbStore.files = []
    bannerDismissed.value = true
    return
  }
  await smbStore.loadDirectory(smbStore.currentPath || '')
}

let backListener = null
let shareTargetUnsub = null

function onDeepLinkOpen(e) {
  const path = typeof e?.detail === 'string' ? e.detail : ''
  if (path && path.startsWith('/')) {
    router.push(path).catch(() => {})
  }
}

onMounted(async () => {
  backListener = await App.addListener('backButton', async () => {
    if (route.name === 'files' && smbStore.canGoBack) {
      await smbStore.goBack()
      return
    }
    if (!tabOrder.includes(route.name)) {
      router.back()
      return
    }
    if (route.name !== 'files') {
      router.push('/files')
      return
    }
    App.minimizeApp()
  })

  // Share target — Android ACTION_SEND(+_MULTIPLE) routes here.  The first
  // `addListener` call triggers the native plugin to drain any queued events
  // that arrived before this code ran (cold-start-from-share case).
  shareTargetUnsub = await ShareTarget.onFiles((items) => {
    shareTargetStore.set(items)
    router.push('/share-in').catch(() => {})
  })
  // Also poll `getPending` once in case the event fired before the listener
  // was attached (very rare race).
  const pending = await ShareTarget.getPending()
  if (pending?.items?.length) {
    shareTargetStore.set(pending.items)
    router.push('/share-in').catch(() => {})
  }

  // Deep-link from notifications: MainActivity dispatches this custom event
  // when it sees an `open` extra on an incoming intent.
  window.addEventListener('archive-open-path', onDeepLinkOpen)
})

onBeforeUnmount(() => {
  backListener?.remove()
  shareTargetUnsub?.()
  window.removeEventListener('archive-open-path', onDeepLinkOpen)
})
</script>

<style lang="scss" scoped>
.main-layout { background: var(--ink-0); }

.main-footer {
  background: var(--ink-0) !important;
  border-top: 1px solid var(--rule);
  // Longhand so .safe-bottom's `padding-bottom: env(safe-area-inset-bottom)`
  // isn't clobbered. 5px extra lifts the bar off the phone edge.
  padding-top: 8px;
  padding-right: 4px;
  padding-left: 4px;
  padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 5px);
}

.nav-bar {
  display: flex;
  align-items: stretch;
  width: 100%;

  &__tab {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    padding: 11px 4px 9px;
    background: transparent;
    border: none;
    cursor: pointer;
    color: var(--paper-dim);
    transition: color 180ms var(--ease-archive);

    &:hover { color: var(--paper-mute); }

    // Tactile micro-interaction: a gentle spring on press.
    &:active .nav-bar__icon-wrap {
      transform: scale(0.88);
    }

    &--active {
      color: var(--marigold);

      .nav-bar__icon-wrap { transform: scale(1.06); }
      .nav-bar__label     { font-weight: 600; color: var(--marigold); }
      :deep(.q-icon)      { color: var(--marigold) !important; }
    }
  }

  &__icon-wrap {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 28px;
    transition: transform 220ms cubic-bezier(0.34, 1.56, 0.64, 1);
  }

  &__label {
    font-family: var(--font-body);
    font-weight: 400;
    font-size: 11px;
    line-height: 1;
    letter-spacing: 0.01em;
    white-space: nowrap;
    transition: font-weight 180ms var(--ease-archive);
  }

}

.conn-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: rgba(200, 90, 60, 0.12);
  border-bottom: 1px solid rgba(200, 90, 60, 0.3);
  color: var(--paper);
  font-family: var(--font-body);
  font-size: 13px;

  &__icon { color: var(--rust); flex-shrink: 0; }
  &__text { flex: 1; min-width: 0; }
  &__action { color: var(--paper); }

  &__close {
    width: 32px;
    height: 32px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper-mute);
    cursor: pointer;
    border-radius: 50%;
    &:hover { background: rgba(0, 0, 0, 0.2); color: var(--paper); }
  }
}
</style>
