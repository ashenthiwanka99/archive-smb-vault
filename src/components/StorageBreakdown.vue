<template>
  <div class="storage-breakdown">
    <div class="storage-breakdown__head">
      <div class="storage-breakdown__title caption-sc">What's in this folder</div>
      <button
        class="btn-ghost storage-breakdown__refresh"
        :disabled="loading"
        @click="load"
        aria-label="Refresh breakdown"
      >
        <q-icon :name="loading ? 'mdi-loading' : 'mdi-refresh'" size="14px" />
      </button>
    </div>

    <div v-if="loading && items.length === 0" class="storage-breakdown__skeleton">
      <div v-for="i in 4" :key="i" class="skeleton-shimmer" style="height: 38px;" />
    </div>

    <div v-else-if="error" class="storage-breakdown__error">
      {{ error }}
    </div>

    <div v-else-if="items.length === 0" class="storage-breakdown__empty">
      Empty — nothing on this shelf yet.
    </div>

    <ul v-else class="storage-breakdown__list">
      <li
        v-for="item in sortedItems"
        :key="item.name"
        class="storage-breakdown__row"
      >
        <div class="storage-breakdown__row-head">
          <div class="storage-breakdown__row-icon">
            <q-icon
              :name="item.isDirectory ? 'mdi-folder-outline' : 'mdi-file-multiple-outline'"
              size="var(--icon-sm)"
              class="paper-mute"
            />
          </div>
          <div class="storage-breakdown__row-name">{{ item.name }}</div>
          <div class="storage-breakdown__row-size tabular">
            {{ formatSize(item.size) }}
          </div>
        </div>
        <div class="storage-breakdown__bar">
          <div
            class="storage-breakdown__bar-fill"
            :style="{ width: barPct(item.size) + '%' }"
          />
        </div>
        <div class="storage-breakdown__row-meta caption-sc tabular">
          {{ item.fileCount.toLocaleString('en-US') }} files
          · {{ pctOfTotal(item.size).toFixed(1) }}% of shelf
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { useSettingsStore } from '../stores/settings'
import { formatSize } from '../utils/helpers'
import { friendlyError } from '../utils/sanitize'

const props = defineProps({
  shareId: { type: String, required: true },
  path: { type: String, default: '' }
})

const settingsStore = useSettingsStore()
const items = ref([])
const loading = ref(false)
const error = ref('')

const sortedItems = computed(() =>
  [...items.value].sort((a, b) => (b.size || 0) - (a.size || 0))
)

const total = computed(() =>
  items.value.reduce((s, i) => s + (i.size || 0), 0)
)

const maxSize = computed(() =>
  items.value.reduce((m, i) => Math.max(m, i.size || 0), 0)
)

function barPct(size) {
  if (!maxSize.value) return 0
  return Math.max(1.5, (size / maxSize.value) * 100)
}

function pctOfTotal(size) {
  if (!total.value) return 0
  return (size / total.value) * 100
}

async function load() {
  const share = settingsStore.shares.find(s => s.id === props.shareId)
  if (!share) { error.value = 'Share not configured'; return }
  const creds = await settingsStore.getShareCredentials(share.id)
  loading.value = true
  error.value = ''
  try {
    const res = await SmbPlugin.getFolderBreakdown({
      host: share.host,
      share: share.shareName,
      path: props.path,
      username: creds.username || '',
      password: creds.password || ''
    })
    items.value = res.items || []
  } catch (e) {
    error.value = friendlyError(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => [props.shareId, props.path], load)
</script>

<style lang="scss" scoped>
.storage-breakdown {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 14px 16px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  &__title {
    color: var(--paper-mute);
    letter-spacing: 0.08em;
  }

  &__refresh {
    padding: 4px 8px;
  }

  &__skeleton {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__error {
    color: var(--rust);
    font-size: 12px;
    padding: 8px 0;
  }

  &__empty {
    color: var(--paper-mute);
    font-size: 12px;
    padding: 8px 0;
  }

  &__list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__row {
    padding: 8px 0;
    border-top: 1px solid var(--rule-soft);
    &:first-child { border-top: none; padding-top: 0; }
  }

  &__row-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }

  &__row-icon {
    display: inline-flex;
    flex-shrink: 0;
  }

  &__row-name {
    flex: 1;
    min-width: 0;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__row-size {
    color: var(--paper);
    font-size: 12px;
    flex-shrink: 0;
  }

  &__bar {
    width: 100%;
    height: 4px;
    background: var(--ink-1);
    border-radius: 2px;
    overflow: hidden;
  }

  &__bar-fill {
    height: 100%;
    background: linear-gradient(to right, var(--marigold), var(--slate));
    transition: width 300ms ease;
  }

  &__row-meta {
    font-size: 10px;
    color: var(--paper-dim);
    margin-top: 4px;
    letter-spacing: 0.04em;
  }
}
</style>
