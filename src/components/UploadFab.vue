<template>
  <div class="speed-dial">
    <!-- Tap-outside backdrop when expanded (transparent so layout isn't obscured). -->
    <div
      v-if="open"
      class="speed-dial__backdrop"
      @click="close"
    />

    <!-- Mini FABs with labels. Reverse order so top item appears last in DOM -->
    <!-- but sits highest visually — enter animation staggers upward. -->
    <transition-group name="sd-item" tag="div" class="speed-dial__items">
      <div
        v-for="(action, i) in visibleActions"
        :key="action.id"
        class="speed-dial__item"
        :style="{ '--i': i }"
      >
        <span class="speed-dial__label">{{ action.label }}</span>
        <button
          class="speed-dial__mini"
          :aria-label="action.label"
          @click="choose(action.id)"
        >
          <q-icon :name="action.icon" size="20px" />
        </button>
      </div>
    </transition-group>

    <!-- Primary FAB -->
    <button
      class="speed-dial__fab"
      :class="{ 'speed-dial__fab--open': open }"
      :aria-label="open ? 'Close actions' : 'Add to this shelf'"
      :aria-expanded="open"
      @click="toggle"
    >
      <q-icon name="mdi-plus" size="24px" class="speed-dial__fab-icon" />
    </button>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const emit = defineEmits(['upload-files', 'upload-photos', 'new-folder'])

const open = ref(false)

const actions = [
  { id: 'new-folder',    label: 'New folder',    icon: 'mdi-folder-plus-outline' },
  { id: 'upload-photos', label: 'Upload photos', icon: 'mdi-image-plus-outline' },
  { id: 'upload-files',  label: 'Upload files',  icon: 'mdi-file-upload-outline' }
]

// When closed, render nothing (transition-group animates out); when open, all.
const visibleActions = computed(() => open.value ? actions : [])

function toggle() { open.value = !open.value }
function close()  { open.value = false }

function choose(id) {
  close()
  // Fire after the close animation so the sheet doesn't linger over the picker.
  setTimeout(() => emit(id), 120)
}
</script>

<style lang="scss" scoped>
.speed-dial {
  position: fixed;
  bottom: calc(var(--tab-height, 80px) + 20px);
  right: 20px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.speed-dial__backdrop {
  position: fixed;
  inset: 0;
  // Sit above the page content so taps close the speed-dial instead of
  // falling through to whatever file row is under the finger.  The FAB and
  // mini FABs sit on higher z-index layers inside .speed-dial.
  z-index: 99;
  background: transparent;
}

.speed-dial__items {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 14px;
}

.speed-dial__item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.speed-dial__label {
  font-family: var(--font-body);
  font-weight: 500;
  font-size: 13px;
  color: var(--paper);
  background: var(--ink-1);
  border: 1px solid var(--rule);
  padding: 6px 12px;
  border-radius: var(--r-sm);
  white-space: nowrap;
  box-shadow: 0 6px 16px -8px rgba(0, 0, 0, 0.5);
}

.speed-dial__mini {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--rule);
  background: var(--ink-1);
  color: var(--marigold);
  cursor: pointer;
  border-radius: 50%;
  box-shadow: 0 8px 20px -8px rgba(0, 0, 0, 0.6);
  transition: background var(--dur-fast) var(--ease-archive),
              transform var(--dur-fast) var(--ease-archive);

  &:hover { background: var(--ink-2); }
  &:active { transform: scale(0.92); }
}

.speed-dial__fab {
  width: 56px;
  height: 56px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: var(--marigold);
  color: var(--ink-0);
  cursor: pointer;
  border-radius: 50%;
  box-shadow: 0 10px 24px -8px rgba(232, 163, 61, 0.55),
              0 4px 8px rgba(0, 0, 0, 0.4);
  transition: background var(--dur-fast) var(--ease-archive),
              transform var(--dur-base) cubic-bezier(0.34, 1.56, 0.64, 1),
              box-shadow var(--dur-base) var(--ease-archive);

  &:hover { background: #F0B255; }
  &:active { transform: scale(0.94); }

  &--open {
    background: var(--ink-1);
    color: var(--paper);
    box-shadow: 0 10px 24px -8px rgba(0, 0, 0, 0.6);

    .speed-dial__fab-icon {
      transform: rotate(45deg);
    }
  }
}

.speed-dial__fab-icon {
  transition: transform var(--dur-base) cubic-bezier(0.34, 1.56, 0.64, 1);
}

// Per-item staggered enter/leave — `--i` is the index (0..n-1).
.sd-item-enter-active {
  transition: opacity 220ms var(--ease-archive),
              transform 280ms cubic-bezier(0.34, 1.56, 0.64, 1);
  transition-delay: calc(var(--i) * 40ms);
}
.sd-item-leave-active {
  transition: opacity 120ms var(--ease-archive),
              transform 160ms var(--ease-archive);
}
.sd-item-enter-from {
  opacity: 0;
  transform: translateY(16px) scale(0.8);
}
.sd-item-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.92);
}
</style>
