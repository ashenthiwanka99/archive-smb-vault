<template>
  <component
    :is="tag"
    class="settings-row"
    :class="{ 'settings-row--clickable': interactive }"
    :type="tag === 'button' ? 'button' : undefined"
    @click="interactive && $emit('click', $event)"
  >
    <div v-if="$slots.leading" class="settings-row__leading">
      <slot name="leading" />
    </div>

    <div class="settings-row__body">
      <div class="settings-row__title">
        <slot name="title">{{ title }}</slot>
      </div>
      <div v-if="subtitle || $slots.subtitle" class="settings-row__sub">
        <slot name="subtitle">{{ subtitle }}</slot>
      </div>
    </div>

    <div v-if="$slots.trailing || tag === 'button'" class="settings-row__trailing">
      <slot name="trailing">
        <q-icon v-if="tag === 'button'" name="mdi-chevron-right" size="var(--icon-md)" class="paper-dim" />
      </slot>
    </div>
  </component>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  // 'div' for static rows with controls, 'button' for navigational rows.
  as: { type: String, default: 'div' }
})
defineEmits(['click'])

const tag = computed(() => props.as === 'button' ? 'button' : 'div')
const interactive = computed(() => tag.value === 'button')
</script>

<style lang="scss" scoped>
.settings-row {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 14px 18px;
  background: transparent;
  border: none;
  border-top: 1px solid var(--rule-soft);
  text-align: left;
  color: inherit;
  font: inherit;

  &:first-child { border-top: none; }

  &--clickable {
    cursor: pointer;
    transition: background var(--dur-fast) var(--ease-archive);
    &:hover { background: var(--ink-2); }
    &:active { background: var(--ink-2); }
  }

  &__leading {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    line-height: 1.3;
  }

  &__sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 3px;
    line-height: 1.4;
  }

  &__trailing {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
}
</style>
