<template>
  <q-layout view="hHh lpR fFf">
    <q-header class="plain-header">
      <q-toolbar class="plain-header__bar">
        <q-btn flat dense round icon="mdi-arrow-left" color="primary" size="sm" @click="goBack" />
        <q-toolbar-title class="plain-header__title">
          {{ $route.meta?.title || '' }}
        </q-toolbar-title>
      </q-toolbar>
    </q-header>

    <q-page-container>
      <router-view />
    </q-page-container>
  </q-layout>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/files')
}
</script>

<style lang="scss" scoped>
.plain-header {
  background: var(--ink-0) !important;
  border-bottom: 1px solid var(--rule);
  // Embed the safe-area inset directly so nothing downstream can clobber it.
  padding-top: env(safe-area-inset-top, 0px);

  &__bar { padding-left: 8px; }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 18px;
    color: var(--paper);
    letter-spacing: -0.01em;
    text-transform: none;
  }
}
</style>
