<template>
  <div
    ref="rootEl"
    class="file-grid"
    :class="{
      'file-grid--has-thumb': thumbUri,
      'file-grid--selected': isSelected
    }"
    role="button"
    :aria-label="file.name"
    :aria-pressed="isSelected"
    @click="onTap"
    v-touch-hold.mouse="onLongPress"
  >
    <div v-if="smbStore.selectionMode" class="file-grid__check">
      <q-icon
        :name="isSelected ? 'mdi-checkbox-marked-circle' : 'mdi-checkbox-blank-circle-outline'"
        size="24px"
        :class="isSelected ? 'marigold' : 'paper-dim'"
      />
    </div>
    <div class="file-grid__icon">
      <img
        v-if="thumbUri"
        :src="thumbUri"
        class="file-grid__thumb"
        loading="lazy"
        decoding="async"
        :alt="file.name"
        @error="onThumbError"
      />
      <FileTypeIcon v-else :type="file.type" size="36px" />
    </div>
    <div class="file-grid__name" :title="file.name">{{ file.name }}</div>
    <div v-if="file.size" class="file-grid__size">{{ formatSize(file.size) }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import FileTypeIcon from './FileTypeIcon.vue'
import { formatSize } from '../utils/helpers'
import { useThumbnailStore } from '../stores/thumbnails'
import { useSmbStore } from '../stores/smb'

const props = defineProps({
  file: { type: Object, required: true }
})

const emit = defineEmits(['tap', 'longpress'])

const smbStore = useSmbStore()
const isSelected = computed(() => smbStore.isSelected(props.file.name))

function onTap() {
  if (smbStore.selectionMode) {
    smbStore.toggleSelection(props.file)
    return
  }
  emit('tap', props.file)
}

function onLongPress() {
  if (!smbStore.selectionMode) {
    smbStore.enterSelection(props.file)
  } else {
    smbStore.toggleSelection(props.file)
  }
  emit('longpress', props.file)
}

const rootEl = ref(null)
const thumbUri = ref('')
const thumbs = useThumbnailStore()

let observer = null
onMounted(() => {
  if (!rootEl.value || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        loadThumb()
        observer?.disconnect()
        observer = null
        return
      }
    }
  }, { rootMargin: '200px' })
  observer.observe(rootEl.value)
})
onBeforeUnmount(() => observer?.disconnect())

async function loadThumb() {
  const uri = await thumbs.get(props.file)
  thumbUri.value = uri || ''
}

function onThumbError() {
  thumbUri.value = ''
}
</script>

<style lang="scss" scoped>
.file-grid {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: flex-end;
  padding: 18px 14px 14px;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--ink-1);
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease-archive), background var(--dur-fast) var(--ease-archive);
  min-height: 118px;
  aspect-ratio: 1;

  &:hover, &:active {
    border-color: var(--paper-mute);
    background: var(--ink-2);
  }

  &--selected {
    border-color: var(--marigold);
    background: var(--marigold-soft);
  }

  &__check {
    position: absolute;
    top: 8px;
    right: 8px;
    z-index: 2;
    background: var(--ink-0);
    border-radius: 50%;
    padding: 2px;
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: auto;
    width: 100%;
    aspect-ratio: 1;
    overflow: hidden;
    border-radius: var(--r-sm);
  }

  &__thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  &--has-thumb .file-grid__icon {
    margin-bottom: 8px;
  }

  &__name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 13px;
    color: var(--paper);
    text-align: left;
    word-break: break-all;
    line-height: 1.3;
    margin-top: 16px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__size {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
    margin-top: 4px;
    font-variant-numeric: tabular-nums;
  }
}
</style>
