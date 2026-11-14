<template>
  <q-page class="cleanup-page">
    <div class="cleanup-page__statusbar-pad safe-top" />
    <q-toolbar class="cleanup-page__toolbar">
      <button class="cleanup-page__icon-btn" aria-label="Back" @click="goBack">
        <q-icon name="mdi-arrow-left" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="cleanup-page__title">Cleanup queue</q-toolbar-title>
      <button
        v-if="hasAny"
        class="cleanup-page__icon-btn"
        aria-label="Clear queue"
        @click="confirmClearAll"
      >
        <q-icon name="mdi-trash-can-outline" size="var(--icon-md)" class="paper-mute" />
      </button>
    </q-toolbar>

    <div class="cleanup-page__body">
      <!-- Summary card -->
      <section class="cleanup-page__section">
        <div class="cleanup-summary">
          <div class="cleanup-summary__row">
            <div>
              <div class="caption-sc cleanup-summary__label">Ready to delete</div>
              <div class="cleanup-summary__big tabular">
                {{ store.cleanupEligible }} file<span v-if="store.cleanupEligible !== 1">s</span>
              </div>
              <div class="cleanup-summary__meta caption-sc tabular">
                {{ formatSize(store.cleanupEligibleBytes) }} reclaimable
              </div>
            </div>
            <button
              v-if="store.cleanupEligible > 0"
              class="btn-archive"
              :disabled="deleting"
              @click="deleteEligible"
            >
              <q-icon :name="deleting ? 'mdi-loading' : 'mdi-delete-outline'" size="16px" />
              {{ deleting ? 'Deleting…' : 'Delete eligible' }}
            </button>
          </div>
          <div class="cleanup-summary__divider" />
          <div class="cleanup-summary__row cleanup-summary__row--sub">
            <div class="caption-sc cleanup-summary__label">In grace</div>
            <div class="cleanup-summary__val tabular">
              {{ store.cleanupQueue.length - store.cleanupEligible }} file<span v-if="(store.cleanupQueue.length - store.cleanupEligible) !== 1">s</span>
              · {{ formatSize(store.cleanupTotalBytes - store.cleanupEligibleBytes) }}
            </div>
          </div>
        </div>
      </section>

      <!-- Empty state -->
      <div v-if="!hasAny" class="empty-state">
        <div class="empty-state__eyebrow">Cleanup</div>
        <div class="empty-state__title">Queue is empty.</div>
        <div class="empty-state__subtitle">
          Files only appear here when backup is complete and cleanup is enabled in Advanced settings.
        </div>
      </div>

      <!-- Entries -->
      <section v-else class="cleanup-page__section">
        <div class="section-head">
          <div class="section-head__title">
            <span class="caption-sc">{{ store.cleanupQueue.length }} queued</span>
          </div>
          <div class="section-head__actions">
            <button class="btn-link" @click="selectEligible">Select eligible</button>
            <button class="btn-link" @click="selected.clear(); refreshSel()">Clear</button>
          </div>
        </div>

        <ul class="cleanup-list">
          <li
            v-for="entry in store.cleanupQueue"
            :key="entry.id"
            class="cleanup-row"
            :class="{
              'cleanup-row--eligible': isEligible(entry),
              'cleanup-row--selected': selected.has(entry.id)
            }"
            @click="toggle(entry.id)"
          >
            <input
              type="checkbox"
              class="cleanup-row__check"
              :checked="selected.has(entry.id)"
              @click.stop
              @change="toggle(entry.id)"
            />

            <button
              type="button"
              class="cleanup-row__thumb"
              :aria-label="`Preview ${entry.name}`"
              @click.stop="openPreview(entry)"
            >
              <img
                v-if="isImage(entry)"
                :src="thumbSrc(entry)"
                class="cleanup-row__thumb-img"
                loading="lazy"
                @error="onThumbError"
              />
              <video
                v-else-if="isVideo(entry)"
                :src="thumbSrc(entry)"
                class="cleanup-row__thumb-img"
                preload="metadata"
                muted
                playsinline
                @error="onThumbError"
              />
              <q-icon
                v-else
                name="mdi-file-document-outline"
                size="22px"
                class="cleanup-row__thumb-icon"
              />
              <div v-if="isVideo(entry)" class="cleanup-row__thumb-play">
                <q-icon name="mdi-play" size="14px" />
              </div>
            </button>

            <div class="cleanup-row__body">
              <div class="cleanup-row__name">{{ entry.name }}</div>
              <div class="cleanup-row__path caption-sc">{{ entry.path }}</div>
              <div class="cleanup-row__meta caption-sc tabular">
                {{ formatSize(entry.size) }}
                · {{ isEligible(entry) ? 'eligible now' : `in grace (${graceLabel(entry)})` }}
              </div>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <!-- Fullscreen preview dialog -->
    <q-dialog v-model="previewOpen" maximized transition-show="fade" transition-hide="fade">
      <div class="preview-shell">
        <div class="preview-shell__head safe-top">
          <button class="preview-shell__close" aria-label="Close preview" @click="previewOpen = false">
            <q-icon name="mdi-close" size="22px" />
          </button>
          <div class="preview-shell__title">
            {{ previewEntry?.name || '' }}
          </div>
          <span class="preview-shell__close" aria-hidden="true" />
        </div>

        <div class="preview-shell__body">
          <img
            v-if="previewEntry && isImage(previewEntry)"
            :src="thumbSrc(previewEntry)"
            class="preview-shell__media"
          />
          <video
            v-else-if="previewEntry && isVideo(previewEntry)"
            :src="thumbSrc(previewEntry)"
            class="preview-shell__media"
            controls
            playsinline
            preload="metadata"
          />
          <div v-else class="preview-shell__fallback">
            <q-icon name="mdi-file-document-outline" size="48px" class="paper-mute" />
            <div class="preview-shell__fallback-name">{{ previewEntry?.name || '' }}</div>
          </div>
        </div>

        <div class="preview-shell__meta safe-bottom">
          <div class="preview-shell__meta-row">
            <span class="caption-sc">Size</span>
            <span class="tabular">{{ formatSize(previewEntry?.size || 0) }}</span>
          </div>
          <div class="preview-shell__meta-row">
            <span class="caption-sc">Status</span>
            <span class="tabular">{{ previewEntry && isEligible(previewEntry) ? 'eligible now' : `in grace${previewEntry ? ' · ' + graceLabel(previewEntry) : ''}` }}</span>
          </div>
          <div class="preview-shell__meta-row preview-shell__meta-row--path">
            <span class="caption-sc">Path</span>
            <span class="preview-shell__meta-path">{{ previewEntry?.path || '' }}</span>
          </div>
        </div>
      </div>
    </q-dialog>

    <!-- Sticky footer: delete selection -->
    <div v-if="selected.size > 0" class="cleanup-footer safe-bottom">
      <div class="cleanup-footer__meta tabular">
        {{ selected.size }} selected · {{ formatSize(selectedBytes) }}
      </div>
      <button
        class="btn-danger cleanup-footer__action"
        :disabled="deleting"
        @click="deleteSelected"
      >
        <q-icon :name="deleting ? 'mdi-loading' : 'mdi-trash-can-outline'" size="16px" />
        {{ deleting ? 'Deleting…' : 'Delete selected' }}
      </button>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { Capacitor } from '@capacitor/core'
import { useBackupStore } from '../stores/backup'
import { formatSize } from '../utils/helpers'

const IMAGE_TYPES = new Set(['image', 'images'])
const VIDEO_TYPES = new Set(['video', 'videos'])
const IMAGE_EXTS = new Set(['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'heic', 'heif'])
const VIDEO_EXTS = new Set(['mp4', 'mkv', 'mov', 'webm', 'avi', 'm4v', '3gp'])

function extOf(name) {
  const dot = (name || '').lastIndexOf('.')
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
}

function isImage(entry) {
  if (!entry) return false
  if (IMAGE_TYPES.has(String(entry.type || '').toLowerCase())) return true
  return IMAGE_EXTS.has(extOf(entry.name))
}

function isVideo(entry) {
  if (!entry) return false
  if (VIDEO_TYPES.has(String(entry.type || '').toLowerCase())) return true
  return VIDEO_EXTS.has(extOf(entry.name))
}

function thumbSrc(entry) {
  // Local file paths can't be rendered by the https-scheme WebView
  // directly — Capacitor.convertFileSrc rewrites them to a
  // `https://localhost/_capacitor_file_/...` URL the view can load.
  const path = entry?.path || ''
  if (!path) return ''
  if (Capacitor.isNativePlatform()) return Capacitor.convertFileSrc(path)
  return `file://${path}`
}

function onThumbError(e) {
  // Keep the row usable even when a thumb fails (OS denied access, file
  // vanished, unsupported codec, etc.). Hide the broken element so the
  // generic icon fallback shows instead.
  e.target.style.display = 'none'
}

const router = useRouter()
const $q = useQuasar()
const store = useBackupStore()

const selected = ref(new Set())
const selectionKey = ref(0)   // bump to force reactivity since Set mutations don't trigger it
const deleting = ref(false)
const previewOpen = ref(false)
const previewEntry = ref(null)

function openPreview(entry) {
  previewEntry.value = entry
  previewOpen.value = true
}

// Trigger recompute when the Set mutates.  Vue can't track `.add`/`.delete`
// on a Set, so we bump a companion ref and read it in the computeds.
function refreshSel() { selectionKey.value++ }
function toggle(id) {
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
  refreshSel()
}

const hasAny = computed(() => store.cleanupQueue.length > 0)
const selectedBytes = computed(() => {
  void selectionKey.value
  let b = 0
  for (const e of store.cleanupQueue) if (selected.value.has(e.id)) b += Number(e.size) || 0
  return b
})

function isEligible(entry) {
  return (entry.eligibleAt || 0) <= Date.now()
}

function graceLabel(entry) {
  const ms = (entry.eligibleAt || 0) - Date.now()
  if (ms <= 0) return 'now'
  const days = Math.ceil(ms / 86_400_000)
  if (days <= 1) return 'in <1d'
  return `in ${days}d`
}

function selectEligible() {
  for (const e of store.cleanupQueue) if (isEligible(e)) selected.value.add(e.id)
  refreshSel()
}

async function deleteEligible() {
  const ids = store.cleanupQueue.filter(isEligible).map(e => e.id)
  if (ids.length === 0) return
  await doDelete(ids)
}

async function deleteSelected() {
  const ids = Array.from(selected.value)
  if (ids.length === 0) return
  const inGrace = store.cleanupQueue.filter(e => ids.includes(e.id) && !isEligible(e))
  // If any selection is still in grace, ask once before bypassing.  This
  // honours the safety rail by default but lets the user say "I know,
  // delete now anyway" without going to settings to shorten grace.
  if (inGrace.length > 0) {
    $q.dialog({
      title: 'Skip grace period?',
      message: `${inGrace.length} of ${ids.length} selected are still in their grace window. Delete them anyway?`,
      ok: { label: 'Delete now', color: 'negative' },
      cancel: { label: 'Wait', flat: true }
    }).onOk(() => doDelete(ids, /* onlyEligible */ false))
    return
  }
  await doDelete(ids, /* onlyEligible */ true)
}

async function doDelete(ids, onlyEligible = true) {
  deleting.value = true
  try {
    const res = await store.executeCleanup({ ids, onlyEligible })
    if (!res?.accepted) {
      $q.notify({
        message: 'Delete canceled.',
        color: 'info', textColor: 'dark', timeout: 2000
      })
      return
    }
    const count = Number(res.deleted) || 0
    $q.notify({
      message: count > 0 ? `Removed ${count} file${count === 1 ? '' : 's'}` : 'Nothing removed',
      color: count > 0 ? 'positive' : 'warning',
      textColor: 'dark',
      timeout: 2500
    })
    // Keep a clean selection — dropped ids are gone.
    for (const id of ids) selected.value.delete(id)
    refreshSel()
  } finally {
    deleting.value = false
  }
}

function confirmClearAll() {
  $q.dialog({
    title: 'Clear cleanup queue?',
    message: 'Removes entries from the queue. Files on the phone are NOT deleted.',
    ok: { label: 'Clear', color: 'negative' },
    cancel: { label: 'Keep', flat: true }
  }).onOk(async () => {
    await store.clearCleanupQueue()
    selected.value.clear()
    refreshSel()
  })
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/gallery')
}

onMounted(() => store.refreshCleanupQueue())
</script>

<style lang="scss" scoped>
.cleanup-page {
  min-height: 100vh;
  background: var(--ink-0);
  padding-bottom: 96px;

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
  }

  &__body { padding: 16px; }
  &__section { margin-bottom: 20px; }
}

.cleanup-summary {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 18px 20px;

  &__row {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 16px;

    &--sub {
      align-items: baseline;
    }
  }

  &__label { color: var(--paper-dim); letter-spacing: 0.1em; }
  &__big {
    font-family: var(--font-display);
    font-size: clamp(24px, 7vw, 32px);
    color: var(--paper);
    line-height: 1;
    margin-top: 4px;
  }
  &__meta { color: var(--paper-mute); margin-top: 6px; }
  &__val  { color: var(--paper-mute); font-size: 12px; }
  &__divider {
    height: 1px;
    background: var(--rule-soft);
    margin: 14px 0;
  }
}

.cleanup-list {
  list-style: none;
  padding: 0;
  margin: 0;
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;
}

.cleanup-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-top: 1px solid var(--rule-soft);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-archive);
  &:first-child { border-top: none; }
  &:active { background: var(--ink-1); }

  &--selected { background: var(--ink-1); }

  &__check {
    accent-color: var(--marigold);
    flex-shrink: 0;
  }

  &__thumb {
    position: relative;
    width: 56px;
    height: 56px;
    border-radius: var(--r-sm);
    overflow: hidden;
    background: var(--ink-2);
    border: 1px solid var(--rule);
    padding: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    flex-shrink: 0;
    transition: border-color var(--dur-fast) var(--ease-archive);

    &:active { border-color: var(--marigold); }
  }

  &__thumb-img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &__thumb-icon {
    color: var(--paper-mute);
  }

  &__thumb-play {
    position: absolute;
    right: 3px;
    bottom: 3px;
    background: rgba(0, 0, 0, 0.55);
    color: var(--paper);
    width: 18px;
    height: 18px;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    backdrop-filter: blur(4px);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    word-break: break-all;
  }

  &__path {
    color: var(--paper-dim);
    margin-top: 2px;
    word-break: break-all;
  }

  &__meta {
    color: var(--paper-dim);
    margin-top: 4px;
    letter-spacing: 0.04em;
  }

  &--eligible .cleanup-row__name { color: var(--paper); }
  &--eligible .cleanup-row__meta { color: var(--marigold); }
}

.cleanup-footer {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  padding: 12px 16px;
  background: var(--ink-1);
  border-top: 1px solid var(--rule);
  display: flex;
  align-items: center;
  gap: 12px;
  z-index: 20;

  &__meta {
    flex: 1;
    color: var(--paper-mute);
    font-size: 12px;
  }

  &__action { flex-shrink: 0; }
}

.preview-shell {
  background: var(--ink-0);
  min-height: 100vh;
  display: flex;
  flex-direction: column;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 12px 8px;
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
  }

  &__close {
    width: var(--tap-min);
    height: var(--tap-min);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper);
    border-radius: 50%;
    cursor: pointer;
    flex-shrink: 0;
    &:active { background: var(--ink-2); }
  }

  &__title {
    flex: 1;
    min-width: 0;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    text-align: center;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    padding: 0 8px;
  }

  &__body {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #000;
    padding: 12px;
    overflow: hidden;
  }

  &__media {
    max-width: 100%;
    max-height: 100%;
    display: block;
    object-fit: contain;
  }

  &__fallback {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
    color: var(--paper-mute);
  }

  &__fallback-name {
    font-family: var(--font-body);
    font-size: 14px;
    color: var(--paper);
    word-break: break-all;
    text-align: center;
    max-width: 280px;
  }

  &__meta {
    background: var(--ink-0);
    border-top: 1px solid var(--rule);
    padding: 14px 16px 18px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__meta-row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);

    .caption-sc { color: var(--paper-dim); }

    &--path {
      flex-direction: column;
      align-items: stretch;
      gap: 2px;
    }
  }

  &__meta-path {
    color: var(--paper-mute);
    font-size: 11px;
    word-break: break-all;
    font-family: var(--font-body);
    letter-spacing: 0;
  }
}

.empty-state {
  padding: 32px 16px;
  text-align: center;
  color: var(--paper-mute);

  &__eyebrow {
    font-family: var(--font-body);
    font-size: 11px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--paper-dim);
  }

  &__title {
    font-family: var(--font-display);
    font-size: 22px;
    color: var(--paper);
    margin-top: 6px;
  }

  &__subtitle {
    font-size: 13px;
    color: var(--paper-mute);
    margin-top: 4px;
  }
}
</style>
