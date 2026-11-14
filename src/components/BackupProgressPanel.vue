<template>
  <section class="bp-panel">
    <div class="bp-panel__head">
      <div class="bp-panel__title">
        {{ phase === 'scanning' ? 'Scanning…' : 'Backing up' }}
      </div>
      <div class="bp-panel__count tabular" v-if="total > 0">
        {{ current }} <span class="bp-panel__of">of</span> {{ total }}
      </div>
    </div>

    <q-linear-progress
      :value="total > 0 ? current / total : 0"
      :indeterminate="total === 0 || phase === 'scanning'"
      size="3px"
      color="primary"
      track-color="dark"
      class="bp-panel__bar"
    />

    <div class="bp-panel__file">
      <q-icon name="mdi-file-upload-outline" size="14px" class="bp-panel__file-icon" />
      <span class="bp-panel__file-name">
        {{ fileName || (phase === 'scanning' ? 'Finding new media…' : 'Preparing…') }}
      </span>
    </div>

    <div class="bp-panel__meta">
      <span v-if="destName" class="bp-panel__meta-part">→ {{ destName }}</span>
      <span v-if="total > 0 && current < total" class="bp-panel__meta-part">
        {{ total - current }} remaining
      </span>
      <span v-if="etaSeconds > 0 && phase !== 'scanning'" class="bp-panel__meta-part bp-panel__meta-eta">
        · {{ formatEta(etaSeconds) }} left
      </span>
    </div>

    <div class="bp-panel__actions">
      <button class="btn-archive bp-panel__stop" @click="$emit('stop')">
        <q-icon name="mdi-stop-circle-outline" size="15px" />
        Stop
      </button>
    </div>
  </section>
</template>

<script setup>
defineProps({
  current: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  fileName: { type: String, default: '' },
  phase: { type: String, default: '' },
  destName: { type: String, default: '' },
  etaSeconds: { type: Number, default: 0 }
})
defineEmits(['stop'])

function formatEta(seconds) {
  if (!seconds || seconds <= 0) return ''
  if (seconds < 60) return `${Math.round(seconds)}s`
  const mins = Math.round(seconds / 60)
  if (mins < 60) return `${mins} min`
  const hrs = Math.floor(mins / 60)
  const rem = mins % 60
  return rem ? `${hrs}h ${rem}m` : `${hrs}h`
}
</script>

<style lang="scss" scoped>
.bp-panel {
  margin: 16px 20px 0;
  padding: 18px 20px;
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  border-left: 2px solid var(--marigold);

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 10px;
  }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 15px;
    color: var(--paper);
    letter-spacing: -0.01em;
  }

  &__count {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 13px;
    color: var(--marigold);
    font-variant-numeric: tabular-nums;
  }

  &__of {
    color: var(--paper-dim);
    font-weight: 400;
  }

  &__bar {
    margin-bottom: 12px;
    border-radius: 2px;
    overflow: hidden;
  }

  &__file {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--paper);
    font-family: var(--font-body);
    font-size: 13px;
    overflow: hidden;
  }

  &__file-icon { color: var(--paper-mute); flex-shrink: 0; }

  &__file-name {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    flex: 1;
    min-width: 0;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.04em;
    margin-top: 6px;
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
  }

  &__meta-part { white-space: nowrap; }

  &__actions {
    margin-top: 14px;
    display: flex;
    justify-content: flex-end;
  }

  &__stop {
    background: var(--rust) !important;
    color: var(--ink-0) !important;
  }
}
</style>
