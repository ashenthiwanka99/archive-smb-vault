<template>
  <button
    class="shelf-row"
    :class="{ 'shelf-row--selected': selected }"
    :aria-pressed="selected"
    @click="$emit('toggle', shelf)"
  >
    <q-checkbox
      :model-value="selected"
      color="primary"
      dense
      class="shelf-row__check"
      @click.stop="$emit('toggle', shelf)"
    />

    <div class="shelf-row__body">
      <div class="shelf-row__label">{{ shelf.label }}</div>
      <div class="shelf-row__meta tabular">
        {{ shelf.total.toLocaleString('en-US') }} items
        <span v-if="shelf.totalSize" class="shelf-row__meta-sep">·</span>
        <span v-if="shelf.totalSize">{{ shelf.sizeLabel }}</span>
      </div>

      <div class="shelf-row__progress">
        <div class="shelf-row__progress-track">
          <div
            class="shelf-row__progress-fill"
            :style="{ width: progressPct + '%' }"
          />
        </div>
        <div class="shelf-row__progress-label tabular">
          {{ shelf.catalogued }} of {{ shelf.total }} backed up
        </div>
      </div>
    </div>
  </button>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  shelf: { type: Object, required: true },
  selected: { type: Boolean, default: false }
})
defineEmits(['toggle'])

const progressPct = computed(() => {
  const t = props.shelf.total
  if (!t) return 0
  return Math.min(100, Math.max(0, (props.shelf.catalogued / t) * 100))
})
</script>

<style lang="scss" scoped>
.shelf-row {
  width: 100%;
  display: grid;
  grid-template-columns: 22px 1fr;
  align-items: start;
  column-gap: 12px;
  padding: 14px 14px 14px 10px;
  border: 1px solid transparent;
  background: transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
  text-align: left;
  transition: background var(--dur-fast) var(--ease-archive),
              border-color var(--dur-fast) var(--ease-archive);

  &:hover {
    background: var(--ink-1);
  }

  &--selected {
    background: rgba(232, 163, 61, 0.06);
    border-color: rgba(232, 163, 61, 0.25);
  }

  &__check {
    width: 22px;
    height: 22px;
    margin-top: -1px;

    :deep(.q-checkbox__inner) {
      width: 22px;
      min-width: 22px;
      height: 22px;
      padding: 0;
    }
    :deep(.q-checkbox__bg) {
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      width: 22px;
      height: 22px;
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__label {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    line-height: 1.3;
    word-break: break-word;
  }

  &--selected .shelf-row__label {
    color: var(--marigold);
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-mute);
    letter-spacing: 0.04em;
    margin-top: 3px;
  }

  &__meta-sep {
    margin: 0 4px;
    color: var(--paper-dim);
  }

  &__progress {
    margin-top: 8px;
    display: flex;
    align-items: center;
    gap: 10px;
  }

  &__progress-track {
    flex: 1;
    height: 2px;
    background: var(--ink-2);
    border-radius: 1px;
    overflow: hidden;
  }

  &__progress-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 0.3s var(--ease-archive);
  }

  &__progress-label {
    font-family: var(--font-body);
    font-size: 10px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }
}
</style>
