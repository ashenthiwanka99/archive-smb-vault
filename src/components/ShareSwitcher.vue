<template>
  <div v-if="shares.length" class="shelf-tabs" role="tablist">
    <button
      v-for="share in shares"
      :key="share.id"
      role="tab"
      :aria-selected="activeId === share.id"
      class="shelf-tabs__tab"
      :class="{ 'shelf-tabs__tab--active': activeId === share.id }"
      @click="$emit('switch', share.id)"
    >
      {{ share.name }}
    </button>
  </div>
</template>

<script setup>
defineProps({
  shares:   { type: Array,  default: () => [] },
  activeId: { type: String, default: '' }
})
defineEmits(['switch'])
</script>

<style lang="scss" scoped>
.shelf-tabs {
  display: flex;
  gap: 24px;
  padding: 14px 20px 0;
  overflow-x: auto;
  border-bottom: 1px solid var(--rule-soft);

  &::-webkit-scrollbar { display: none; }

  &__tab {
    background: transparent;
    border: none;
    padding: 6px 0 10px;
    cursor: pointer;
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 11px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--paper-dim);
    white-space: nowrap;
    position: relative;
    transition: color var(--dur-fast) var(--ease-archive);

    &::after {
      content: '';
      position: absolute;
      left: 0; right: 0; bottom: -1px;
      height: 1px;
      background: transparent;
      transition: background var(--dur-fast) var(--ease-archive);
    }

    &:hover { color: var(--paper); }

    &--active {
      color: var(--marigold);
      &::after { background: var(--marigold); }
    }
  }
}
</style>
