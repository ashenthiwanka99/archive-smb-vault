<template>
  <q-page class="search-page">
    <div class="search-page__statusbar-pad safe-top" />
    <q-toolbar class="search-page__toolbar">
      <q-btn flat dense round icon="mdi-arrow-left" color="primary" size="md" aria-label="Back" @click="goBack" />
      <q-input
        v-model="query"
        dense borderless
        placeholder="Search this shelf"
        autocapitalize="none"
        autocorrect="off"
        spellcheck="false"
        class="col q-mx-sm"
        autofocus
        @keyup.enter="runSearch"
      >
        <template #prepend>
          <q-icon name="mdi-magnify" size="var(--icon-sm)" class="paper-dim" />
        </template>
        <template #append>
          <q-icon
            v-if="query"
            name="mdi-close"
            size="var(--icon-xs)"
            class="cursor-pointer paper-dim"
            aria-label="Clear search"
            @click="clearSearch"
          />
        </template>
      </q-input>
      <q-btn
        flat dense
        icon="mdi-magnify"
        size="md"
        class="marigold"
        aria-label="Run search"
        @click="manualGo"
      />
      <q-btn flat dense icon="mdi-tune-variant" size="md" class="paper-mute" aria-label="Filter by type">
        <q-menu>
          <q-list dense>
            <q-item
              v-for="t in typeFilters"
              :key="t.value"
              clickable v-close-popup
              @click="selectedType = selectedType === t.value ? '' : t.value"
            >
              <q-item-section avatar>
                <q-icon
                  :name="selectedType === t.value ? 'mdi-checkbox-marked-outline' : 'mdi-checkbox-blank-outline'"
                  size="var(--icon-sm)"
                />
              </q-item-section>
              <q-item-section>{{ t.label }}</q-item-section>
            </q-item>
          </q-list>
        </q-menu>
      </q-btn>
    </q-toolbar>

    <!-- DEBUG: live state line — remove once search is known-working -->
    <div style="padding: 6px 20px; background: #2a1e0a; font-family: monospace; font-size: 11px; color: #ffd27a; overflow-x: auto; white-space: nowrap;">
      q='{{ query }}' · len={{ query.length }} · share={{ smbStore.activeShare?.name || 'NONE' }}
      · taskId={{ activeTaskId || '-' }} · results={{ results.length }} · searching={{ searching }}
    </div>

    <!-- Scope / status banner -->
    <div class="search-page__scope caption-sc">
      <template v-if="searching">
        Searching <span class="paper">{{ scopeLabel }}</span> · {{ results.length }} match{{ results.length === 1 ? '' : 'es' }}
      </template>
      <template v-else-if="searched">
        {{ results.length }} match{{ results.length === 1 ? '' : 'es' }} in <span class="paper">{{ scopeLabel }}</span>
        <span v-if="canceled" class="paper-dim"> · stopped</span>
      </template>
      <template v-else>
        In <span class="paper">{{ scopeLabel }}</span>
      </template>
      <button v-if="searching" class="btn-link search-page__cancel" @click="cancelSearch">
        Cancel
      </button>
    </div>

    <!-- Results -->
    <div v-if="results.length > 0">
      <FileRow
        v-for="file in results"
        :key="file.path"
        :file="file"
        @tap="openResult(file)"
      />
    </div>

    <!-- Searching with no hits yet -->
    <div v-else-if="searching" class="search-page__searching">
      <q-spinner color="primary" size="18px" class="q-mr-sm" />
      <span>Walking the shelf…</span>
    </div>

    <!-- Completed, no hits -->
    <div v-else-if="searched" class="empty-state">
      <div class="empty-state__eyebrow">No matches</div>
      <div class="empty-state__title">Nothing found.</div>
      <div class="empty-state__subtitle">
        Nothing matches &ldquo;{{ lastQuery }}&rdquo;{{ selectedType ? ` in ${selectedType}` : '' }}.
      </div>
    </div>

    <!-- Initial prompt -->
    <div v-else class="empty-state">
      <div class="empty-state__eyebrow">Search</div>
      <div class="empty-state__title">What are you looking for?</div>
      <div class="empty-state__subtitle">Walks every folder on the <span class="paper">{{ scopeLabel }}</span> shelf.</div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useSmbStore } from '../stores/smb'
import { useSettingsStore } from '../stores/settings'
import { SmbPlugin } from '../plugins/SmbPlugin'
import FileRow from '../components/FileRow.vue'
import { friendlyError } from '../utils/sanitize'

const router = useRouter()
const $q = useQuasar()
const smbStore = useSmbStore()
const settingsStore = useSettingsStore()

const query = ref('')
const selectedType = ref('')
const results = ref([])
const searching = ref(false)
const searched = ref(false)
const canceled = ref(false)
const lastQuery = ref('')
const activeTaskId = ref('')

// Search always runs against the whole active shelf.
const scopeLabel = computed(() =>
  smbStore.activeShare?.name || smbStore.activeShare?.shareName || 'shelf'
)

const typeFilters = [
  { label: 'Folder',   value: 'folder' },
  { label: 'Video',    value: 'video' },
  { label: 'Image',    value: 'image' },
  { label: 'Audio',    value: 'audio' },
  { label: 'Document', value: 'document' },
  { label: 'Code',     value: 'code' },
  { label: 'Archive',  value: 'archive' }
]

let unsubResults = null
let unsubComplete = null

// Track every taskId we've started that hasn't finished yet so events can't
// race the `await` that sets activeTaskId.  Without this, the first batch of
// results would fire before activeTaskId was assigned and get dropped.
const pendingTasks = new Set()

onMounted(async () => {
  unsubResults = await SmbPlugin.onSearchResults(({ taskId, matches }) => {
    if (!taskId || !pendingTasks.has(taskId)) return
    for (const m of matches || []) results.value.push(m)
  })
  unsubComplete = await SmbPlugin.onSearchComplete(({ taskId, canceled: wasCanceled, error }) => {
    if (!taskId || !pendingTasks.has(taskId)) return
    pendingTasks.delete(taskId)
    // Only flip UI state if THIS task is the currently displayed one.  If the
    // user already started another search, leave it in searching-state.
    if (taskId === activeTaskId.value) {
      searching.value = false
      searched.value = true
      canceled.value = !!wasCanceled
      if (error) {
        $q.notify({ message: `Search failed: ${friendlyError(error)}`, color: 'negative', timeout: 3500 })
      }
    }
  })
})

/**
 * Auto-run the search a beat after the user stops typing.  Runs for any
 * query ≥ 2 chars (single-char searches would scan the whole shelf for
 * basically no signal).  Enter still triggers immediately.
 *
 * We use a Vue `watch` + setTimeout instead of q-input's `debounce` prop
 * because the latter was silently not firing on some Quasar/WebView combos,
 * which is why the search appeared dead even though the logic was right.
 */
// Debug helper: fired from an explicit search button so we can diagnose
// whether v-model + watch path is wired correctly.
function manualGo() {
  $q.notify({
    message: `(debug) query='${query.value}' share=${smbStore.activeShare?.name || 'none'}`,
    color: 'info', timeout: 2500
  })
  runSearch()
}

let debounceHandle = null
watch(query, (next) => {
  if (debounceHandle) {
    clearTimeout(debounceHandle)
    debounceHandle = null
  }
  const q = (next || '').trim()
  if (q.length === 0) {
    clearSearch()
    return
  }
  if (q.length < 2) return
  debounceHandle = setTimeout(() => {
    debounceHandle = null
    runSearch()
  }, 350)
})

onBeforeUnmount(() => {
  // Stop any running search when the page unmounts so native doesn't keep
  // walking the tree behind our back.
  if (activeTaskId.value) {
    SmbPlugin.cancelSearch({ taskId: activeTaskId.value }).catch(() => {})
  }
  unsubResults?.()
  unsubComplete?.()
})

async function runSearch() {
  const q = query.value.trim()
  if (!q) {
    $q.notify({ message: '(debug) empty query, not searching', color: 'warning', textColor: 'dark', timeout: 1500 })
    return
  }
  const share = smbStore.activeShare
  if (!share) {
    $q.notify({ message: 'No shelf linked', color: 'negative', timeout: 2500 })
    return
  }
  $q.notify({ message: `Searching "${q}" on ${share.name || share.shareName}…`, color: 'info', timeout: 1500 })

  // Cancel any in-flight search first so its events don't bleed into the new
  // results list.  Iterating over `pendingTasks` because rapid typing can
  // leave multiple concurrent walkers even with debounce.
  for (const t of pendingTasks) {
    SmbPlugin.cancelSearch({ taskId: t }).catch(() => {})
  }
  pendingTasks.clear()
  activeTaskId.value = ''

  results.value = []
  searched.value = false
  canceled.value = false
  searching.value = true
  lastQuery.value = q

  // Pre-generate the task id so the gate in the event listener is set up
  // before the first result batch can arrive.  Without this, results fired
  // between call.resolve() and the `await` settling would be dropped.
  const newId = 'js' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
  pendingTasks.add(newId)
  activeTaskId.value = newId
  console.log('[search] starting', newId, 'q=', q, 'share=', share.shareName)

  try {
    const creds = await settingsStore.getShareCredentials(share.id)
    await SmbPlugin.recursiveSearch({
      host: share.host,
      share: share.shareName,
      // Always start from the shelf root so "search" means "the whole shelf"
      // regardless of where the user was browsing when they tapped the icon.
      path: '',
      username: creds.username,
      password: creds.password,
      query: q,
      types: selectedType.value,
      taskId: newId
    })
  } catch (e) {
    pendingTasks.delete(newId)
    if (activeTaskId.value === newId) activeTaskId.value = ''
    searching.value = false
    searched.value = true
    $q.notify({ message: `Search failed: ${friendlyError(e)}`, color: 'negative', timeout: 3500 })
  }
}

async function cancelSearch() {
  if (!activeTaskId.value) return
  try { await SmbPlugin.cancelSearch({ taskId: activeTaskId.value }) } catch {}
  // The native 'searchComplete' event will flip `searching` off.
}

function clearSearch() {
  query.value = ''
  results.value = []
  searched.value = false
  canceled.value = false
  searching.value = false
  // Cancel every in-flight task (there can be more than one during rapid
  // typing before auto-debounce stabilizes).
  for (const t of pendingTasks) {
    SmbPlugin.cancelSearch({ taskId: t }).catch(() => {})
  }
  pendingTasks.clear()
  activeTaskId.value = ''
}

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/files')
}

function openResult(file) {
  if (file.isDirectory) {
    smbStore.navigateTo(file.path)
    router.push('/files')
  } else {
    router.push({
      name: 'preview',
      query: {
        name: file.name,
        type: file.type,
        size: file.size,
        path: file.path,
        shareId: smbStore.activeShareId
      }
    })
  }
}
</script>

<style lang="scss" scoped>
.search-page {
  &__statusbar-pad {
    background: var(--ink-0);
    width: 100%;
  }

  &__toolbar {
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
    min-height: 52px;

    :deep(.q-field__native) {
      color: var(--paper) !important;
      font-family: var(--font-body);
      font-size: 15px;
      &::placeholder { color: var(--paper-dim) !important; }
    }
  }

  &__scope {
    display: flex;
    align-items: center;
    padding: 14px 20px 4px;
    color: var(--paper-dim);
  }

  &__cancel {
    margin-left: auto;
  }

  &__searching {
    display: flex;
    align-items: center;
    padding: 24px 20px;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-mute);
  }
}
</style>
