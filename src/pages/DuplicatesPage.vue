<template>
  <q-page class="dup-page">
    <div class="dup-page__statusbar-pad safe-top" />
    <q-toolbar class="dup-page__toolbar">
      <button
        class="dup-page__icon-btn"
        aria-label="Back"
        @click="goBack"
      >
        <q-icon name="mdi-arrow-left" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="dup-page__title">Duplicates</q-toolbar-title>
    </q-toolbar>

    <div class="dup-page__body">
      <!-- Shelf picker + controls -->
      <section class="dup-page__section">
        <div class="dup-controls">
          <div class="dup-controls__row">
            <div class="dup-controls__label caption-sc">Scan shelf</div>
            <q-select
              v-model="shareId"
              :options="shareOptions"
              emit-value map-options dense borderless
              class="dup-controls__select"
            />
          </div>
          <div class="dup-controls__row">
            <div class="dup-controls__label caption-sc">Within path</div>
            <q-input
              v-model="scanPath"
              dense borderless
              placeholder="(root)"
              class="dup-controls__input"
              autocapitalize="none"
              autocorrect="off"
              spellcheck="false"
            />
          </div>
          <div class="dup-controls__actions">
            <button
              v-if="!store.running"
              class="btn-archive"
              :disabled="!shareId"
              @click="startScan"
            >
              <q-icon name="mdi-magnify-scan" size="16px" />
              Scan for duplicates
            </button>
            <button
              v-else
              class="btn-danger-ghost"
              @click="store.cancel()"
            >
              <q-icon name="mdi-close-circle-outline" size="16px" />
              Stop
            </button>
          </div>
        </div>
      </section>

      <!-- Running status -->
      <section v-if="store.running || store.phase" class="dup-page__section">
        <div class="dup-status">
          <div class="dup-status__phase">
            <span v-if="store.phase === 'walking'">Walking tree…</span>
            <span v-else-if="store.phase === 'hashing'">Hashing candidates…</span>
            <span v-else-if="store.phase === 'done'">Scan complete.</span>
            <span v-else-if="store.phase === 'canceled'">Scan canceled.</span>
          </div>
          <div class="dup-status__meta tabular caption-sc">
            {{ store.groupsFound }} group{{ store.groupsFound === 1 ? '' : 's' }}
            · {{ formatSize(store.bytesWasted) }} reclaimable
          </div>
        </div>
      </section>

      <!-- Error -->
      <div v-if="store.error" class="dup-error">
        {{ store.error }}
      </div>

      <!-- Groups -->
      <section v-if="store.visibleGroups.length > 0" class="dup-page__section">
        <div class="section-head">
          <div class="section-head__title">
            <span class="caption-sc">Groups · {{ store.visibleGroups.length }}</span>
          </div>
          <div class="section-head__actions">
            <button class="btn-link" @click="toggleAll(true)">Select copies</button>
            <button class="btn-link" @click="toggleAll(false)">Clear</button>
          </div>
        </div>

        <div class="dup-groups">
          <div
            v-for="group in store.visibleGroups"
            :key="group.id"
            class="dup-group"
          >
            <div class="dup-group__head">
              <div class="dup-group__size tabular">{{ formatSize(group.size) }}</div>
              <div class="dup-group__count caption-sc">
                {{ group.files.length }} copies
                · save {{ formatSize(group.size * (group.files.length - 1)) }}
              </div>
            </div>
            <ul class="dup-group__files">
              <li
                v-for="file in group.files"
                :key="file.path"
                class="dup-file"
                :class="{ 'dup-file--selected': file.selected }"
              >
                <input
                  type="checkbox"
                  class="dup-file__check"
                  :checked="file.selected"
                  @change="file.selected = $event.target.checked"
                />
                <div class="dup-file__body">
                  <div class="dup-file__name">{{ file.name }}</div>
                  <div class="dup-file__path caption-sc">{{ file.path }}</div>
                  <div class="dup-file__meta caption-sc tabular">
                    {{ formatWhen(file.modified) }}
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </section>

      <!-- Empty state (done, nothing found) -->
      <div
        v-else-if="store.phase === 'done' && !store.running"
        class="empty-state"
      >
        <div class="empty-state__eyebrow">Duplicates</div>
        <div class="empty-state__title">Clean shelf.</div>
        <div class="empty-state__subtitle">
          No duplicate files found in this scan.
        </div>
      </div>
    </div>

    <!-- Sticky footer with delete action -->
    <div
      v-if="store.selectedCount > 0"
      class="dup-footer safe-bottom"
    >
      <div class="dup-footer__meta tabular">
        {{ store.selectedCount }} selected · {{ formatSize(store.selectedBytes) }}
      </div>
      <button
        class="btn-danger dup-footer__action"
        :disabled="deleting"
        @click="confirmDelete"
      >
        <q-icon :name="deleting ? 'mdi-loading' : 'mdi-trash-can-outline'" size="16px" />
        {{ deleting ? 'Deleting…' : 'Delete selected' }}
      </button>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useDuplicatesStore } from '../stores/duplicates'
import { useSettingsStore } from '../stores/settings'
import { useSmbStore } from '../stores/smb'
import { formatSize } from '../utils/helpers'

const router = useRouter()
const $q = useQuasar()
const store = useDuplicatesStore()
const settingsStore = useSettingsStore()
const smbStore = useSmbStore()

const shareId = ref('')
const scanPath = ref('')
const deleting = ref(false)

const shareOptions = computed(() =>
  settingsStore.shares.map(s => ({ label: s.name, value: s.id }))
)

onMounted(() => {
  // Default to the active shelf + current path so the scan is scoped to
  // whatever the user was already looking at.  User can override either.
  shareId.value = store.lastRunShareId || smbStore.activeShare?.id || settingsStore.shares[0]?.id || ''
  scanPath.value = store.lastRunPath || smbStore.currentPath || ''
})

onBeforeUnmount(() => {
  // Keep the listeners alive across navigations so a long scan doesn't die
  // if the user pops to another page — but tear down if the scan is idle
  // to avoid leaking event handlers indefinitely.
  if (!store.running) store.tearDownSubs()
})

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/files')
}

async function startScan() {
  if (!shareId.value) return
  await store.startScan({ shareId: shareId.value, path: scanPath.value.trim() })
}

function toggleAll(value) {
  for (const g of store.visibleGroups) {
    // When toggling ON: tick everything except the newest (index 0 after sort).
    // When toggling OFF: untick all.
    g.files.forEach((f, i) => { f.selected = value && i > 0 })
  }
}

function confirmDelete() {
  $q.dialog({
    title: 'Delete selected duplicates?',
    message: `Permanently remove ${store.selectedCount} file(s)? This can't be undone.`,
    ok: { label: 'Delete', color: 'negative' },
    cancel: { label: 'Keep', flat: true }
  }).onOk(async () => {
    deleting.value = true
    try {
      const result = await store.deleteSelected()
      const tone = result.failed > 0 ? 'warning' : 'positive'
      const msg = result.failed > 0
        ? `${result.deleted} deleted · ${result.failed} failed`
        : `Deleted ${result.deleted} duplicate${result.deleted === 1 ? '' : 's'}`
      $q.notify({ message: msg, color: tone, textColor: 'dark', timeout: 2800 })
    } finally {
      deleting.value = false
    }
  })
}

function formatWhen(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit'
  })
}
</script>

<style lang="scss" scoped>
.dup-page {
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

  &__body {
    padding: 16px;
  }

  &__section { margin-bottom: 20px; }
}

.dup-controls {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 14px 16px;

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 0;
    border-top: 1px solid var(--rule-soft);
    &:first-child { border-top: none; padding-top: 0; }
  }

  &__label {
    color: var(--paper-mute);
    letter-spacing: 0.08em;
    flex-shrink: 0;
    width: 92px;
  }

  &__select, &__input {
    flex: 1;
    :deep(.q-field__native) {
      color: var(--paper);
      font-family: var(--font-body);
      font-size: 13px;
    }
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
    margin-top: 10px;
  }
}

.dup-status {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 12px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  &__phase {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
  }

  &__meta {
    color: var(--paper-mute);
  }
}

.dup-error {
  color: var(--rust);
  font-size: 13px;
  margin-bottom: 16px;
}

.dup-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dup-group {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 12px 14px;

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--rule-soft);
  }

  &__size {
    font-family: var(--font-display);
    font-size: 16px;
    color: var(--paper);
  }

  &__count {
    color: var(--paper-mute);
  }

  &__files {
    list-style: none;
    padding: 0;
    margin: 0;
  }
}

.dup-file {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--rule-soft);

  &:first-child { border-top: none; }

  &__check {
    margin-top: 4px;
    accent-color: var(--marigold);
    cursor: pointer;
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
    margin-top: 2px;
  }

  &--selected {
    .dup-file__name { color: var(--marigold); }
  }
}

.dup-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
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
