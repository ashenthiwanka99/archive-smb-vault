<template>
  <div class="swipe-row" ref="rowEl" :class="{ 'swipe-row--selected': isSelected }">
    <!-- Action buttons revealed on swipe (hidden in selection mode) -->
    <div v-if="!smbStore.selectionMode" class="swipe-row__actions">
      <button
        class="swipe-row__action swipe-row__action--download"
        :aria-label="`Download ${file.name}`"
        @click="$emit('download', file)"
      >
        <q-icon name="mdi-download" size="var(--icon-md)" />
      </button>
      <button
        class="swipe-row__action swipe-row__action--star"
        :aria-label="`Star ${file.name}`"
        @click="$emit('star', file)"
      >
        <q-icon name="mdi-star" size="var(--icon-md)" />
      </button>
      <button
        class="swipe-row__action swipe-row__action--delete"
        :aria-label="`Delete ${file.name}`"
        @click="$emit('delete', file)"
      >
        <q-icon name="mdi-delete" size="var(--icon-md)" />
      </button>
    </div>

    <!-- Swipeable content -->
    <div
      class="swipe-row__content"
      :style="{ transform: `translateX(${smbStore.selectionMode ? 0 : offsetX}px)` }"
      @touchstart="onTouchStart"
      @touchmove="onTouchMove"
      @touchend="onTouchEnd"
      @click="onTap"
      v-touch-hold.mouse="onLongPress"
    >
      <q-item class="file-row">
        <q-item-section avatar>
          <div class="file-row__icon-wrap">
            <!-- Checkbox overlay in selection mode -->
            <div v-if="smbStore.selectionMode" class="file-row__check">
              <q-icon
                :name="isSelected ? 'mdi-checkbox-marked-circle' : 'mdi-checkbox-blank-circle-outline'"
                size="24px"
                :class="isSelected ? 'marigold' : 'paper-dim'"
              />
            </div>
            <template v-else>
              <img
                v-if="thumbUri"
                :src="thumbUri"
                class="file-row__thumb"
                loading="lazy"
                decoding="async"
                :alt="file.name"
                @error="onThumbError"
              />
              <FileTypeIcon v-else :type="file.type" />
            </template>
          </div>
        </q-item-section>

        <q-item-section>
          <q-item-label class="file-row__name">{{ file.name }}</q-item-label>
          <q-item-label caption class="file-row__meta">
            <template v-if="file.isDirectory">
              <span v-if="file.itemCount">{{ file.itemCount }} items</span>
            </template>
            <span v-if="file.size">{{ formatSize(file.size) }}</span>
            <span v-if="file.modified"> · {{ formatDate(file.modified) }}</span>
          </q-item-label>
        </q-item-section>

        <q-item-section side v-if="!smbStore.selectionMode">
          <q-icon name="mdi-chevron-right" color="grey-8" size="20px" />
        </q-item-section>
      </q-item>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import FileTypeIcon from './FileTypeIcon.vue'
import { formatSize, formatDate } from '../utils/helpers'
import { useThumbnailStore } from '../stores/thumbnails'
import { useSmbStore } from '../stores/smb'

const props = defineProps({
  file: { type: Object, required: true }
})

const emit = defineEmits(['tap', 'download', 'delete', 'star'])

const smbStore = useSmbStore()
const isSelected = computed(() => smbStore.isSelected(props.file.name))

const rowEl = ref(null)
const offsetX = ref(0)
const actionsWidth = 180 // 3 buttons x 60px
const thumbUri = ref('')

const thumbs = useThumbnailStore()

// Lazy-load the thumbnail only when the row actually scrolls into view.  No
// point queueing 500 fetches when the user only sees 10 rows at once.
let observer = null
onMounted(() => {
  if (!rowEl.value || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        loadThumb()
        observer?.disconnect()
        observer = null
        return
      }
    }
  }, { rootMargin: '100px' })
  observer.observe(rowEl.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
})

async function loadThumb() {
  const uri = await thumbs.get(props.file)
  thumbUri.value = uri || ''
}

function onThumbError() {
  thumbUri.value = ''
}

let startX = 0
let startY = 0
let startOffset = 0
let isSwiping = false
let isSwipeDecided = false

function onTouchStart(e) {
  const touch = e.touches[0]
  startX = touch.clientX
  startY = touch.clientY
  startOffset = offsetX.value
  isSwiping = false
  isSwipeDecided = false
}

function onTouchMove(e) {
  const touch = e.touches[0]
  const dx = touch.clientX - startX
  const dy = touch.clientY - startY

  // Decide if this is a horizontal swipe or vertical scroll
  if (!isSwipeDecided) {
    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
      isSwipeDecided = true
      isSwiping = Math.abs(dx) > Math.abs(dy)
    }
    return
  }

  if (!isSwiping) return

  e.preventDefault()
  let newOffset = startOffset + dx
  // Clamp: no right swipe beyond 0, no left swipe beyond actions width
  newOffset = Math.max(-actionsWidth, Math.min(0, newOffset))
  offsetX.value = newOffset
}

function onTouchEnd() {
  if (!isSwiping) return

  // Snap open or closed
  if (offsetX.value < -80) {
    offsetX.value = -actionsWidth
  } else {
    offsetX.value = 0
  }
}

function onTap() {
  if (isSwiping) return
  // Close if open
  if (offsetX.value !== 0) {
    offsetX.value = 0
    return
  }
  // In selection mode, taps toggle selection instead of opening the file.
  if (smbStore.selectionMode) {
    smbStore.toggleSelection(props.file)
    return
  }
  emit('tap', props.file)
}

function onLongPress() {
  // Long-press is how users enter selection mode on list + grid.  Once in,
  // tapping additional rows toggles them (see onTap).
  if (!smbStore.selectionMode) {
    smbStore.enterSelection(props.file)
  } else {
    smbStore.toggleSelection(props.file)
  }
}
</script>

<style lang="scss" scoped>
.swipe-row {
  position: relative;
  overflow: hidden;
  border-bottom: 1px solid var(--rule-soft);

  &--selected {
    background: var(--marigold-soft);
  }

  &__actions {
    position: absolute;
    right: 0;
    top: 0;
    bottom: 0;
    display: flex;
    align-items: stretch;
    background: var(--ink-2);
  }

  &__action {
    width: 60px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    cursor: pointer;
    background: transparent;
    color: var(--paper);
    transition: background var(--dur-fast) var(--ease-archive), color var(--dur-fast) var(--ease-archive);

    &:active { background: var(--ink-3); }

    &--download { color: var(--marigold); }
    &--star     { color: var(--marigold); }
    &--delete   { color: var(--rust); }
  }

  &__content {
    position: relative;
    z-index: 1;
    background: var(--ink-0);
    transition: transform 0.25s var(--ease-archive);
    will-change: transform;
  }
}

.file-row {
  padding: 14px 20px;
  min-height: 64px;

  &__icon-wrap {
    width: 40px;
    height: 40px;
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
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
