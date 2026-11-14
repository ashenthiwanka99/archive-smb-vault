<template>
  <button
    type="button"
    class="net"
    :class="`net--${status}`"
    :disabled="status === 'connecting'"
    :aria-label="label"
    @click="$emit('toggle')"
  >
    <span class="net__label">{{ label }}</span>
  </button>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, default: 'offline' }
})

defineEmits(['toggle'])

const label = computed(() => {
  switch (props.status) {
    case 'online':     return 'Connected'
    case 'connecting': return 'Connecting'
    default:           return 'Disconnected'
  }
})
</script>

<style lang="scss" scoped>
.net {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-body);
  font-weight: 500;
  font-size: 10px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--paper-dim);
  padding: 4px 2px 4px;
  border: none;
  border-bottom: 1px solid var(--rule);
  background: transparent;
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease-archive);

  &:active { opacity: 0.6; }
  &:disabled { cursor: progress; }

  &--online {
    color: var(--moss);
    border-bottom-color: var(--moss);
  }

  &--connecting {
    color: var(--marigold);
    border-bottom-color: var(--marigold);
  }

  &--offline {
    color: var(--rust);
    border-bottom-color: var(--rust);
  }
}
</style>
