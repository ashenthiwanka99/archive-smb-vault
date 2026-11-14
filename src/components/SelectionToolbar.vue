<template>
  <div class="sel-toolbar">
    <button
      class="sel-toolbar__close"
      aria-label="Exit selection mode"
      @click="smbStore.clearSelection()"
    >
      <q-icon name="mdi-close" size="var(--icon-md)" />
    </button>

    <div class="sel-toolbar__count">
      <span class="tabular">{{ smbStore.selectedNames.size }}</span>
      <span class="sel-toolbar__count-label">selected</span>
    </div>

    <div class="sel-toolbar__spacer" />

    <button
      class="sel-toolbar__action"
      :aria-label="`Download ${smbStore.selectedNames.size} items`"
      :disabled="!anySelected"
      @click="$emit('download')"
    >
      <q-icon name="mdi-download-outline" size="var(--icon-md)" />
    </button>

    <button
      class="sel-toolbar__action"
      :aria-label="`Star ${smbStore.selectedNames.size} items`"
      :disabled="!anySelected"
      @click="$emit('star')"
    >
      <q-icon name="mdi-star-outline" size="var(--icon-md)" />
    </button>

    <button
      class="sel-toolbar__action"
      :aria-label="'More actions'"
    >
      <q-icon name="mdi-dots-vertical" size="var(--icon-md)" />
      <q-menu>
        <q-list dense style="min-width: 180px;">
          <q-item clickable v-close-popup @click="$emit('select-all')">
            <q-item-section avatar>
              <q-icon name="mdi-select-all" size="var(--icon-sm)" />
            </q-item-section>
            <q-item-section>Select all</q-item-section>
          </q-item>
          <q-item clickable v-close-popup @click="$emit('copy')">
            <q-item-section avatar>
              <q-icon name="mdi-content-copy" size="var(--icon-sm)" />
            </q-item-section>
            <q-item-section>Copy to…</q-item-section>
          </q-item>
          <q-item clickable v-close-popup @click="$emit('move')">
            <q-item-section avatar>
              <q-icon name="mdi-file-move" size="var(--icon-sm)" />
            </q-item-section>
            <q-item-section>Move to…</q-item-section>
          </q-item>
          <q-separator />
          <q-item clickable v-close-popup @click="$emit('delete')">
            <q-item-section avatar>
              <q-icon name="mdi-delete-outline" size="var(--icon-sm)" class="rust" />
            </q-item-section>
            <q-item-section class="rust">Delete</q-item-section>
          </q-item>
        </q-list>
      </q-menu>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useSmbStore } from '../stores/smb'

defineEmits(['download', 'star', 'delete', 'copy', 'move', 'select-all'])

const smbStore = useSmbStore()
const anySelected = computed(() => smbStore.selectedNames.size > 0)
</script>

<style lang="scss" scoped>
.sel-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 10px 14px;
  background: var(--marigold-soft);
  border-bottom: 1px solid var(--rule);
  min-height: 56px;

  &__close {
    width: 40px;
    height: 40px;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;

    &:active { background: var(--ink-2); }
  }

  &__count {
    display: inline-flex;
    align-items: baseline;
    gap: 6px;
    margin-left: 4px;
    color: var(--paper);
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 15px;
  }

  &__count-label {
    font-size: 12px;
    color: var(--paper-mute);
    letter-spacing: 0.04em;
  }

  &__spacer {
    flex: 1;
  }

  &__action {
    position: relative;
    width: 40px;
    height: 40px;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    display: inline-flex;
    align-items: center;
    justify-content: center;

    &:active:not(:disabled) { background: var(--ink-2); }
    &:disabled { opacity: 0.4; cursor: not-allowed; }
  }
}
</style>
