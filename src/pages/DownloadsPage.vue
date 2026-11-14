<template>
  <q-page class="downloads-page">
    <header class="page-head">
      <div class="page-head__body">
        <div class="caption-sc">Downloads</div>
        <h1 class="page-head__title">Fetched files</h1>
        <div class="page-head__sub">
          {{ downloadStore.queue.length }} active · {{ downloadStore.history.length }} in history
        </div>
      </div>
      <div v-if="downloadStore.history.length" class="page-head__actions">
        <button class="btn-danger-ghost downloads-page__clear" @click="confirmClear">
          Clear history
        </button>
      </div>
    </header>

    <q-pull-to-refresh @refresh="onRefresh" color="primary" bg-color="transparent" icon-color="primary">
      <!-- Uploads (WorkManager-backed, survives app kills) -->
      <section v-if="uploadsStore.items.length > 0 || uploadsStore.running" class="downloads-page__section">
        <div class="downloads-page__section-head">
          <div class="caption-sc downloads-page__label">Uploads</div>
          <button
            v-if="uploadsStore.running || uploadsStore.queued"
            class="btn-link"
            @click="uploadsStore.stopAll()"
          >
            Stop all
          </button>
        </div>

        <!-- Batch summary -->
        <div class="upload-summary">
          <div class="upload-summary__bar">
            <div
              class="upload-summary__bar-fill"
              :style="{ width: overallPct + '%' }"
            />
          </div>
          <div v-if="uploadsStore.etaSeconds > 0 && uploadsStore.running" class="upload-summary__eta">
            About {{ formatEta(uploadsStore.etaSeconds) }} remaining
          </div>
          <div class="upload-summary__stats">
            <span class="upload-summary__stat">
              <q-icon name="mdi-cloud-upload" size="14px" class="marigold" />
              <span class="tabular">{{ uploadsStore.uploadingCount }}</span>
              <span class="paper-dim">uploading</span>
            </span>
            <span class="upload-summary__stat">
              <q-icon name="mdi-clock-outline" size="14px" class="paper-mute" />
              <span class="tabular">{{ uploadsStore.queuedCount }}</span>
              <span class="paper-dim">queued</span>
            </span>
            <span class="upload-summary__stat">
              <q-icon name="mdi-check-circle-outline" size="14px" class="moss" />
              <span class="tabular moss">{{ uploadsStore.sessionUploadedCount }}</span>
              <span class="paper-dim">done</span>
            </span>
            <span v-if="uploadsStore.sessionFailedCount > 0 || uploadsStore.failedCount > 0" class="upload-summary__stat">
              <q-icon name="mdi-alert-circle-outline" size="14px" class="rust" />
              <span class="tabular rust">{{ Math.max(uploadsStore.failedCount, uploadsStore.sessionFailedCount) }}</span>
              <span class="paper-dim">failed</span>
            </span>
          </div>
        </div>

        <div class="upload-item" v-for="item in uploadsStore.items" :key="item.id">
          <q-icon
            :name="item.status === 'failed' ? 'mdi-alert-circle-outline'
                  : item.status === 'uploading' ? 'mdi-cloud-upload'
                  : 'mdi-cloud-upload-outline'"
            size="var(--icon-md)"
            :class="item.status === 'failed' ? 'rust' : item.status === 'uploading' ? 'marigold' : 'paper-mute'"
            class="upload-item__icon"
          />
          <div class="upload-item__body">
            <div class="upload-item__name">{{ item.name }}</div>
            <div class="upload-item__meta tabular">
              {{ formatSize(item.size) }}
              <span v-if="item.status === 'queued'" class="paper-dim"> · awaiting</span>
              <span v-if="item.status === 'uploading'" class="marigold">
                · {{ itemBytesFor(item) ? `${formatSize(itemBytesFor(item))} / ${formatSize(item.size)}` : 'uploading…' }}
                <span v-if="itemBytesFor(item) && item.size > 0" class="paper-dim"> ({{ itemPercentFor(item) }}%)</span>
              </span>
              <span v-if="item.status === 'failed'" class="rust"> · failed</span>
            </div>
            <div v-if="item.status === 'uploading' && item.size > 0" class="upload-item__bar">
              <div
                class="upload-item__bar-fill"
                :style="{ width: itemPercentFor(item) + '%' }"
              />
            </div>
            <div v-if="item.error" class="upload-item__error">{{ item.error }}</div>
          </div>
          <button
            v-if="item.status === 'failed'"
            class="upload-item__action"
            :aria-label="`Retry ${item.name}`"
            @click="uploadsStore.retry(item.id)"
          >
            <q-icon name="mdi-refresh" size="var(--icon-md)" class="marigold" />
          </button>
          <button
            v-if="item.status !== 'uploading'"
            class="upload-item__action"
            :aria-label="`Remove ${item.name}`"
            @click="uploadsStore.remove(item.id)"
          >
            <q-icon name="mdi-close" size="var(--icon-md)" class="paper-dim" />
          </button>
        </div>
      </section>

      <section v-if="downloadStore.queue.length" class="downloads-page__section">
        <div class="caption-sc downloads-page__label">Downloads</div>
        <DownloadProgress
          v-for="item in downloadStore.queue"
          :key="item.id"
          :item="item"
          @cancel="downloadStore.cancelDownload"
        />
      </section>

      <section v-if="downloadStore.history.length" class="downloads-page__section">
        <div class="caption-sc downloads-page__label">History</div>
        <DownloadProgress
          v-for="item in downloadStore.history"
          :key="item.id"
          :item="item"
          @retry="downloadStore.retry"
        />
      </section>

      <div
        v-if="!downloadStore.queue.length && !downloadStore.history.length && !uploadsStore.items.length"
        class="empty-state"
      >
        <div class="empty-state__eyebrow">Nothing yet</div>
        <div class="empty-state__title">No transfers.</div>
        <div class="empty-state__subtitle">
          Tap download on any file, or upload via the + button, to see them here.
        </div>
        <button class="btn-ghost q-mt-md" @click="$router.push('/files')">
          <q-icon name="mdi-folder-outline" size="var(--icon-sm)" />
          Open Files
        </button>
      </div>
    </q-pull-to-refresh>
  </q-page>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useQuasar } from 'quasar'
import { useDownloadStore } from '../stores/downloads'
import { useUploadsStore } from '../stores/uploads'
import DownloadProgress from '../components/DownloadProgress.vue'
import { formatSize } from '../utils/helpers'

const $q = useQuasar()
const downloadStore = useDownloadStore()
const uploadsStore = useUploadsStore()

onMounted(() => uploadsStore.startPolling())
onBeforeUnmount(() => uploadsStore.stopPolling())

// Overall batch completion: done / (done + remaining).  Blends the native
// per-run counters (which reset when a new worker starts) with the current
// queue so the bar doesn't jump backwards mid-flight.
const overallPct = computed(() => {
  const done = uploadsStore.sessionUploadedCount
  const remaining = uploadsStore.items.filter(
    i => i.status === 'queued' || i.status === 'uploading'
  ).length
  const total = done + remaining
  if (total <= 0) return 0
  return Math.min(100, Math.max(0, (done / total) * 100))
})

// For the item whose id matches the native "currently uploading" id, surface
// the live byte counter from the worker progress.  Other items show nothing.
function itemBytesFor(item) {
  return item.id === uploadsStore.currentId ? uploadsStore.currentBytes : 0
}

function itemPercentFor(item) {
  if (item.id !== uploadsStore.currentId || !item.size) return 0
  return Math.min(100, Math.max(0, Math.floor((uploadsStore.currentBytes / item.size) * 100)))
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

async function onRefresh(done) {
  // Downloads state is derived from the persisted store + live work status.
  // If there's any native poll we want to kick, do it here; otherwise this
  // is a visual-only refresh.
  try { await downloadStore.refreshActive?.() } catch {}
  done()
}

function confirmClear() {
  $q.dialog({
    title: 'Clear history',
    message: 'Remove all download history? This cannot be undone.',
    cancel: true,
    ok: { label: 'Clear', color: 'negative' }
  }).onOk(() => {
    downloadStore.clearHistory()
    $q.notify({ message: 'History cleared', color: 'positive', timeout: 1500 })
  })
}
</script>

<style lang="scss" scoped>
.downloads-page {
  padding-bottom: 40px;

  &__clear {
    min-height: 36px;
    padding: 0 14px;
    font-size: 12px;
  }

  &__section { padding-top: 8px; }

  &__section-head {
    display: flex;
    align-items: center;
    padding: 18px 20px 10px;

    .btn-link {
      margin-left: auto;
    }
  }

  &__label {
    padding: 18px 20px 10px;
    color: var(--paper-dim);
  }
}

.upload-summary {
  margin: 0 20px 8px;
  padding: 14px 16px;
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);

  &__bar {
    height: 4px;
    background: var(--rule-soft);
    border-radius: 2px;
    overflow: hidden;
    margin-bottom: 10px;
  }

  &__bar-fill {
    height: 100%;
    background: linear-gradient(90deg, var(--marigold), #F4BE66);
    transition: width var(--dur-base) var(--ease-archive);
  }

  &__eta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-mute);
    letter-spacing: 0.02em;
    margin-bottom: 8px;
  }

  &__stats {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper);
  }

  &__stat {
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
}

.upload-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--rule-soft);

  &__icon { flex-shrink: 0; margin-top: 2px; }

  &__body { flex: 1; min-width: 0; }

  &__bar {
    margin-top: 6px;
    height: 2px;
    background: var(--rule-soft);
    border-radius: 1px;
    overflow: hidden;
  }

  &__bar-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 300ms var(--ease-archive);
  }

  &__name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 2px;
  }

  &__error {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--rust);
    margin-top: 4px;
    line-height: 1.4;
  }

  &__action {
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border: none;
    background: transparent;
    cursor: pointer;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    &:active { background: var(--ink-2); }
  }
}
</style>
