<template>
  <q-item
    ref="rootEl"
    clickable
    class="row-file"
    @click="$emit('tap', file)"
    v-touch-hold.mouse="() => $emit('longpress', file)"
  >
    <q-item-section avatar class="row-file__avatar">
      <div class="row-file__avatar-wrap">
        <img
          v-if="thumbUri"
          :src="thumbUri"
          class="row-file__thumb"
          loading="lazy"
          decoding="async"
          :alt="file.name"
          @error="thumbUri = ''"
        />
        <FileTypeIcon v-else :type="file.type" size="22px" />
      </div>
    </q-item-section>

    <q-item-section class="row-file__body">
      <q-item-label class="row-file__name">{{ file.name }}</q-item-label>
      <q-item-label caption class="row-file__meta tabular">
        <template v-if="file.isDirectory">
          <span v-if="file.itemCount">{{ file.itemCount }} items</span>
          <span v-else>folder</span>
        </template>
        <template v-else>
          <span v-if="file.size">{{ formatSize(file.size) }}</span>
          <span v-if="file.modified"> · {{ formatDate(file.modified) }}</span>
          <span v-if="file.duration"> · {{ file.duration }}</span>
          <span v-if="file.resolution" class="row-file__badge">{{ file.resolution }}</span>
        </template>
      </q-item-label>
    </q-item-section>

    <q-item-section side class="row-file__side">
      <q-icon
        v-if="originIcon"
        :name="originIcon"
        size="16px"
        class="row-file__origin"
        :class="`row-file__origin--${file.origin}`"
        :aria-label="originLabel"
      />
      <q-icon name="mdi-chevron-right" size="18px" />
    </q-item-section>
  </q-item>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import FileTypeIcon from './FileTypeIcon.vue'
import { formatSize, formatDate } from '../utils/helpers'
import { useThumbnailStore } from '../stores/thumbnails'

const props = defineProps({
  file: { type: Object, required: true }
})
defineEmits(['tap', 'longpress'])

// Origin badge on the right side — Recents entries carry an `origin` tag
// that distinguishes where the entry came from.  The file-browser and
// starred lists don't set it (falls back to browsed → no badge).
const ORIGIN_ICONS = {
  uploaded: 'mdi-cloud-upload-outline',
  downloaded: 'mdi-cloud-download-outline',
  'backed-up': 'mdi-backup-restore'
}
const ORIGIN_LABELS = {
  uploaded: 'Uploaded',
  downloaded: 'Downloaded',
  'backed-up': 'Backed up'
}
const originIcon = computed(() => ORIGIN_ICONS[props.file?.origin] || '')
const originLabel = computed(() => ORIGIN_LABELS[props.file?.origin] || '')

const rootEl = ref(null)
const thumbUri = ref('')
const thumbs = useThumbnailStore()

let observer = null
onMounted(() => {
  const el = rootEl.value?.$el || rootEl.value
  if (!el || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        thumbs.get(props.file).then(u => { thumbUri.value = u || '' })
        observer?.disconnect()
        observer = null
        return
      }
    }
  }, { rootMargin: '100px' })
  observer.observe(el)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<style lang="scss" scoped>
.row-file {
  padding: 14px 20px;
  border-bottom: 1px solid var(--rule-soft);
  min-height: 64px;
  transition: background var(--dur-fast) var(--ease-archive);

  &:hover { background: var(--ink-1); }
  &:active { background: var(--ink-2); }

  &__avatar {
    min-width: 40px;
    padding-right: 16px;
  }

  &__avatar-wrap {
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--r-sm);
    overflow: hidden;
    background: var(--ink-1);
  }

  &__thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &__name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 15px;
    color: var(--paper);
    line-height: 1.3;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    letter-spacing: 0;
    margin-top: 2px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__badge {
    color: var(--marigold);
    font-size: 11px;
    letter-spacing: 0.04em;
    margin-left: 6px;
  }

  &__side {
    color: var(--paper-dim);
    flex-direction: row;
    align-items: center;
    gap: 8px;
  }

  &__origin {
    &--uploaded { color: var(--marigold); }
    &--downloaded { color: var(--slate, #6FA8DC); }
    &--backed-up { color: var(--moss, #7EA577); }
  }
}
</style>
