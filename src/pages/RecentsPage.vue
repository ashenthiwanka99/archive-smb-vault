<template>
  <q-page class="recents-page">
    <header class="page-head">
      <div class="page-head__body">
        <div class="caption-sc">Recent</div>
        <h1 class="page-head__title">Last touched</h1>
        <div class="page-head__sub">
          {{ recentStore.items.length }} {{ recentStore.items.length === 1 ? 'item' : 'items' }}
        </div>
      </div>
      <div v-if="recentStore.items.length > 0" class="page-head__actions">
        <button class="btn-danger-ghost recents-page__clear" @click="confirmClear">
          Clear
        </button>
      </div>
    </header>

    <!-- Empty -->
    <div v-if="recentStore.items.length === 0" class="empty-state">
      <div class="empty-state__eyebrow">History</div>
      <div class="empty-state__title">Nothing yet.</div>
      <div class="empty-state__subtitle">Files you view will appear here, grouped by day.</div>
    </div>

    <q-pull-to-refresh v-else @refresh="onRefresh" color="primary" bg-color="transparent" icon-color="primary">
      <section v-if="recentStore.grouped.today.length" class="recents-page__group">
        <div class="caption-sc recents-page__group-label">Today</div>
        <FileRow
          v-for="file in recentStore.grouped.today"
          :key="file.path + file.accessedAt"
          :file="file"
          @tap="openFile(file)"
        />
      </section>

      <section v-if="recentStore.grouped.yesterday.length" class="recents-page__group">
        <div class="caption-sc recents-page__group-label">Yesterday</div>
        <FileRow
          v-for="file in recentStore.grouped.yesterday"
          :key="file.path + file.accessedAt"
          :file="file"
          @tap="openFile(file)"
        />
      </section>

      <section v-if="recentStore.grouped.thisWeek.length" class="recents-page__group">
        <div class="caption-sc recents-page__group-label">This week</div>
        <FileRow
          v-for="file in recentStore.grouped.thisWeek"
          :key="file.path + file.accessedAt"
          :file="file"
          @tap="openFile(file)"
        />
      </section>

      <section v-if="recentStore.grouped.older.length" class="recents-page__group">
        <div class="caption-sc recents-page__group-label">Earlier</div>
        <FileRow
          v-for="file in recentStore.grouped.older"
          :key="file.path + file.accessedAt"
          :file="file"
          @tap="openFile(file)"
        />
      </section>
    </q-pull-to-refresh>
  </q-page>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useRecentStore } from '../stores/recents'
import FileRow from '../components/FileRow.vue'

const $q = useQuasar()
const router = useRouter()
const recentStore = useRecentStore()

function openFile(file) {
  router.push({
    name: 'preview',
    query: {
      name: file.name,
      type: file.type,
      size: file.size,
      path: file.path,
      shareId: file.shareId
    }
  })
}

async function onRefresh(done) {
  // Recents list is purely derived from the Pinia-persisted store; pull-to-
  // refresh here is mostly a nicety that re-reads localStorage if the user
  // has another session that modified it.  No network call — resolve fast.
  try { await recentStore.refresh?.() } catch {}
  done()
}

function confirmClear() {
  $q.dialog({
    title: 'Clear history',
    message: 'Remove all recent file entries? This cannot be undone.',
    cancel: true,
    ok: { label: 'Clear', color: 'negative' }
  }).onOk(() => {
    recentStore.clearHistory()
    $q.notify({ message: 'History cleared', color: 'positive', timeout: 1500 })
  })
}
</script>

<style lang="scss" scoped>
.recents-page {
  padding-bottom: 96px;

  &__clear {
    min-height: 36px;
    padding: 0 14px;
    font-size: 12px;
  }

  &__group { padding-top: 16px; }

  &__group-label {
    padding: 14px 20px 6px;
    color: var(--paper-dim);
  }
}
</style>
