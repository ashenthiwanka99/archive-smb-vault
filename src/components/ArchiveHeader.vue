<template>
  <header class="archive-header">
    <div class="archive-header__row">
      <div class="archive-header__brand">
        <span class="archive-header__wordmark">Archive</span>
        <span class="archive-header__period">.</span>
      </div>

      <button
        class="archive-header__icon-btn"
        aria-label="Search"
        @click="$emit('open-search')"
      >
        <q-icon name="mdi-magnify" size="22px" />
      </button>

      <button
        class="archive-header__icon-btn"
        aria-label="Downloads"
        @click="$emit('open-downloads')"
      >
        <q-icon name="mdi-download-outline" size="22px" />
        <span
          v-if="downloadBadge > 0"
          class="archive-header__badge tabular"
          :aria-label="`${downloadBadge} active download${downloadBadge === 1 ? '' : 's'}`"
        >{{ downloadBadge > 9 ? '9+' : downloadBadge }}</span>
      </button>

      <button
        class="archive-header__icon-btn"
        aria-label="Starred"
        @click="$emit('open-starred')"
      >
        <q-icon name="mdi-star-outline" size="22px" />
      </button>

      <NetworkStatus :status="connectionStatus" @toggle="$emit('toggle-connection')" />
    </div>
  </header>
</template>

<script setup>
import NetworkStatus from './NetworkStatus.vue'

defineProps({
  connectionStatus: { type: String, default: 'offline' },
  downloadBadge: { type: Number, default: 0 }
})
defineEmits(['open-search', 'open-downloads', 'open-starred', 'toggle-connection'])
</script>

<style lang="scss" scoped>
.archive-header {
  background: var(--ink-0);
  padding-top: calc(env(safe-area-inset-top, 0px) + 14px);
  padding-right: 12px;
  padding-bottom: 14px;
  padding-left: 20px;
  min-height: calc(env(safe-area-inset-top, 0px) + 56px);
  border-bottom: 1px solid var(--rule);

  &__row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  &__brand {
    flex: 1;
    min-width: 0;
    display: inline-flex;
    align-items: baseline;
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 26px;
    line-height: 1;
    letter-spacing: -0.02em;
    color: var(--paper);
  }

  &__period {
    color: var(--marigold);
    margin-left: 1px;
  }

  &__icon-btn {
    position: relative;
    width: 40px;
    height: 40px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    transition: background var(--dur-fast) var(--ease-archive);

    &:active { background: var(--ink-2); }
  }

  &__badge {
    position: absolute;
    top: 4px;
    right: 2px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    border-radius: 8px;
    background: var(--rust);
    color: var(--paper);
    font-family: var(--font-body);
    font-size: 10px;
    font-weight: 600;
    line-height: 16px;
    text-align: center;
    border: 2px solid var(--ink-0);
  }
}
</style>
