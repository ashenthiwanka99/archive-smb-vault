<template>
  <q-page class="storage-page">
    <div class="storage-page__statusbar-pad safe-top" />
    <q-toolbar class="storage-page__toolbar">
      <button class="storage-page__icon-btn" aria-label="Back" @click="goBack">
        <q-icon name="mdi-arrow-left" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="storage-page__title">Storage</q-toolbar-title>
      <button
        class="storage-page__icon-btn"
        :disabled="backupStore.statsLoading"
        aria-label="Refresh"
        @click="backupStore.refreshStorageStats()"
      >
        <q-icon name="mdi-refresh" size="var(--icon-md)" class="paper-mute" />
      </button>
    </q-toolbar>

    <div class="storage-page__body">
      <!-- Shelf picker: only shown when the user has multiple configured. -->
      <section v-if="settingsStore.shares.length > 1" class="storage-page__section">
        <div class="shelf-picker">
          <div class="shelf-picker__label caption-sc">Shelf</div>
          <q-select
            v-model="shareId"
            :options="shareOptions"
            emit-value map-options dense borderless
            class="shelf-picker__select"
          />
        </div>
      </section>

      <!-- Main storage card (full head + bar + legend, no tools row). -->
      <section class="storage-page__section">
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
        </div>
      </section>

      <!-- Tools: find duplicates, cleanup queue. -->
      <section class="storage-page__section">
        <div class="storage-page__tools">
          <button
            class="tool-row"
            @click="openDuplicates"
          >
            <q-icon name="mdi-content-duplicate" size="22px" class="tool-row__icon" />
            <div class="tool-row__body">
              <div class="tool-row__title">Find duplicates</div>
              <div class="tool-row__sub caption-sc">
                Scan this shelf for identical files and reclaim space.
              </div>
            </div>
            <q-icon name="mdi-chevron-right" size="18px" class="tool-row__chevron" />
          </button>

          <button
            class="tool-row"
            @click="openCleanup"
          >
            <q-icon name="mdi-broom" size="22px" class="tool-row__icon" />
            <div class="tool-row__body">
              <div class="tool-row__title">
                Cleanup queue
                <span
                  v-if="backupStore.cleanupQueue.length > 0"
                  class="tool-row__count tabular"
                >· {{ backupStore.cleanupQueue.length }}</span>
              </div>
              <div class="tool-row__sub caption-sc">
                <template v-if="backupStore.cleanupEligible > 0">
                  <span class="tool-row__badge">{{ backupStore.cleanupEligible }} ready</span>
                  to delete from phone
                </template>
                <template v-else-if="backupStore.cleanupQueue.length > 0">
                  All entries still in grace
                </template>
                <template v-else>
                  Empty — enable "Delete from phone after backup" in Advanced
                </template>
              </div>
            </div>
            <q-icon name="mdi-chevron-right" size="18px" class="tool-row__chevron" />
          </button>
        </div>
      </section>

      <!-- Per-folder breakdown. -->
      <section class="storage-page__section">
        <div class="section-head">
          <div class="section-head__title">
            <span class="caption-sc">What's in this shelf</span>
          </div>
        </div>
        <StorageBreakdown
          v-if="breakdownShareId"
          :share-id="breakdownShareId"
          :path="backupStore.backupPath || ''"
        />
      </section>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useBackupStore } from '../stores/backup'
import { useSettingsStore } from '../stores/settings'
import StorageBreakdown from '../components/StorageBreakdown.vue'
import { formatSize } from '../utils/helpers'

const router = useRouter()
const backupStore = useBackupStore()
const settingsStore = useSettingsStore()

// Shelf picker default — match the Gallery card's fallback logic so the
// page always renders useful data even before backup is configured.
const shareId = ref(
  backupStore.backupShareId || settingsStore.shares[0]?.id || ''
)

const shareOptions = computed(() =>
  settingsStore.shares.map(s => ({ label: s.name, value: s.id }))
)

// `breakdownShareId` reads from the picker when present, otherwise falls
// back (same pattern the Gallery card used before the refactor).
const breakdownShareId = computed(() =>
  shareId.value || backupStore.backupShareId || settingsStore.shares[0]?.id || ''
)

// Same ratio math the Gallery card used — used / (used + free), clamped
// to [0, 1].  Kept local so the page can evolve independently.
const storageUsedPct = computed(() => {
  const used = backupStore.backupSize
  const free = backupStore.shelfFreeBytes
  const total = used + free
  if (total <= 0) return 0
  return Math.max(0, Math.min(1, used / total))
})

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/gallery')
}

function openDuplicates() { router.push({ name: 'duplicates' }) }
function openCleanup() { router.push({ name: 'cleanup' }) }

onMounted(() => {
  backupStore.refreshCleanupQueue()
  backupStore.refreshStorageStats()
})

// Keep the backup store's target share in sync if the user picks a
// different shelf from the picker — the breakdown + stats refresh follow.
watch(shareId, (id) => {
  if (id && id !== backupStore.backupShareId) {
    backupStore.backupShareId = id
    backupStore.refreshStorageStats()
  }
})
</script>

<style lang="scss" scoped>
.storage-page {
  min-height: 100vh;
  background: var(--ink-0);

  &__statusbar-pad { background: var(--ink-0); width: 100%; }

  &__toolbar {
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
    min-height: 56px;
    padding: 0 8px;
    gap: 4px;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    padding-left: 8px;
  }

  &__icon-btn {
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
    &:active { background: var(--ink-2); }
    &:disabled { opacity: 0.4; }
  }

  &__body { padding: 16px; }
  &__section { margin-bottom: 20px; }

  &__tools {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
}

.shelf-picker {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 12px;

  &__label {
    color: var(--paper-mute);
    letter-spacing: 0.08em;
    width: 60px;
    flex-shrink: 0;
  }

  &__select {
    flex: 1;
    :deep(.q-field__native) {
      color: var(--paper);
      font-family: var(--font-body);
      font-size: 13px;
    }
  }
}

// Storage card chrome re-used from Gallery, kept local here so the two can
// evolve independently without coupling.
.storage-card {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 20px 20px 18px;

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
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 6px;
  }

  &__bar {
    width: 100%;
    height: 6px;
    background: var(--ink-0);
    border-radius: 3px;
    overflow: hidden;
    margin-bottom: 14px;
  }

  &__bar-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 400ms var(--ease-archive);
  }

  &__legend {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__legend-row {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__legend-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    &--used { background: var(--marigold); }
    &--free { background: var(--paper-dim); }
  }

  &__legend-key {
    flex: 1;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
  }

  &__legend-val {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper);
  }
}

.tool-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease-archive),
              background var(--dur-fast) var(--ease-archive);
  width: 100%;
  text-align: left;

  &:hover { border-color: var(--paper-mute); }
  &:active { background: var(--ink-2); }

  &__icon {
    color: var(--paper);
    flex-shrink: 0;
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
  }

  &__count {
    color: var(--paper-mute);
    font-weight: 400;
    font-size: 12px;
    margin-left: 2px;
  }

  &__sub {
    color: var(--paper-mute);
    margin-top: 2px;
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    padding: 1px 6px;
    border-radius: 8px;
    background: var(--marigold);
    color: var(--ink-0);
    font-family: var(--font-body);
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.04em;
    margin-right: 2px;
  }

  &__chevron {
    color: var(--paper-dim);
    flex-shrink: 0;
  }
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;

  &__title .caption-sc {
    color: var(--paper-mute);
    letter-spacing: 0.12em;
  }
}
</style>
