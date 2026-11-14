<template>
  <q-page class="starred-page">
    <header class="page-head">
      <div class="page-head__body">
        <div class="caption-sc">Starred</div>
        <h1 class="page-head__title">Kept close</h1>
        <div class="page-head__sub">
          {{ starredStore.items.length }} pinned {{ starredStore.items.length === 1 ? 'item' : 'items' }}
        </div>
      </div>
    </header>

    <div v-if="starredStore.items.length === 0" class="empty-state">
      <div class="empty-state__eyebrow">Empty</div>
      <div class="empty-state__title">Nothing pinned yet.</div>
      <div class="empty-state__subtitle">
        Long-press any file or folder in Files and pick Star to pin it here for quick access.
      </div>
      <button class="btn-ghost q-mt-md" @click="$router.push('/files')">
        <q-icon name="mdi-folder-outline" size="var(--icon-sm)" />
        Open Files
      </button>
    </div>

    <q-list v-else class="starred-page__list">
      <q-item
        v-for="item in starredStore.items"
        :key="item.path + item.shareId"
        clickable
        class="starred-row"
        @click="openItem(item)"
      >
        <q-item-section avatar class="starred-row__avatar">
          <FileTypeIcon :type="item.type" size="22px" />
        </q-item-section>

        <q-item-section>
          <q-item-label class="starred-row__name">{{ item.name }}</q-item-label>
          <q-item-label caption class="starred-row__path">{{ item.path }}</q-item-label>
        </q-item-section>

        <q-item-section side>
          <q-btn
            flat dense round
            icon="mdi-star"
            size="md"
            class="marigold"
            :aria-label="`Unstar ${item.name}`"
            @click.stop="unstar(item)"
          />
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useStarredStore } from '../stores/starred'
import { useSmbStore } from '../stores/smb'
import FileTypeIcon from '../components/FileTypeIcon.vue'

const $q = useQuasar()
const router = useRouter()
const starredStore = useStarredStore()
const smbStore = useSmbStore()

function openItem(item) {
  if (item.isDirectory) {
    if (smbStore.activeShareId !== item.shareId) smbStore.switchShare(item.shareId)
    smbStore.navigateTo(item.path)
    router.push('/files')
  } else {
    router.push({
      name: 'preview',
      query: {
        name: item.name, type: item.type, size: item.size,
        path: item.path, shareId: item.shareId
      }
    })
  }
}

function unstar(item) {
  starredStore.removeStar(item.path, item.shareId)
  $q.notify({ message: 'Unpinned', color: 'positive', textColor: 'dark', timeout: 1500 })
}
</script>

<style lang="scss" scoped>
.starred-page {
  padding-bottom: 40px;

  &__list { padding-top: 8px; }
}

.starred-row {
  padding: 14px 20px;
  border-bottom: 1px solid var(--rule-soft);
  transition: background var(--dur-fast) var(--ease-archive);

  &:hover { background: var(--ink-1); }

  &__avatar { min-width: 32px; padding-right: 16px; }

  &__name {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 15px;
    color: var(--paper);
  }

  &__path {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute) !important;
    margin-top: 2px;
  }
}
</style>
