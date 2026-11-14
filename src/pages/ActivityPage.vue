<template>
  <q-page class="activity-page">
    <div class="activity-page__statusbar-pad safe-top" />
    <q-toolbar class="activity-page__toolbar">
      <button class="activity-page__icon-btn" aria-label="Back" @click="goBack">
        <q-icon name="mdi-arrow-left" size="var(--icon-md)" />
      </button>
      <q-toolbar-title class="activity-page__title">Activity</q-toolbar-title>
      <button
        class="activity-page__icon-btn"
        aria-label="Refresh"
        @click="backupStore.refreshHistory()"
      >
        <q-icon name="mdi-refresh" size="var(--icon-md)" class="paper-mute" />
      </button>
    </q-toolbar>

    <div class="activity-page__body">
      <BackupHistoryCard
        :history="backupStore.history"
        @clear="backupStore.clearHistory()"
      />
    </div>
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBackupStore } from '../stores/backup'
import BackupHistoryCard from '../components/BackupHistoryCard.vue'

const router = useRouter()
const backupStore = useBackupStore()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/gallery')
}

onMounted(() => backupStore.refreshHistory())
</script>

<style lang="scss" scoped>
.activity-page {
  min-height: 100vh;
  background: var(--ink-0);

  &__statusbar-pad { background: var(--ink-0); width: 100%; }

  &__toolbar {
    background: var(--ink-0);
    border-bottom: 1px solid var(--rule);
    min-height: 56px;
    padding: 0 8px;
    gap: 4px;
  }

  &__title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    padding-left: 8px;
  }

  &__icon-btn {
    width: var(--tap-min);
    height: var(--tap-min);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper);
    cursor: pointer;
    border-radius: 50%;
    &:active { background: var(--ink-2); }
  }

  &__body {
    padding: 16px;
  }
}
</style>
