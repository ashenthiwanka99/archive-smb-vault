<template>
  <section class="status-card" :class="`status-card--${state}`">
    <div class="status-card__glyph">
      <q-spinner v-if="state === 'uploading'" color="primary" size="26px" />
      <q-icon
        v-else
        :name="glyphIcon"
        size="26px"
        :class="glyphClass"
      />
    </div>

    <div class="status-card__body">
      <div class="status-card__title">{{ title }}</div>
      <div v-if="subtitle" class="status-card__sub">{{ subtitle }}</div>
    </div>

    <div v-if="ctaLabel" class="status-card__action">
      <button
        class="btn-archive"
        :disabled="ctaDisabled"
        @click="$emit('action')"
      >
        <q-icon v-if="ctaIcon" :name="ctaIcon" size="15px" />
        {{ ctaLabel }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  state: {
    type: String,
    required: true,
    validator: v => ['uploading', 'queued', 'up-to-date', 'pending', 'nothing-selected', 'no-shares', 'no-permission', 'error'].includes(v)
  },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  ctaLabel: { type: String, default: '' },
  ctaIcon: { type: String, default: '' },
  ctaDisabled: { type: Boolean, default: false }
})

defineEmits(['action'])

const glyphIcon = computed(() => {
  switch (props.state) {
    case 'up-to-date':        return 'mdi-check-circle-outline'
    case 'pending':           return 'mdi-circle-medium'
    case 'queued':            return 'mdi-clock-outline'
    case 'nothing-selected':  return 'mdi-folder-question-outline'
    case 'no-shares':         return 'mdi-alert-circle-outline'
    case 'no-permission':     return 'mdi-lock-outline'
    case 'error':             return 'mdi-close-circle-outline'
    default:                  return 'mdi-circle-outline'
  }
})

const glyphClass = computed(() => {
  switch (props.state) {
    case 'up-to-date':        return 'moss'
    case 'pending':
    case 'queued':            return 'marigold'
    case 'nothing-selected':  return 'paper-dim'
    case 'no-shares':         return 'paper-dim'
    case 'no-permission':
    case 'error':             return 'rust'
    default:                  return 'paper-mute'
  }
})
</script>

<style lang="scss" scoped>
.status-card {
  display: grid;
  grid-template-columns: 44px 1fr;
  grid-template-areas:
    "glyph body"
    "action action";
  align-items: center;
  column-gap: 16px;
  row-gap: 14px;
  padding: 20px 22px;
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  margin: 0 20px;

  &__glyph {
    grid-area: glyph;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    background: var(--ink-0);
    border: 1px solid var(--rule-soft);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  &__body {
    grid-area: body;
    min-width: 0;
  }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 20px;
    letter-spacing: -0.01em;
    color: var(--paper);
    line-height: 1.2;
    text-wrap: balance;
    overflow-wrap: break-word;
  }

  &__sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    letter-spacing: 0.02em;
    margin-top: 4px;
    line-height: 1.4;
  }

  &__action {
    grid-area: action;

    .btn-archive {
      width: 100%;
    }
  }

  // On wider cards (tablet+), put the CTA inline to the right.
  @media (min-width: 540px) {
    grid-template-columns: 44px 1fr auto;
    grid-template-areas: "glyph body action";
    row-gap: 0;

    &__action .btn-archive { width: auto; }
  }

  // Tiny accent strip on the left edge that picks up the state colour.
  &--up-to-date      { border-left: 2px solid var(--moss); }
  &--pending,
  &--queued          { border-left: 2px solid var(--marigold); }
  &--uploading       { border-left: 2px solid var(--marigold); }
  &--nothing-selected,
  &--no-shares       { border-left: 2px solid var(--paper-dim); }
  &--no-permission,
  &--error           { border-left: 2px solid var(--rust); }
}
</style>
