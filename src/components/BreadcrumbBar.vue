<template>
  <nav class="crumbs" aria-label="Folder path">
    <button
      v-if="crumbs.length > 1"
      class="crumbs__back"
      aria-label="Up one folder"
      @click="$emit('navigate', crumbs[crumbs.length - 2].path)"
    >
      <q-icon name="mdi-chevron-left" size="var(--icon-md)" />
    </button>

    <div ref="trackRef" class="crumbs__track">
      <button
        v-for="(crumb, idx) in crumbs"
        :key="idx"
        class="crumbs__item"
        :class="{ 'crumbs__item--active': idx === crumbs.length - 1 }"
        @click="onCrumbClick(idx, crumb)"
        v-touch-hold:600.mouse="() => onCrumbLongPress(crumb)"
      >
        <span v-if="idx > 0" class="crumbs__sep" aria-hidden="true">›</span>
        <span class="crumbs__label">{{ crumb.label }}</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { useQuasar } from 'quasar'

const props = defineProps({
  crumbs: { type: Array, default: () => [] }
})
const emit = defineEmits(['navigate'])

const $q = useQuasar()
const trackRef = ref(null)

function onCrumbClick(idx, crumb) {
  if (idx === props.crumbs.length - 1) return  // can't navigate to current
  emit('navigate', crumb.path)
}

async function onCrumbLongPress(crumb) {
  // Copy the full path of this crumb to clipboard — useful for power users
  // who want to paste a path into move/copy dialogs or other apps.
  const path = crumb?.path || ''
  const display = path || '(shelf root)'
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(display)
    } else {
      // Fallback for older WebViews — synthesize a temporary textarea.
      const ta = document.createElement('textarea')
      ta.value = display
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    $q.notify({ message: 'Path copied', color: 'positive', textColor: 'dark', timeout: 1500 })
  } catch {
    $q.notify({ message: 'Could not copy path', color: 'negative', timeout: 2000 })
  }
}

// Auto-scroll the trail so the deepest folder is always visible.
watch(() => props.crumbs.length, async () => {
  await nextTick()
  if (trackRef.value) {
    trackRef.value.scrollLeft = trackRef.value.scrollWidth
  }
}, { immediate: true })
</script>

<style lang="scss" scoped>
.crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px 8px 8px;
  border-bottom: 1px solid var(--rule-soft);

  &__back {
    width: 36px;
    height: 36px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper-mute);
    cursor: pointer;
    border-radius: 50%;
    transition: background var(--dur-fast) var(--ease-archive), color var(--dur-fast) var(--ease-archive);

    &:hover { color: var(--paper); background: var(--ink-1); }
    &:active { background: var(--ink-2); }
  }

  &__track {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow-x: auto;
    scroll-behavior: smooth;
    scrollbar-width: none;

    &::-webkit-scrollbar { display: none; }
  }

  &__item {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    border: none;
    background: transparent;
    padding: 6px 2px;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-mute);
    cursor: pointer;
    transition: color var(--dur-fast) var(--ease-archive);

    &:hover:not(:disabled) { color: var(--paper); }

    &--active {
      color: var(--paper);
      font-weight: 500;
      cursor: default;
    }

    &:disabled { cursor: default; }
  }

  &__sep {
    color: var(--paper-dim);
    margin: 0 6px;
    font-size: 14px;
  }

  &__label {
    white-space: nowrap;
    max-width: 180px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
