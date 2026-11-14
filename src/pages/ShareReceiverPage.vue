<template>
  <q-page class="share-recv">
    <div class="share-recv__statusbar-pad safe-top" />
    <q-toolbar class="share-recv__toolbar">
      <button class="share-recv__icon-btn" aria-label="Cancel" @click="cancel">
        <q-icon name="mdi-close" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="share-recv__title">Save to shelf</q-toolbar-title>
    </q-toolbar>

    <div v-if="items.length === 0" class="empty-state">
      <div class="empty-state__eyebrow">Share</div>
      <div class="empty-state__title">Nothing shared.</div>
      <div class="empty-state__subtitle">Return to the source app and tap Share.</div>
      <button class="btn-ghost q-mt-md" @click="cancel">Close</button>
    </div>

    <template v-else>
      <!-- Items summary -->
      <section class="share-recv__section">
        <div class="caption-sc share-recv__label">{{ items.length }} item{{ items.length === 1 ? '' : 's' }}</div>
        <div class="share-recv__list">
          <div v-for="(item, i) in items" :key="i" class="share-recv__item">
            <q-icon name="mdi-file-outline" size="var(--icon-md)" class="paper-mute" />
            <div class="share-recv__item-body">
              <div class="share-recv__item-name">{{ item.name }}</div>
              <div class="share-recv__item-meta tabular">
                {{ item.size ? formatSize(item.size) : '' }}
                <span v-if="item.mimeType" class="paper-dim"> · {{ item.mimeType }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- Shelf picker -->
      <section class="share-recv__section">
        <div class="caption-sc share-recv__label">Shelf</div>
        <div class="share-recv__shelves">
          <button
            v-for="s in settingsStore.shares"
            :key="s.id"
            class="share-recv__shelf"
            :class="{ 'share-recv__shelf--active': targetShareId === s.id }"
            @click="targetShareId = s.id"
          >
            {{ s.name }}
          </button>
        </div>
      </section>

      <!-- Destination path -->
      <section class="share-recv__section">
        <div class="caption-sc share-recv__label">Folder</div>
        <button class="share-recv__path" @click="editPath">
          <q-icon name="mdi-folder-outline" size="var(--icon-md)" class="paper-mute" />
          <span class="share-recv__path-text tabular">{{ targetPath || '(shelf root)' }}</span>
          <q-icon name="mdi-pencil-outline" size="var(--icon-sm)" class="paper-dim share-recv__path-edit" />
        </button>
      </section>

      <!-- Action -->
      <section class="share-recv__actions">
        <button
          class="btn-archive share-recv__primary"
          :disabled="uploading || !targetShareId"
          @click="startUpload"
        >
          <q-icon
            :name="uploading ? 'mdi-cloud-upload' : 'mdi-cloud-upload-outline'"
            size="var(--icon-sm)"
          />
          {{ uploading ? `Uploading… ${progress.current}/${progress.total}` : 'Upload' }}
        </button>
        <button class="btn-ghost" @click="cancel" :disabled="uploading">Cancel</button>
      </section>
    </template>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { useShareTargetStore } from '../stores/shareTarget'
import { useSettingsStore } from '../stores/settings'
import { useSmbStore } from '../stores/smb'
import { useUploadsStore } from '../stores/uploads'
import { formatSize } from '../utils/helpers'
import { friendlyError } from '../utils/sanitize'

const $q = useQuasar()
const router = useRouter()
const shareStore = useShareTargetStore()
const settingsStore = useSettingsStore()
const smbStore = useSmbStore()
const uploadsStore = useUploadsStore()

const items = computed(() => shareStore.items)
const targetShareId = ref('')
const targetPath = ref('')
const uploading = ref(false)

// Reactive progress from the smb store — reflects per-file progress as
// uploadFiles() walks the batch.
const progress = computed(() => smbStore.uploadProgress || { current: 0, total: 0 })

onMounted(() => {
  // Default to the currently-active shelf, then fall back to the first.
  targetShareId.value = smbStore.activeShareId || settingsStore.shares[0]?.id || ''
  // If Files is currently pointing somewhere, suggest that as the default
  // destination — most users share "back into" the folder they just browsed.
  targetPath.value = smbStore.currentPath || ''
})

function editPath() {
  $q.dialog({
    title: 'Destination folder',
    prompt: { model: targetPath.value, type: 'text', label: 'Relative path on the shelf' },
    cancel: true
  }).onOk(v => {
    targetPath.value = (v || '').trim().replace(/^[/\\]+/, '').replace(/[/\\]+$/, '')
  })
}

async function startUpload() {
  if (uploading.value) return
  if (!targetShareId.value) {
    $q.notify({ message: 'Pick a shelf first', color: 'negative', timeout: 2500 })
    return
  }
  // Switch the smb store to the target shelf + folder so we can use its
  // uploadFiles() directly — it already resolves creds + handles progress.
  if (smbStore.activeShareId !== targetShareId.value) {
    await smbStore.switchShare(targetShareId.value)
  }
  if (smbStore.currentPath !== targetPath.value) {
    try { await smbStore.navigateTo(targetPath.value) } catch { /* non-fatal */ }
  }

  uploading.value = true
  try {
    const share = settingsStore.shares.find(s => s.id === targetShareId.value)
    if (!share) throw new Error('Shelf not found')
    const files = items.value.map(it => ({
      localUri: it.uri, name: it.name, size: Number(it.size) || 0
    }))
    const result = await uploadsStore.enqueue({ files, share, path: targetPath.value })
    if (result.success) {
      $q.notify({
        message: `Queued ${result.queued} upload${result.queued === 1 ? '' : 's'} — watch progress in Downloads`,
        color: 'positive', textColor: 'dark', timeout: 3000
      })
      shareStore.clear()
      router.replace('/downloads')
    } else {
      $q.notify({
        message: `Upload failed: ${friendlyError(result.error || 'unknown error')}`,
        color: 'negative', timeout: 4000
      })
    }
  } catch (e) {
    $q.notify({ message: `Upload failed: ${friendlyError(e)}`, color: 'negative', timeout: 4000 })
  } finally {
    uploading.value = false
  }
}

function cancel() {
  shareStore.clear()
  router.replace('/files')
}
</script>

<style lang="scss" scoped>
.share-recv {
  padding-bottom: 40px;

  &__statusbar-pad { background: var(--ink-0); width: 100%; }

  &__toolbar {
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
    min-height: 56px;
    padding: 0 8px;
    gap: 4px;
  }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 17px;
    color: var(--paper);
    padding-left: 8px;
  }

  &__icon-btn {
    width: var(--tap-min);
    height: var(--tap-min);
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    &:active { background: var(--ink-2); }
  }

  &__section {
    padding: 22px 20px 2px;
  }

  &__label {
    color: var(--paper-dim);
    margin-bottom: 10px;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 14px;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-sm);
  }

  &__item-body { flex: 1; min-width: 0; }
  &__item-name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  &__item-meta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-mute);
    margin-top: 2px;
  }

  &__shelves {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__shelf {
    min-height: 40px;
    padding: 0 14px;
    font-family: var(--font-body);
    font-size: 13px;
    font-weight: 500;
    color: var(--paper);
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-sm);
    cursor: pointer;

    &--active {
      background: var(--marigold-soft);
      border-color: var(--marigold);
      color: var(--marigold);
    }
  }

  &__path {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-sm);
    color: var(--paper);
    font: inherit;
    text-align: left;
    cursor: pointer;
  }

  &__path-text {
    flex: 1;
    min-width: 0;
    font-family: var(--font-body);
    font-size: 13px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__path-edit { flex-shrink: 0; }

  &__actions {
    display: flex;
    gap: 10px;
    padding: 26px 20px 0;
  }

  &__primary { flex: 1; }
}
</style>
