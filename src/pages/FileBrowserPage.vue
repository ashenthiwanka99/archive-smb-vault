<template>
  <q-page class="file-browser">
    <!-- Upload Progress -->
    <UploadProgress
      v-if="smbStore.uploading"
      :uploading="smbStore.uploading"
      :progress="smbStore.uploadProgress"
      @cancel="smbStore.uploading = false"
    />

    <!-- Empty-shares onboarding -->
    <div v-if="settingsStore.shares.length === 0" class="empty-state">
      <div class="empty-state__eyebrow">Welcome</div>
      <div class="empty-state__title">Your archive is empty.</div>
      <div class="empty-state__subtitle">
        Point the app at a shelf on your network — an SMB share on your NAS or home server — to begin browsing.
      </div>
      <button class="btn-archive q-mt-lg" @click="$router.push('/settings')">
        Add your first shelf
        <q-icon name="mdi-arrow-right" size="16px" />
      </button>
    </div>

    <template v-else>
      <SelectionToolbar
        v-if="smbStore.selectionMode"
        @download="batchDownload"
        @star="batchStar"
        @delete="batchDelete"
        @copy="batchCopyMove('copy')"
        @move="batchCopyMove('move')"
        @select-all="smbStore.selectAll()"
      />

      <div v-else class="file-browser__shelf-row">
        <ShareSwitcher
          :shares="settingsStore.shares"
          :active-id="smbStore.activeShare?.id || ''"
          class="file-browser__switcher"
          @switch="smbStore.switchShare"
        />
        <div class="file-browser__shelf-actions">
          <button
            class="file-browser__icon-btn"
            :aria-label="smbStore.viewMode === 'list' ? 'Switch to grid view' : 'Switch to list view'"
            @click="smbStore.viewMode = smbStore.viewMode === 'list' ? 'grid' : 'list'"
          >
            <q-icon
              :name="smbStore.viewMode === 'list' ? 'mdi-view-list-outline' : 'mdi-view-grid-outline'"
              size="var(--icon-md)"
              class="paper-mute"
            />
          </button>
          <button class="file-browser__icon-btn" aria-label="Sort">
            <q-icon name="mdi-sort-variant" size="var(--icon-md)" class="paper-mute" />
            <q-menu>
              <q-list dense style="min-width: 180px;">
                <q-item
                  v-for="opt in sortOptions"
                  :key="opt.value"
                  clickable v-close-popup
                  @click="smbStore.toggleSort(opt.value)"
                >
                  <q-item-section avatar>
                    <q-icon
                      :name="smbStore.sortBy === opt.value
                        ? (smbStore.sortAsc ? 'mdi-arrow-up' : 'mdi-arrow-down')
                        : 'mdi-minus'"
                      size="var(--icon-sm)"
                    />
                  </q-item-section>
                  <q-item-section>{{ opt.label }}</q-item-section>
                </q-item>
              </q-list>
            </q-menu>
          </button>
        </div>
      </div>

      <BreadcrumbBar
        v-if="smbStore.currentPath && !smbStore.selectionMode"
        :crumbs="smbStore.breadcrumbs"
        @navigate="smbStore.navigateTo"
      />

      <q-inner-loading :showing="smbStore.operating" color="primary" />

      <!-- Loading skeleton -->
      <div v-if="smbStore.loading" class="q-pa-md stack">
        <div v-for="i in 6" :key="i" class="skeleton-shimmer" style="height: 64px;" />
      </div>

      <!-- Not connected -->
      <div v-else-if="!smbStore.isConnected && smbStore.files.length === 0 && !smbStore.error" class="empty-state">
        <div class="empty-state__eyebrow">Shelf</div>
        <div class="empty-state__title">Not yet linked.</div>
        <div class="empty-state__subtitle">Tap connect to reach {{ smbStore.activeShare?.host }}.</div>
        <button class="btn-archive q-mt-lg" :disabled="smbStore.loading" @click="smbStore.loadDirectory('')">
          {{ smbStore.loading ? 'linking…' : 'connect' }}
        </button>
      </div>

      <!-- Error state / auth prompt -->
      <div v-else-if="smbStore.error" class="empty-state">
        <div class="empty-state__eyebrow">Shelf</div>
        <div class="empty-state__title">{{ isAccessDenied ? 'Restricted.' : 'Cannot reach shelf.' }}</div>
        <div class="empty-state__subtitle">{{ smbStore.error }}</div>

        <template v-if="isAccessDenied">
          <div class="auth-form q-mt-lg">
            <div class="caption-sc q-mb-sm">Credentials</div>
            <q-input v-model="authUsername" dense borderless placeholder="username"
              autocomplete="username"
              autocapitalize="none"
              autocorrect="off"
              spellcheck="false"
              class="auth-form__input q-mb-sm">
              <template #prepend><q-icon name="mdi-account-outline" size="var(--icon-sm)" class="paper-dim" /></template>
            </q-input>
            <q-input v-model="authPassword" dense borderless placeholder="password"
              autocomplete="current-password"
              :type="showPassword ? 'text' : 'password'" class="auth-form__input q-mb-md">
              <template #prepend><q-icon name="mdi-lock-outline" size="var(--icon-sm)" class="paper-dim" /></template>
              <template #append>
                <q-icon :name="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" size="var(--icon-sm)"
                  class="cursor-pointer paper-dim"
                  :aria-label="showPassword ? 'Hide password' : 'Show password'"
                  @click="showPassword = !showPassword" />
              </template>
            </q-input>
            <button class="btn-archive full-width" :disabled="smbStore.loading" @click="loginAndConnect">
              {{ smbStore.loading ? 'verifying…' : 'sign in & link' }}
            </button>
            <label class="auth-form__persist">
              <q-checkbox v-model="saveCredentials" color="primary" dense size="sm" />
              <span>Save for this shelf</span>
            </label>
          </div>
        </template>
        <template v-else>
          <button class="btn-ghost q-mt-lg" :disabled="smbStore.loading" @click="retryConnection">retry</button>
        </template>
        <button class="btn-ghost q-mt-sm" @click="$router.push('/settings')">open settings</button>
      </div>

      <!-- Empty directory -->
      <div v-else-if="smbStore.filteredFiles.length === 0" class="empty-state">
        <div class="empty-state__eyebrow">{{ smbStore.searchQuery ? 'Search' : 'Folder' }}</div>
        <div class="empty-state__title">
          {{ smbStore.searchQuery ? 'Nothing matches.' : 'This folder is empty.' }}
        </div>
        <div v-if="!smbStore.searchQuery" class="empty-state__subtitle">
          Add files to this shelf or drop something in from another app.
        </div>
        <button v-if="smbStore.searchQuery" class="btn-ghost q-mt-md" @click="smbStore.searchQuery = ''">
          Clear search
        </button>
      </div>

      <!-- List / grid view wrapped in pull-to-refresh -->
      <q-pull-to-refresh
        v-else
        @refresh="onPullRefresh"
        color="primary"
        bg-color="transparent"
        icon-color="primary"
      >
        <template v-if="smbStore.viewMode === 'list'">
          <div class="file-browser__count caption-sc">
            {{ smbStore.folderCount }} folders · {{ smbStore.fileCount }} files
          </div>
          <div>
            <SwipeFileRow
              v-for="file in smbStore.filteredFiles"
              :key="file.name"
              :file="file"
              @tap="handleTap(file)"
              @download="handleDownload(file)"
              @star="handleStar(file)"
              @delete="handleDelete(file)"
            />
          </div>
        </template>

        <template v-else>
          <div class="file-browser__count caption-sc">
            {{ smbStore.folderCount }} folders · {{ smbStore.fileCount }} files
          </div>
          <div class="file-browser__grid">
            <FileGrid
              v-for="file in smbStore.filteredFiles"
              :key="file.name"
              :file="file"
              @tap="handleTap(file)"
              @longpress="showContextMenu(file)"
            />
          </div>
        </template>
      </q-pull-to-refresh>

      <UploadFab
        v-if="smbStore.isConnected"
        @upload-files="pickAndUploadFiles"
        @upload-photos="pickAndUploadPhotos"
        @new-folder="createNewFolder"
      />
    </template>

    <MiniPlayer v-if="playerStore.miniPlayerVisible" @expand="showFullPlayer = true" />
    <MusicPlayerFull v-if="showFullPlayer" @minimize="showFullPlayer = false" />
  </q-page>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { FilePicker } from '@capawesome/capacitor-file-picker'
import { useSmbStore } from '../stores/smb'
import { useSettingsStore } from '../stores/settings'
import { useRecentStore } from '../stores/recents'
import { useStarredStore } from '../stores/starred'
import { useDownloadStore } from '../stores/downloads'
import { useUploadsStore } from '../stores/uploads'
import { usePlayerStore } from '../stores/player'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { friendlyError } from '../utils/sanitize'
import SwipeFileRow from '../components/SwipeFileRow.vue'
import FileGrid from '../components/FileGrid.vue'
import ShareSwitcher from '../components/ShareSwitcher.vue'
import BreadcrumbBar from '../components/BreadcrumbBar.vue'
import SelectionToolbar from '../components/SelectionToolbar.vue'
import UploadFab from '../components/UploadFab.vue'
import UploadProgress from '../components/UploadProgress.vue'
import MiniPlayer from '../components/MiniPlayer.vue'
import MusicPlayerFull from '../components/MusicPlayerFull.vue'

const $q = useQuasar()
const router = useRouter()
const smbStore = useSmbStore()
const settingsStore = useSettingsStore()
const recentStore = useRecentStore()
const starredStore = useStarredStore()
const downloadStore = useDownloadStore()
const uploadsStore = useUploadsStore()
const playerStore = usePlayerStore()

const navigating = ref(false)
const authUsername = ref('')
const authPassword = ref('')
const showPassword = ref(false)
const saveCredentials = ref(true)
const showFullPlayer = ref(false)

const isAccessDenied = computed(() => {
  const err = (smbStore.error || '').toLowerCase()
  return err.includes('access') || err.includes('denied') || err.includes('auth') ||
    err.includes('logon') || err.includes('credential') || err.includes('password')
})

const sortOptions = [
  { label: 'Name', value: 'name' },
  { label: 'Size', value: 'size' },
  { label: 'Date', value: 'date' },
  { label: 'Type', value: 'type' }
]

// Auto-retry supervisor for the initial directory load.  The native SMB
// session can be genuinely busy on app launch (WorkManager backup worker
// hogging it, or the OS still waking the Wi-Fi radio), and a single attempt
// is not enough — the user was falling back to pull-to-refresh manually.
//
// This keeps retrying as long as the page is mounted and the listing is
// still empty.  There is no attempt cap: a user sitting on an empty Files
// page clearly wants a listing, not a resigned "gave up."  The interval
// grows from 2s → 10s so we don't hammer the bridge forever, but we never
// stop entirely — and a visibility/online event jumps the schedule back
// to an immediate retry (the OS just handed us fresh resources).
let loadSupervisor = null
let loadAttempts = 0
let loadDelay = 2000
const MAX_DELAY = 10_000

function computeDelay() {
  // Tight 2s retries for the first few attempts; back off to 10s after that
  // so we stay responsive to transient cold-start failures without burning
  // CPU on a shelf that's genuinely unreachable.
  if (loadAttempts < 3) return 2000
  if (loadAttempts < 6) return 4000
  return MAX_DELAY
}

function scheduleNextRetry() {
  if (loadSupervisor) { clearTimeout(loadSupervisor); loadSupervisor = null }
  if (smbStore.files.length > 0) return
  loadSupervisor = setTimeout(() => {
    loadSupervisor = null
    if (smbStore.files.length > 0) return
    tryLoad()
    loadDelay = computeDelay()
    scheduleNextRetry()
  }, loadDelay)
}

function tryLoad() {
  if (smbStore.loading) return
  if (smbStore.files.length > 0) return
  if (!smbStore.activeShare) return
  loadAttempts++
  smbStore.error = null
  smbStore.loadDirectory('')
}

// Fresh resources just arrived → retry immediately.  Covers the cold-start
// case where the Wi-Fi radio wakes mid-mount, and the case where the
// backup worker finishes hogging the SMB session.
function onRecover() {
  if (smbStore.files.length > 0) return
  loadAttempts = 0
  loadDelay = 2000
  tryLoad()
  scheduleNextRetry()
}

onMounted(async () => {
  // Make absolutely sure the default shelves are seeded before we try to load.
  // The seed-defaults boot file runs first, but if it races with this mount
  // (e.g. on cold start), await it here too — it's idempotent.
  if (settingsStore.shares.length === 0) {
    await settingsStore.seedDefaultShares()
  }
  // Only manage the auto-load when we actually need one.
  if (smbStore.files.length > 0) return
  tryLoad()
  scheduleNextRetry()
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onRecover)
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('online', onRecover)
  }
})

onBeforeUnmount(() => {
  if (loadSupervisor) { clearTimeout(loadSupervisor); loadSupervisor = null }
  loadAttempts = 0
  loadDelay = 2000
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', onRecover)
  }
  if (typeof window !== 'undefined') {
    window.removeEventListener('online', onRecover)
  }
})

async function handleTap(file) {
  if (navigating.value) return
  navigating.value = true
  try {
    if (file.isDirectory) {
      await smbStore.navigateInto(file.name)
    } else if (file.type === 'audio') {
      // Play audio in built-in player
      await playAudio(file)
    } else {
      const fullPath = smbStore.currentPath ? `${smbStore.currentPath}/${file.name}` : file.name
      recentStore.addRecent({ ...file, path: fullPath, shareId: smbStore.activeShareId })
      router.push({
        name: 'preview',
        query: { name: file.name, type: file.type, size: file.size, path: fullPath, shareId: smbStore.activeShareId }
      })
    }
  } finally {
    setTimeout(() => { navigating.value = false }, 300)
  }
}

async function playAudio(file) {
  const fullPath = smbStore.currentPath ? `${smbStore.currentPath}/${file.name}` : file.name
  try {
    $q.notify({ message: `Loading ${file.name}...`, color: 'info', timeout: 2000 })
    const shareId = smbStore.activeShare?.id
    if (!shareId) throw new Error('No share configured')
    const audioFiles = smbStore.files.filter(f => f.type === 'audio').map(f => ({
      name: f.name,
      path: smbStore.currentPath ? `${smbStore.currentPath}/${f.name}` : f.name,
      shareId,
      size: f.size
    }))
    playerStore.setQueue(audioFiles)
    // Player resolves credentials itself via settings store
    await playerStore.play({ name: file.name, path: fullPath, shareId })
  } catch (e) {
    $q.notify({ message: `Failed to play: ${friendlyError(e)}`, color: 'negative', timeout: 3000 })
  }
}

function handleDownload(file) {
  const share = smbStore.activeShare
  if (!share) {
    $q.notify({ message: 'No share configured', color: 'negative', timeout: 2000 })
    return
  }
  const fullPath = smbStore.currentPath ? `${smbStore.currentPath}/${file.name}` : file.name
  const id = downloadStore.enqueue({
    name: file.name, path: fullPath, shareId: share.id,
    host: share.host, shareName: share.shareName,
    size: file.size, type: file.type
  })
  $q.notify({ message: id ? `Download queued: ${file.name}` : 'Already downloading',
    color: id ? 'positive' : 'warning', textColor: 'dark', timeout: 2000 })
}

function handleStar(file) {
  const fullPath = smbStore.currentPath ? `${smbStore.currentPath}/${file.name}` : file.name
  const was = starredStore.isStarred(fullPath, smbStore.activeShareId)
  starredStore.toggleStar({
    name: file.name, path: fullPath, shareId: smbStore.activeShareId,
    type: file.type, isDirectory: file.isDirectory, size: file.size
  })
  $q.notify({ message: was ? 'Removed from starred' : 'Added to starred',
    color: 'positive', textColor: 'dark', timeout: 1500 })
}

async function handleDelete(file) {
  $q.dialog({
    title: 'Confirm delete', message: `Delete "${file.name}"?`,
    cancel: true, ok: { label: 'Delete', color: 'negative' }
  }).onOk(async () => {
    const result = await smbStore.deleteItem(file.name)
    $q.notify({
      message: result.success ? `"${file.name}" deleted` : `Delete failed: ${result.error}`,
      color: result.success ? 'positive' : 'negative',
      textColor: result.success ? 'dark' : 'white', timeout: 3000
    })
  })
}

// -----------------------------
// Batch operations (selection mode)
// Each batch op reuses the single-file handler in a loop; we clear selection
// at the end so the toolbar closes.
// -----------------------------

function batchDownload() {
  const files = smbStore.selectedFilesList()
  if (files.length === 0) return
  for (const f of files) {
    if (!f.isDirectory) handleDownload(f)
  }
  $q.notify({ message: `Queued ${files.length} download${files.length === 1 ? '' : 's'}`, color: 'positive', textColor: 'dark', timeout: 1800 })
  smbStore.clearSelection()
}

function batchStar() {
  const files = smbStore.selectedFilesList()
  if (files.length === 0) return
  for (const f of files) handleStar(f)
  smbStore.clearSelection()
}

function batchDelete() {
  const files = smbStore.selectedFilesList()
  if (files.length === 0) return
  $q.dialog({
    title: 'Confirm delete',
    message: `Delete ${files.length} item${files.length === 1 ? '' : 's'}? This cannot be undone.`,
    cancel: true,
    ok: { label: 'Delete', color: 'negative' }
  }).onOk(async () => {
    let ok = 0, fail = 0
    for (const f of files) {
      const r = await smbStore.deleteItem(f.name)
      if (r.success) ok++; else fail++
    }
    $q.notify({
      message: fail === 0
        ? `Deleted ${ok} item${ok === 1 ? '' : 's'}`
        : `Deleted ${ok}, ${fail} failed`,
      color: fail === 0 ? 'positive' : 'negative',
      textColor: fail === 0 ? 'dark' : 'white',
      timeout: 2500
    })
    smbStore.clearSelection()
  })
}

function batchCopyMove(kind) {
  const files = smbStore.selectedFilesList()
  if (files.length === 0) return
  const title = kind === 'copy' ? 'Copy to' : 'Move to'
  $q.dialog({
    title, prompt: { model: '', type: 'text', label: 'Destination path' }, cancel: true
  }).onOk(async dest => {
    if (!dest) return
    let ok = 0, fail = 0
    for (const f of files) {
      const r = kind === 'copy'
        ? await smbStore.copyItem(f.name, dest)
        : await smbStore.moveItem(f.name, dest)
      if (r.success) ok++; else fail++
    }
    $q.notify({
      message: fail === 0
        ? `${kind === 'copy' ? 'Copied' : 'Moved'} ${ok} item${ok === 1 ? '' : 's'}`
        : `${ok} succeeded, ${fail} failed`,
      color: fail === 0 ? 'positive' : 'negative',
      textColor: fail === 0 ? 'dark' : 'white', timeout: 2500
    })
    smbStore.clearSelection()
  })
}

function showContextMenu(file) {
  const fullPath = smbStore.currentPath ? `${smbStore.currentPath}/${file.name}` : file.name
  const starred = starredStore.isStarred(fullPath, smbStore.activeShareId)

  $q.bottomSheet({
    title: file.name, titleClass: 'paper display',
    actions: [
      { label: starred ? 'Unstar' : 'Star', icon: starred ? 'mdi-star' : 'mdi-star-outline', id: 'star' },
      ...(!file.isDirectory ? [
        { label: 'Download', icon: 'mdi-download', id: 'download' },
        { label: 'Share', icon: 'mdi-share-variant', id: 'share' },
      ] : [
        { label: 'Download folder', icon: 'mdi-folder-download-outline', id: 'download-folder' },
      ]),
      { label: 'Copy to…', icon: 'mdi-content-copy', id: 'copy' },
      { label: 'Move to…', icon: 'mdi-file-move', id: 'move' },
      { label: 'Rename', icon: 'mdi-pencil', id: 'rename' },
      ...(file.isDirectory ? [{ label: 'Folder size', icon: 'mdi-folder-information', id: 'size' }] : []),
      { label: 'Delete', icon: 'mdi-delete', id: 'delete', classes: 'text-danger' }
    ]
  }).onOk(action => handleContextAction(action, file, fullPath, starred))
}

async function handleContextAction(action, file, fullPath, starred) {
  switch (action.id) {
    case 'star': handleStar(file); break
    case 'download': handleDownload(file); break
    case 'share':
      try {
        const { share, creds } = await smbStore.resolveShareAndCreds()
        if (!share) throw new Error('No share configured')
        await SmbPlugin.shareFile({
          host: share.host, share: share.shareName,
          path: fullPath, username: creds.username, password: creds.password
        })
      } catch (e) {
        $q.notify({ message: `Share failed: ${friendlyError(e)}`, color: 'negative', timeout: 3000 })
      }
      break
    case 'copy':
      $q.dialog({ title: 'Copy to', prompt: { model: '', type: 'text', label: 'Destination path' }, cancel: true })
        .onOk(async dest => {
          if (!dest) return
          const r = await smbStore.copyItem(file.name, dest)
          $q.notify({ message: r.success ? 'Copied' : `Failed: ${r.error}`, color: r.success ? 'positive' : 'negative', textColor: r.success ? 'dark' : 'white', timeout: 3000 })
        })
      break
    case 'move':
      $q.dialog({ title: 'Move to', prompt: { model: '', type: 'text', label: 'Destination path' }, cancel: true })
        .onOk(async dest => {
          if (!dest) return
          const r = await smbStore.moveItem(file.name, dest)
          $q.notify({ message: r.success ? 'Moved' : `Failed: ${r.error}`, color: r.success ? 'positive' : 'negative', textColor: r.success ? 'dark' : 'white', timeout: 3000 })
        })
      break
    case 'rename':
      $q.dialog({ title: 'Rename', prompt: { model: file.name, type: 'text' }, cancel: true })
        .onOk(async newName => {
          if (!newName || newName === file.name) return
          const r = await smbStore.renameItem(file.name, newName)
          $q.notify({ message: r.success ? `Renamed to "${newName}"` : `Failed: ${r.error}`, color: r.success ? 'positive' : 'negative', textColor: r.success ? 'dark' : 'white', timeout: 3000 })
        })
      break
    case 'size':
      $q.notify({ message: 'Calculating...', color: 'info', timeout: 1500 })
      const info = await smbStore.getItemSize(file.name)
      if (info?.size !== undefined) {
        const { formatSize } = await import('../utils/helpers')
        $q.notify({ message: `${file.name}: ${formatSize(info.size)} (${info.fileCount} files)`, color: 'positive', textColor: 'dark', timeout: 5000 })
      }
      break
    case 'download-folder':
      downloadFolder(file, fullPath)
      break
    case 'delete': handleDelete(file); break
  }
}

async function pickAndUploadFiles() {
  try {
    const result = await FilePicker.pickFiles({ readData: false })
    if (!result.files?.length) return
    await queuePickedFiles(result.files)
  } catch (e) {
    if (!e.message?.includes('cancel')) {
      $q.notify({ message: `Pick failed: ${e.message}`, color: 'negative', timeout: 3000 })
    }
  }
}

async function pickAndUploadPhotos() {
  try {
    const result = await FilePicker.pickImages({ readData: false })
    if (!result.files?.length) return
    await queuePickedFiles(result.files)
  } catch (e) {
    if (!e.message?.includes('cancel')) {
      $q.notify({ message: `Pick failed: ${e.message}`, color: 'negative', timeout: 3000 })
    }
  }
}

async function queuePickedFiles(picked) {
  const share = smbStore.activeShare
  if (!share) {
    $q.notify({ message: 'No shelf configured', color: 'negative', timeout: 2500 })
    return
  }
  const files = picked.map(f => ({
    localUri: f.path || f.uri,
    name: f.name,
    size: Number(f.size) || 0
  }))
  const r = await uploadsStore.enqueue({ files, share, path: smbStore.currentPath || '' })
  $q.notify({
    message: r.success
      ? `Queued ${r.queued} upload${r.queued === 1 ? '' : 's'} — see Downloads tab`
      : `Upload failed: ${r.error}`,
    color: r.success ? 'positive' : 'negative',
    textColor: r.success ? 'dark' : 'white',
    timeout: 2800
  })
}

function createNewFolder() {
  $q.dialog({ title: 'New folder', prompt: { model: '', type: 'text', label: 'Folder name' }, cancel: true })
    .onOk(async name => {
      if (!name?.trim()) return
      const r = await smbStore.createFolder(name.trim())
      $q.notify({ message: r.success ? `Folder "${name}" created` : `Failed: ${r.error}`,
        color: r.success ? 'positive' : 'negative', textColor: r.success ? 'dark' : 'white', timeout: 3000 })
    })
}

async function loginAndConnect() {
  if (!authUsername.value.trim()) {
    $q.notify({ message: 'Username required', color: 'negative', timeout: 2000 })
    return
  }
  const share = smbStore.activeShare
  if (!share) return

  // Load previous credentials so we can restore them if the new ones fail.
  const previous = await settingsStore.getShareCredentials(share.id)

  // Try the new credentials via a transient set, then verify with a directory list.
  await settingsStore.setShareCredentials(share.id, {
    username: authUsername.value.trim(),
    password: authPassword.value
  })
  await smbStore.loadDirectory(smbStore.currentPath || '')

  if (smbStore.isConnected) {
    $q.notify({ message: 'Connected', color: 'positive', textColor: 'dark', timeout: 2000 })
    if (!saveCredentials.value) {
      // User opted not to persist — clear after successful session.
      await settingsStore.clearShareCredentials(share.id)
    }
    authPassword.value = ''
  } else {
    // Login failed — restore whatever was there before instead of persisting bad creds.
    if (previous.username || previous.password) {
      await settingsStore.setShareCredentials(share.id, previous)
    } else {
      await settingsStore.clearShareCredentials(share.id)
    }
    $q.notify({ message: 'Login failed — credentials not saved', color: 'negative', timeout: 3000 })
  }
}

function retryConnection() { smbStore.loadDirectory(smbStore.currentPath || '') }

async function onPullRefresh(done) {
  try {
    await smbStore.loadDirectory(smbStore.currentPath || '')
  } finally {
    done()
  }
}

async function downloadFolder(file, fullPath) {
  const share = smbStore.activeShare
  if (!share) {
    $q.notify({ message: 'No shelf configured', color: 'negative', timeout: 2500 })
    return
  }
  $q.notify({
    message: `Walking ${file.name}… queued files will appear in Downloads.`,
    color: 'info', timeout: 2500
  })
  const result = await downloadStore.enqueueFolder({ share, folderPath: fullPath })
  if (result?.error) {
    $q.notify({ message: `Folder download failed: ${result.error}`, color: 'negative', timeout: 3500 })
    return
  }
  $q.notify({
    message: result.enqueued > 0
      ? `Queued ${result.enqueued} file${result.enqueued === 1 ? '' : 's'} from ${file.name}`
      : `No files to download in ${file.name}`,
    color: result.enqueued > 0 ? 'positive' : 'warning',
    textColor: 'dark', timeout: 2500
  })
}
</script>

<style lang="scss" scoped>
.file-browser {
  // Share switcher + trailing action icons share a single row.  Replaces
  // the old "third row" (search bar + view/sort), which is now a single
  // search icon in the header.
  &__shelf-row {
    display: flex;
    align-items: center;
    gap: 4px;
    padding-right: 8px;
    border-bottom: 1px solid var(--rule-soft);
  }

  &__switcher {
    flex: 1;
    min-width: 0;
    border-bottom: none !important;  // parent row owns the divider now
  }

  &__shelf-actions {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 2px;
    padding-bottom: 2px;
  }

  &__icon-btn {
    position: relative;
    width: var(--tap-min);
    height: var(--tap-min);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    transition: background var(--dur-fast) var(--ease-archive);

    &:active { background: var(--ink-2); }
  }

  &__count {
    padding: 18px 20px 10px;
    color: var(--paper-dim);
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
    padding: 8px 20px 24px;

    @media (min-width: 480px) {
      grid-template-columns: repeat(3, 1fr);
    }
    @media (min-width: 720px) {
      grid-template-columns: repeat(4, 1fr);
    }
  }
}

.auth-form {
  width: 100%;
  max-width: 340px;
  margin: 0 auto;

  &__input {
    :deep(.q-field__control) {
      background: transparent !important;
      border: none !important;
      border-bottom: 1px solid var(--rule) !important;
      border-radius: 0 !important;
      padding: 0 4px;
      min-height: 44px;
    }
    :deep(.q-field__control:before),
    :deep(.q-field__control:after) { border: none !important; box-shadow: none !important; }
    :deep(.q-field--focused .q-field__control) {
      border-bottom-color: var(--marigold) !important;
    }
    :deep(.q-field__native) {
      color: var(--paper) !important;
      font-family: var(--font-body);
      font-size: 15px;
      &::placeholder { color: var(--paper-dim) !important; }
    }
  }

  &__persist {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    cursor: pointer;
  }
}

.full-width { width: 100%; }
</style>
