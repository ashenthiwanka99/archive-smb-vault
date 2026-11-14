<template>
  <div class="dl-item">
    <div class="dl-item__row">
      <FileTypeIcon :type="item.type || 'unknown'" size="20px" class="q-mr-md" />
      <div class="col dl-item__body">
        <div class="dl-item__name">{{ item.name }}</div>
        <div class="dl-item__meta tabular">
          <span class="dl-item__size">{{ formatSize(item.size) }}</span>
          <span v-if="item.status === 'queued'" class="paper-dim"> · awaiting</span>
          <span v-if="item.status === 'downloading'" class="marigold"> · {{ item.progress }}%</span>
          <span v-if="item.status === 'completed'" class="moss"> · fetched</span>
          <span v-if="item.status === 'failed'" class="rust"> · flagged</span>
        </div>
      </div>
      <q-btn
        v-if="item.status === 'failed'"
        flat dense round
        icon="mdi-refresh"
        size="md"
        class="marigold"
        :aria-label="`Retry ${item.name}`"
        @click="$emit('retry', item.id)"
      />
      <q-btn
        v-if="item.status === 'queued' || item.status === 'downloading'"
        flat dense round
        icon="mdi-close"
        size="md"
        class="paper-dim"
        :aria-label="`Cancel ${item.name}`"
        @click="$emit('cancel', item.id)"
      />
    </div>

    <q-linear-progress
      v-if="item.status === 'downloading' || item.status === 'queued'"
      :value="item.progress / 100"
      :indeterminate="item.status === 'downloading' && item.progress < 10"
      class="dl-item__bar"
      size="1px"
      color="primary"
      track-color="dark"
    />
    <div v-if="item.status === 'failed' && item.error" class="dl-item__error">
      {{ item.error }}
    </div>
  </div>
</template>

<script setup>
import FileTypeIcon from './FileTypeIcon.vue'
import { formatSize } from '../utils/helpers'

defineProps({
  item: { type: Object, required: true }
})
defineEmits(['retry', 'cancel'])
</script>

<style lang="scss" scoped>
.dl-item {
  padding: 14px 20px;
  border-bottom: 1px solid var(--rule-soft);

  &__row {
    display: flex;
    align-items: flex-start;
    gap: 4px;
  }

  &__body {
    min-width: 0;
    flex: 1;
  }

  &__name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    word-break: break-all;
    overflow-wrap: anywhere;
    line-height: 1.35;
  }

  &__meta {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 2px;
  }

  &__bar {
    margin-top: 10px;
    background: var(--rule-soft);
  }

  &__error {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--rust);
    margin-top: 10px;
    padding-left: 36px;
    line-height: 1.4;
  }
}
</style>
