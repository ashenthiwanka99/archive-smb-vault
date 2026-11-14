<template>
  <transition name="slide-down">
    <div v-if="uploading" class="upload-progress">
      <div class="upload-progress__content row items-center no-wrap q-px-md">
        <q-spinner-tail color="primary" size="16px" class="q-mr-md" />

        <div class="col upload-progress__info">
          <div class="upload-progress__counter">
            Uploading · {{ progress?.current || 0 }} of {{ progress?.total || 0 }}
          </div>
          <div class="upload-progress__filename">
            {{ progress?.fileName || '' }}
          </div>
        </div>

        <q-btn
          flat dense round
          icon="mdi-close"
          size="md"
          class="rust"
          aria-label="Cancel upload"
          @click="$emit('cancel')"
        />
      </div>

      <div class="upload-progress__bar">
        <div
          class="upload-progress__bar-fill"
          :style="{ width: barPercent + '%' }"
        />
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  uploading: { type: Boolean, default: false },
  progress: {
    type: Object,
    default: () => ({ current: 0, total: 0, fileName: '' })
  }
})

defineEmits(['cancel'])

const barPercent = computed(() => {
  if (!props.progress?.total) return 0
  return (props.progress.current / props.progress.total) * 100
})
</script>

<style lang="scss" scoped>
.upload-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  padding-top: env(safe-area-inset-top, 0px);
  background: var(--ink-1);
  border-bottom: 1px solid var(--rule);
  z-index: 50;

  &__content { height: 46px; }

  &__info { overflow: hidden; }

  &__counter {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.14em;
    text-transform: uppercase;
  }

  &__filename {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 2px;
  }

  &__bar {
    height: 1px;
    background: var(--rule-soft);
  }

  &__bar-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 0.3s var(--ease-archive);
  }
}

.slide-down-enter-active,
.slide-down-leave-active {
  transition: transform 0.3s var(--ease-archive), opacity 0.3s var(--ease-archive);
}

.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-100%);
  opacity: 0;
}
</style>
