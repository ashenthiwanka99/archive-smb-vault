<template>
  <q-page class="gallery-page">
    <q-pull-to-refresh @refresh="onPullRefresh" color="primary" bg-color="transparent" icon-color="primary">
    <!-- Hero -->
    <header class="gallery-page__hero staircase">
      <div class="caption-sc">Gallery</div>
      <h1 class="gallery-page__title display-xl">Private backup.</h1>
    </header>

    <!-- Status card -->
    <BackupStatusCard
      :state="statusState"
      :title="statusTitle"
      :subtitle="statusSubtitle"
      :cta-label="ctaLabel"
      :cta-icon="ctaIcon"
      :cta-disabled="ctaDisabled"
      @action="handleCta"
    />

    <!-- Live upload panel -->
    <BackupProgressPanel
      v-if="isWorking"
      :current="backupStore.currentIndex"
      :total="backupStore.totalFiles"
      :file-name="backupStore.currentFile"
      :phase="backupStore.workPhase"
      :dest-name="destinationShelfName"
      :eta-seconds="backupStore.etaSeconds"
      @stop="stopIntake"
    />

    <!-- Shelf storage -->
    <section v-if="settingsStore.shares.length > 0" class="gallery-page__section">
      <div class="storage-card">
        <div class="storage-card__head">
          <div>
            <div class="caption-sc storage-card__label">Shelf storage</div>
            <div class="storage-card__title">
              {{ backupStore.statsLoading && backupStore.statsUpdatedAt === 0
                  ? 'reading…'
                  : formatSize(backupStore.backupSize) }}
            </div>
            <div class="storage-card__meta">
              held by backup · {{ backupStore.backupFileCount.toLocaleString('en-US') }} files
            </div>
          </div>
          <button
            class="btn-ghost storage-card__refresh"
            :disabled="backupStore.statsLoading"
            @click="backupStore.refreshStorageStats()"
            aria-label="Refresh storage"
          >
            <q-icon name="mdi-refresh" size="16px" />
          </button>
        </div>

        <div class="storage-card__bar">
          <div class="storage-card__bar-fill" :style="{ width: (storageUsedPct * 100) + '%' }" />
        </div>

        <div class="storage-card__legend">
          <div class="storage-card__legend-row">
            <span class="storage-card__legend-dot storage-card__legend-dot--used" />
            <span class="storage-card__legend-key">backup uses</span>
            <span class="storage-card__legend-val tabular">{{ formatSize(backupStore.backupSize) }}</span>
          </div>
          <div class="storage-card__legend-row">
            <span class="storage-card__legend-dot storage-card__legend-dot--free" />
            <span class="storage-card__legend-key">free on shelf</span>
            <span class="storage-card__legend-val tabular">
              {{ backupStore.shelfFreeBytes > 0 ? formatSize(backupStore.shelfFreeBytes) : '—' }}
            </span>
          </div>
        </div>

        <button
          class="storage-card__manage"
          @click="openStorage"
          aria-label="Manage storage"
        >
          <span>Manage storage</span>
          <q-icon
            v-if="backupStore.cleanupEligible > 0"
            name="mdi-circle-medium"
            size="14px"
            class="storage-card__manage-badge"
            aria-label="Cleanup queue has eligible items"
          />
          <q-icon name="mdi-chevron-right" size="16px" />
        </button>
      </div>
    </section>

    <!-- Backup activity (collapsed: sparkline + 3 latest runs; full view lives at /activity) -->
    <BackupHistoryCard
      :history="backupStore.history"
      :loading="false"
      :max-rows="3"
      @clear="backupStore.clearHistory()"
    />
    <div v-if="backupStore.history.length > 0" class="gallery-page__activity-link">
      <button class="btn-link" @click="openActivity">
        View all activity
        <q-icon name="mdi-chevron-right" size="14px" />
      </button>
    </div>

    <!-- Source folders -->
    <section v-if="galleryStore.byFolder.length > 0 || galleryStore.loading" class="gallery-page__section">
      <div class="section-head">
        <div class="section-head__title">
          <span class="caption-sc">Source folders</span>
        </div>
        <div class="section-head__actions">
          <button
            v-if="anyUnselected"
            class="btn-link"
            @click="selectAllFolders"
          >
            Select all
          </button>
          <button
            v-if="backupStore.selectedFolders.length > 0"
            class="btn-link"
            @click="clearFolderSelection"
          >
            Clear
          </button>
        </div>
      </div>

      <div class="section-head__hint body-sm body-mute">
        {{ folderSelectionHint }}
      </div>

      <q-input
        v-if="showSearch"
        v-model="folderFilter"
        dense borderless
        placeholder="Search folders"
        autocapitalize="none"
        autocorrect="off"
        spellcheck="false"
        class="folder-search"
      >
        <template #prepend>
          <q-icon name="mdi-magnify" size="var(--icon-sm)" class="paper-dim" />
        </template>
        <template #append>
          <q-icon
            v-if="folderFilter"
            name="mdi-close"
            size="var(--icon-xs)"
            class="cursor-pointer paper-dim"
            aria-label="Clear search"
            @click="folderFilter = ''"
          />
        </template>
      </q-input>

      <!-- Loading skeleton -->
      <div v-if="galleryStore.loading && galleryStore.byFolder.length === 0" class="stack">
        <div v-for="i in 4" :key="i" class="skeleton-shimmer" style="height: 64px;" />
      </div>

      <div v-else class="folder-list">
        <ShelfRow
          v-for="shelf in filteredFolders"
          :key="shelf.folderPath"
          :shelf="decorateShelf(shelf)"
          :selected="isFolderSelected(shelf)"
          @toggle="onShelfToggle"
        />
        <div v-if="folderFilter && filteredFolders.length === 0" class="folder-empty">
          No folders match "{{ folderFilter }}".
        </div>
      </div>
    </section>

    <!-- Empty media state -->
    <div
      v-else-if="!galleryStore.loading && galleryStore.totalCount === 0"
      class="empty-state"
    >
      <div class="empty-state__eyebrow">Nothing to back up</div>
      <div class="empty-state__title">No photos found.</div>
      <div class="empty-state__subtitle">
        Grant media access to populate this list.
      </div>
      <button class="btn-ghost q-mt-md" @click="grantMediaAccess">
        grant access
      </button>
    </div>

    <!-- Inline setup: 2 primary controls + advanced link. No routing. -->
    <section v-if="settingsStore.shares.length > 0" class="gallery-page__section">
      <div class="caption-sc section-head__title-only">Setup</div>
      <div class="setup-card">
        <div class="setup-card__row">
          <div class="setup-card__body">
            <div class="setup-card__title">Auto-backup</div>
            <div class="setup-card__sub">{{ backupStore.enabled ? 'Running in the background' : 'Off — start backups manually' }}</div>
          </div>
          <q-toggle v-model="backupStore.enabled" color="primary" />
        </div>

        <button class="setup-card__row setup-card__row--link" @click="editDestination">
          <div class="setup-card__body">
            <div class="setup-card__title">Destination</div>
            <div class="setup-card__sub tabular">
              {{ destinationShelfName || 'Not selected' }}<span v-if="backupStore.backupPath"> · {{ backupStore.backupPath }}</span>
            </div>
          </div>
          <q-icon name="mdi-pencil-outline" size="var(--icon-sm)" class="paper-dim" />
        </button>

        <button class="setup-card__row setup-card__row--link" @click="advancedOpen = true">
          <div class="setup-card__body">
            <div class="setup-card__title">Advanced options</div>
            <div class="setup-card__sub">{{ advancedSummary }}</div>
          </div>
          <q-icon name="mdi-chevron-right" size="var(--icon-md)" class="paper-dim" />
        </button>
      </div>
    </section>

    <!-- Advanced settings sheet -->
    <BackupSettingsSheet v-model="advancedOpen" />

    <!-- Recent additions -->
    <section v-if="galleryStore.recent.length > 0" class="gallery-page__section">
      <div class="caption-sc section-head__title-only">Pending backup</div>
      <div class="recent-list">
        <div
          v-for="item in galleryStore.recent.slice(0, 5)"
          :key="item.id"
          class="recent-row"
        >
          <q-icon
            :name="item.type === 'video' ? 'mdi-filmstrip' : 'mdi-image-outline'"
            size="16px"
            class="paper-dim recent-row__icon"
          />
          <div class="recent-row__name">{{ item.name }}</div>
          <div class="recent-row__meta tabular">
            {{ formatShortDate(item.dateAdded) }} · {{ formatSize(item.size) }}
          </div>
        </div>
      </div>
    </section>
    </q-pull-to-refresh>
  </q-page>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useGalleryStore } from '../stores/gallery'
import { useBackupStore } from '../stores/backup'
import { useSettingsStore } from '../stores/settings'
import { formatSize } from '../utils/helpers'
import BackupStatusCard from '../components/BackupStatusCard.vue'
import BackupHistoryCard from '../components/BackupHistoryCard.vue'
import BackupProgressPanel from '../components/BackupProgressPanel.vue'
import BackupSettingsSheet from '../components/BackupSettingsSheet.vue'
import ShelfRow from '../components/ShelfRow.vue'

const $q = useQuasar()
const router = useRouter()
const galleryStore = useGalleryStore()
const backupStore = useBackupStore()
const settingsStore = useSettingsStore()

const folderFilter = ref('')
const permsDenied = ref(false)
const advancedOpen = ref(false)
function openStorage() {
  router.push({ name: 'storage' })
}

function openActivity() {
  router.push({ name: 'activity' })
}

// -----------------------------
// Derived state
// -----------------------------

const destinationShelfName = computed(() => {
  const share = settingsStore.shares.find(s => s.id === backupStore.backupShareId)
  return share?.name || (settingsStore.shares[0]?.name || '')
})

const advancedSummary = computed(() => {
  const parts = []
  const types = []
  if (backupStore.includePhotos) types.push('Photos')
  if (backupStore.includeVideos) types.push('Videos')
  parts.push(types.length ? types.join(' + ') : 'Nothing selected')
  if (backupStore.wifiOnly) parts.push('Wi-Fi only')
  return parts.join(' · ')
})

function editDestination() {
  $q.dialog({
    title: 'Destination path',
    prompt: {
      model: backupStore.backupPath,
      type: 'text',
      label: 'Relative path on the shelf',
      isValid: v => typeof v === 'string' && v.trim().replace(/^[/\\]+/, '').length > 0
    },
    cancel: true
  }).onOk(path => {
    const trimmed = (path || '').trim().replace(/^[/\\]+/, '')
    if (!trimmed) {
      $q.notify({
        message: 'Destination cannot be empty — photos would dump into the share root.',
        color: 'negative', timeout: 3000
      })
      return
    }
    const ok = backupStore.setBackupPath(trimmed)
    if (!ok) {
      $q.notify({ message: backupStore.backupError || 'Invalid path', color: 'negative', timeout: 3000 })
    }
  })
}

const isWorking = computed(() =>
  backupStore.backupInProgress || backupStore.isWorkRunning || backupStore.isWorkQueued
)

const canIntake = computed(() =>
  !isWorking.value &&
  settingsStore.shares.length > 0 &&
  (backupStore.includePhotos || backupStore.includeVideos)
)

const pendingCount = computed(() => galleryStore.pendingCount)

const statusState = computed(() => {
  if (settingsStore.shares.length === 0) return 'no-shares'
  if (permsDenied.value) return 'no-permission'
  if (backupStore.backupError) return 'error'
  if (backupStore.isWorkRunning) return 'uploading'
  if (backupStore.isWorkQueued) return 'queued'
  // Explicit opt-in: no folders picked = nothing to back up.  Distinct from
  // "up-to-date" which should only appear after at least one run has
  // completed.
  if (!backupStore.selectedFolders || backupStore.selectedFolders.length === 0) {
    return 'nothing-selected'
  }
  // Has folders, but the user hasn't actually backed anything up yet.
  if (!backupStore.lastBackupAt && backupStore.backedUpCount === 0) {
    return pendingCount.value > 0 ? 'pending' : 'nothing-selected'
  }
  if (pendingCount.value === 0 && galleryStore.totalCount > 0) return 'up-to-date'
  if (pendingCount.value > 0) return 'pending'
  return 'up-to-date'
})

const statusTitle = computed(() => {
  switch (statusState.value) {
    case 'uploading':
      // The worker reports distinct phases; we relabel so the user knows
      // what it's actually doing right now.
      if (backupStore.workPhase === 'scanning') return 'Checking shelf for duplicates…'
      return 'Backing up…'
    case 'queued':    return backupStore.wifiOnly ? 'Waiting for Wi-Fi' : 'Queued'
    case 'up-to-date': return 'Up to date.'
    case 'pending':   return `${pendingCount.value.toLocaleString('en-US')} awaiting backup`
    case 'nothing-selected': return 'Nothing picked yet'
    case 'no-shares': return 'No shelf configured'
    case 'no-permission': return 'Media access blocked'
    case 'error':     return backupStore.backupError || 'Something went wrong.'
  }
  return ''
})

const statusSubtitle = computed(() => {
  if (statusState.value === 'no-shares') return 'Add a shelf in settings to begin.'
  if (statusState.value === 'no-permission') return 'Grant photos + videos access to scan your device.'
  if (statusState.value === 'error') return 'Tap retry to try again.'
  if (statusState.value === 'nothing-selected') {
    return (backupStore.selectedFolders?.length || 0) === 0
      ? 'Pick source folders below to start backing up.'
      : 'No photos detected in the selected folders yet.'
  }

  // While uploading, show the live native counter so the card refreshes as
  // files flow through, not just after the run finishes.
  if (statusState.value === 'uploading') {
    const cur = backupStore.currentIndex || 0
    const tot = backupStore.totalFiles || 0
    if (tot > 0) {
      const name = backupStore.currentFile || '…'
      const eta = backupStore.etaSeconds
      const etaStr = eta > 0 ? ` · ${formatEta(eta)} left` : ''
      return `${cur.toLocaleString('en-US')} of ${tot.toLocaleString('en-US')} · ${name}${etaStr}`
    }
    return 'Starting…'
  }

  const parts = []
  if (galleryStore.totalCount > 0) {
    parts.push(`${galleryStore.backedUpCount.toLocaleString('en-US')} / ${galleryStore.totalCount.toLocaleString('en-US')} backed up`)
  }
  if (backupStore.lastBackupAt) {
    parts.push(`last backup ${formatRelativeTime(backupStore.lastBackupAt)}`)
  } else if (galleryStore.totalCount > 0 && statusState.value !== 'uploading') {
    parts.push('never backed up')
  }
  return parts.join(' · ')
})

const ctaLabel = computed(() => {
  switch (statusState.value) {
    case 'no-shares':        return 'Open settings'
    case 'no-permission':    return 'Grant access'
    case 'error':            return 'Retry'
    case 'pending':          return 'Back up now'
    case 'queued':           return 'Stop'
    case 'nothing-selected': return ''
    case 'up-to-date':       return ''
  }
  return ''
})

const ctaIcon = computed(() => {
  switch (statusState.value) {
    case 'pending':       return 'mdi-cloud-upload-outline'
    case 'queued':        return 'mdi-stop-circle-outline'
    case 'no-shares':     return 'mdi-cog-outline'
    case 'no-permission': return 'mdi-lock-open-outline'
    case 'error':         return 'mdi-refresh'
  }
  return ''
})

const ctaDisabled = computed(() => {
  if (statusState.value === 'pending' && !canIntake.value) return true
  return false
})

// Folder list
const showSearch = computed(() => galleryStore.byFolder.length > 6)

const filteredFolders = computed(() => {
  if (!folderFilter.value.trim()) return galleryStore.byFolder
  const q = folderFilter.value.toLowerCase()
  return galleryStore.byFolder.filter(s => s.label.toLowerCase().includes(q))
})

const anyUnselected = computed(() => {
  return galleryStore.byFolder.some(s => !backupStore.isFolderSelected(s.folderPath))
})

const folderSelectionHint = computed(() => {
  const n = backupStore.selectedFolders.length
  const total = galleryStore.byFolder.length
  if (n === 0) return `${total} folders found · pick at least one to back up`
  return `${n} of ${total} picked · only these will be backed up`
})

// Shelf storage bar
const storageUsedPct = computed(() => {
  const used = backupStore.backupSize
  const free = backupStore.shelfFreeBytes
  const total = used + free
  if (total <= 0) return 0
  return Math.max(0.01, Math.min(1, used / total))
})

// -----------------------------
// Actions
// -----------------------------

function decorateShelf(shelf) {
  // Augment the raw byFolder bucket with a preformatted size label the row
  // component can render without pulling in helpers.
  return { ...shelf, sizeLabel: shelf.totalSize ? formatSize(shelf.totalSize) : '' }
}

function isFolderSelected(shelf) {
  return backupStore.isFolderSelected(shelf.folderPath)
}

function onShelfToggle(shelf) {
  if (!shelf.folderPath) return
  backupStore.toggleFolder(shelf.folderPath)
}

function selectAllFolders() {
  backupStore.selectedFolders = galleryStore.byFolder.map(s => s.folderPath).filter(Boolean)
}

function clearFolderSelection() {
  backupStore.selectedFolders = []
}

async function grantMediaAccess() {
  const ok = await backupStore.ensureMediaPermissions()
  permsDenied.value = !ok
  if (ok) await galleryStore.refresh()
}

async function handleCta() {
  switch (statusState.value) {
    case 'no-shares':
      router.push('/settings')
      return
    case 'no-permission':
      await grantMediaAccess()
      return
    case 'queued':
      await stopIntake()
      return
    case 'pending':
    case 'error':
    case 'up-to-date':
      await beginIntake()
      return
  }
}

async function beginIntake() {
  if (!canIntake.value) return
  if (!backupStore.backupShareId && settingsStore.shares.length > 0) {
    backupStore.backupShareId = settingsStore.shares[0].id
  }
  if (!backupStore.enabled) backupStore.enabled = true

  const net = await backupStore.checkNetworkForBackup()
  let force = false
  if (!net.ok && net.reason === 'mobile-data') {
    const proceed = await new Promise(resolve => {
      $q.dialog({
        title: 'On mobile data',
        message: 'Wi-Fi only is enabled and you\'re on mobile data. Start anyway (this will use your data)?',
        cancel: { label: 'Cancel', flat: true },
        ok: { label: 'Use mobile data', color: 'warning' }
      }).onOk(() => resolve(true)).onCancel(() => resolve(false)).onDismiss(() => resolve(false))
    })
    if (!proceed) {
      $q.notify({ message: 'Intake cancelled — waiting for Wi-Fi.', color: 'info', timeout: 2500 })
      return
    }
    force = true
  } else if (!net.ok && net.reason === 'no-network') {
    $q.notify({ message: 'No network connection.', color: 'negative', timeout: 2500 })
    return
  }

  await backupStore.startBackupNow({ forceOverMobile: force })
  if (backupStore.backupError) {
    $q.notify({ message: backupStore.backupError, color: 'negative', timeout: 3500 })
    return
  }
  setTimeout(() => galleryStore.refresh(), 2500)
  setTimeout(() => backupStore.refreshStorageStats(), 8000)
}

async function stopIntake() {
  await backupStore.stopBackup()
  $q.notify({ message: 'Stopping — in-flight file will finish.', color: 'info', timeout: 2500 })
}

// -----------------------------
// Helpers
// -----------------------------

function formatRelativeTime(ts) {
  if (!ts) return 'never'
  const diff = Date.now() - ts
  const m = Math.floor(diff / 60000)
  if (m < 1) return 'just now'
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  if (d < 7) return `${d}d ago`
  return new Date(ts).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}

function formatEta(seconds) {
  if (!seconds || seconds < 0) return ''
  if (seconds < 60) return `~${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    const s = seconds % 60
    return s > 0 && minutes < 10 ? `~${minutes}m ${s}s` : `~${minutes}m`
  }
  const hours = Math.floor(minutes / 60)
  const mRem = minutes % 60
  if (hours < 24) return mRem > 0 ? `~${hours}h ${mRem}m` : `~${hours}h`
  const days = Math.floor(hours / 24)
  return `~${days}d`
}

function formatShortDate(ts) {
  if (!ts) return '—'
  return new Date(ts).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}

// -----------------------------
// Lifecycle
// -----------------------------

let galleryRefreshTimer = null

async function onPullRefresh(done) {
  try {
    await Promise.all([
      galleryStore.refresh(),
      backupStore.refreshStatus(),
      backupStore.refreshStorageStats()
    ])
  } finally {
    done()
  }
}

onMounted(async () => {
  // Preflight media permission check so statusState can reflect denial up-front.
  if (settingsStore.shares.length > 0) {
    try {
      const perm = await backupStore.ensureMediaPermissions()
      permsDenied.value = !perm
    } catch { permsDenied.value = false }
  }
  galleryStore.refresh()
  // Always refresh so the authoritative backed-up-id set is in memory before
  // the status card's pending calc runs — stale empty set would show every
  // item as pending on first paint.
  backupStore.refreshStatus()
  backupStore.refreshHistory()
  backupStore.refreshCleanupQueue()
  if (backupStore.backupShareId) backupStore.refreshStorageStats()
  backupStore.startWorkStatusPolling()

  // Low-frequency gallery re-scan so "Recently added" picks up any new
  // photos taken while the user has the page open without requiring a
  // manual pull.  30 s is cheap and off the main thread.
  galleryRefreshTimer = setInterval(() => { galleryStore.refresh() }, 30_000)
})

onBeforeUnmount(() => {
  backupStore.stopWorkStatusPolling()
  if (galleryRefreshTimer) {
    clearInterval(galleryRefreshTimer)
    galleryRefreshTimer = null
  }
})
</script>

<style lang="scss" scoped>
.gallery-page {
  padding-bottom: 96px;

  &__hero {
    padding: 32px 20px 20px;
  }

  &__title {
    margin: 8px 0 20px;
    color: var(--paper);
  }

  &__section {
    padding: 22px 20px 2px;
  }

  &__activity-link {
    padding: 6px 20px 16px;
    display: flex;
    justify-content: flex-end;
  }
}

.setup-card {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;

  &__row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 16px;
    border-top: 1px solid var(--rule-soft);
    background: transparent;
    border-left: none;
    border-right: none;
    border-bottom: none;
    text-align: left;
    color: inherit;
    font: inherit;

    &:first-child { border-top: none; }

    &--link {
      cursor: pointer;
      transition: background var(--dur-fast) var(--ease-archive);
      &:hover { background: var(--ink-2); }
      &:active { background: var(--ink-2); }
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    line-height: 1.3;
  }

  &__sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 3px;
    line-height: 1.4;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;

  &__title {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__title-only {
    color: var(--paper-dim);
    margin-bottom: 8px;
  }

  &__actions {
    display: flex;
    gap: 4px;
  }

  &__btn {
    min-height: 28px;
    padding: 0 10px;
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  &__hint {
    margin-bottom: 10px;
  }
}

.folder-search {
  margin: 8px 0 10px;

  :deep(.q-field__control) {
    background: var(--ink-1) !important;
    border: 1px solid var(--rule) !important;
    border-radius: var(--r-sm) !important;
    padding: 0 12px;
    min-height: 36px;
  }
  :deep(.q-field__control:before),
  :deep(.q-field__control:after) {
    border: none !important;
    box-shadow: none !important;
  }
  :deep(.q-field--focused .q-field__control) {
    border-color: var(--paper-mute) !important;
  }
  :deep(.q-field__native) {
    color: var(--paper) !important;
    font-family: var(--font-body);
    font-size: 13px;
  }
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 2px;
}

.folder-empty {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--paper-dim);
  padding: 18px 4px;
  text-align: center;
}

.options-list {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--rule-soft);
}

.options-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 4px;
  border-bottom: 1px solid var(--rule-soft);
  background: transparent;
  border-left: none;
  border-right: none;
  border-top: none;
  text-align: left;
  cursor: default;

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    line-height: 1.3;
  }

  &__sub {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
    margin-top: 4px;
  }

  &--link {
    cursor: pointer;
    transition: background var(--dur-fast) var(--ease-archive);
    &:active { background: var(--ink-1); }
  }
}

.recent-list {
  border-top: 1px solid var(--rule-soft);
}

.recent-row {
  display: grid;
  grid-template-columns: 18px 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule-soft);

  &__name {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
  }
}

.storage-card {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 20px 20px 18px;

  &__manage {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    width: 100%;
    margin-top: 16px;
    padding: 10px 12px;
    background: var(--ink-0);
    border: 1px solid var(--rule);
    border-radius: var(--r-sm);
    font-family: var(--font-body);
    font-size: 13px;
    font-weight: 500;
    color: var(--paper);
    cursor: pointer;
    transition: border-color var(--dur-fast) var(--ease-archive),
                background var(--dur-fast) var(--ease-archive);

    &:hover { border-color: var(--paper-mute); }
    &:active { background: var(--ink-2); }
  }

  &__manage-badge {
    margin-left: -6px;
    color: var(--marigold);
  }

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
  }

  &__label { color: var(--paper-dim); margin-bottom: 6px; }

  &__title {
    font-family: var(--font-display);
    font-weight: 400;
    font-size: clamp(26px, 7vw, 36px);
    line-height: 1;
    letter-spacing: -0.02em;
    color: var(--paper);
    font-variant-numeric: tabular-nums;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-mute);
    letter-spacing: 0.04em;
    margin-top: 8px;
  }

  &__refresh {
    min-height: 32px;
    padding: 0 10px;
    flex-shrink: 0;
    color: var(--paper-mute);
  }

  &__bar {
    height: 6px;
    background: var(--ink-2);
    border-radius: 3px;
    overflow: hidden;
    margin-bottom: 14px;
  }

  &__bar-fill {
    height: 100%;
    background: linear-gradient(90deg, var(--marigold), #F4BE66);
    transition: width 0.4s var(--ease-archive);
  }

  &__legend {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  &__legend-row {
    display: flex;
    align-items: center;
    gap: 10px;
    font-family: var(--font-body);
    font-size: 12px;
  }

  &__legend-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    &--used { background: var(--marigold); }
    &--free { background: transparent; border: 1px solid var(--rule); }
  }

  &__legend-key {
    color: var(--paper-dim);
    letter-spacing: 0.04em;
    flex: 1;
  }

  &__legend-val {
    color: var(--paper);
    font-variant-numeric: tabular-nums;
  }
}
</style>
