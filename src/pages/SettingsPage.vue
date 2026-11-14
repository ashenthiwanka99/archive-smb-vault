<template>
  <q-page class="settings-page">
    <header class="page-head">
      <div class="page-head__body">
        <div class="caption-sc">Settings</div>
        <h1 class="page-head__title">Configuration</h1>
        <div class="page-head__sub">Shelves, credentials, and app preferences.</div>
      </div>
    </header>

    <!-- Shelves -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Shelves</div>
      <div
        v-for="share in settingsStore.shares"
        :key="share.id"
        class="settings-card"
      >
        <div class="settings-card__head">
          <div class="settings-card__title">{{ share.name }}</div>
          <div class="settings-card__actions">
            <q-btn
              flat dense round
              icon="mdi-pencil-outline"
              size="md"
              class="paper-mute"
              :aria-label="`Edit ${share.name}`"
              @click="editShare(share)"
            />
            <q-btn
              flat dense round
              icon="mdi-trash-can-outline"
              size="md"
              class="rust"
              :aria-label="`Delete ${share.name}`"
              @click="removeShare(share)"
            />
          </div>
        </div>
        <div class="settings-card__rows">
          <div class="settings-card__row"><span class="settings-card__key">Host</span><span class="settings-card__val tabular">{{ share.host }}</span></div>
          <div class="settings-card__row"><span class="settings-card__key">Share</span><span class="settings-card__val">{{ share.shareName }}</span></div>
          <div class="settings-card__row"><span class="settings-card__key">Access</span><span class="settings-card__val">{{ share.isGuest ? 'guest' : 'authenticated' }}</span></div>
        </div>
      </div>

      <button class="btn-ghost settings-page__add-btn" @click="showShareDialog(null)">
        <q-icon name="mdi-plus" size="var(--icon-sm)" /> Add shelf
      </button>
    </section>

    <!-- Display -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Display</div>
      <div class="settings-card">
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Default layout</div>
            <div class="settings-card__line-sub">List when browsing, grid for gallery.</div>
          </div>
          <q-btn-toggle
            v-model="settingsStore.defaultView"
            dense flat
            toggle-color="primary"
            :options="[{ label: 'List', value: 'list' }, { label: 'Grid', value: 'grid' }]"
            no-caps
            class="settings-page__toggle"
          />
        </div>
      </div>
    </section>

    <!-- Connection -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Connection</div>
      <div class="settings-card">
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Request timeout</div>
          </div>
          <q-btn-toggle
            v-model="settingsStore.connectionTimeout"
            dense flat
            toggle-color="primary"
            :options="[{label:'5s',value:5000},{label:'10s',value:10000},{label:'30s',value:30000}]"
            no-caps
            class="settings-page__toggle"
          />
        </div>
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Auto-reconnect</div>
            <div class="settings-card__line-sub">Reach out again when the app wakes up.</div>
          </div>
          <q-toggle v-model="settingsStore.autoReconnect" color="primary" />
        </div>
      </div>
    </section>

    <!-- Security -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Privacy</div>
      <div class="settings-card">
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Biometric lock</div>
            <div class="settings-card__line-sub">Require fingerprint or face to open the archive.</div>
          </div>
          <q-toggle v-model="settingsStore.biometricEnabled" color="primary" />
        </div>
      </div>
    </section>

    <!-- Diagnostics -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Diagnostics</div>
      <div class="settings-card">
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Debug console</div>
            <div class="settings-card__line-sub">
              Show an on-screen log of console output, errors, and SMB failures.
              Useful when the app misbehaves and you want to share what went wrong.
            </div>
          </div>
          <q-toggle v-model="settingsStore.debugConsoleEnabled" color="primary" />
        </div>
      </div>
    </section>

    <!-- Advanced -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">Advanced</div>
      <button class="settings-card settings-card--link" @click="$router.push('/info')">
        <div class="settings-card__line">
          <div class="settings-card__line-body">
            <div class="settings-card__line-title">Network diagnostics</div>
            <div class="settings-card__line-sub">Ping, latency, share storage info</div>
          </div>
          <q-icon name="mdi-chevron-right" size="var(--icon-md)" class="paper-dim" />
        </div>
      </button>
    </section>

    <!-- About -->
    <section class="settings-page__section">
      <div class="caption-sc settings-page__label">About</div>
      <div class="settings-card">
        <div class="settings-card__rows">
          <div class="settings-card__row"><span class="settings-card__key">App</span><span class="settings-card__val">Archive</span></div>
          <div class="settings-card__row"><span class="settings-card__key">Version</span><span class="settings-card__val tabular">2.0.0</span></div>
          <div class="settings-card__row"><span class="settings-card__key">Package</span><span class="settings-card__val tabular">com.mrdon.server</span></div>
        </div>
      </div>
    </section>

    <!-- Restore defaults (non-destructive) -->
    <section class="settings-page__section">
      <button class="btn-ghost settings-page__restore-btn" @click="confirmResetDefaults">
        <q-icon name="mdi-restore" size="var(--icon-sm)" /> Restore default shelves
      </button>
    </section>

    <!-- Share edit dialog -->
    <q-dialog v-model="shareDialogOpen" persistent>
      <q-card class="share-dialog">
        <div class="share-dialog__eyebrow caption-sc">
          {{ editingShare ? 'edit shelf' : 'add shelf' }}
        </div>
        <div class="share-dialog__title display-l">
          {{ editingShare ? 'Update details.' : 'A new shelf.' }}
        </div>

        <div class="share-dialog__fields">
          <label class="share-dialog__field">
            <span class="share-dialog__label">Name</span>
            <q-input
              v-model="shareForm.name"
              dense borderless
              placeholder="e.g. Media"
              autocapitalize="words"
              spellcheck="false"
              class="share-dialog__input"
              @blur="touched.name = true"
            />
            <span v-if="touched.name && !shareForm.name.trim()" class="share-dialog__error">
              Name is required.
            </span>
          </label>
          <label class="share-dialog__field">
            <span class="share-dialog__label">Host</span>
            <q-input
              v-model="shareForm.host"
              dense borderless
              placeholder="192.168.x.x or hostname"
              inputmode="url"
              autocapitalize="none"
              autocorrect="off"
              spellcheck="false"
              class="share-dialog__input"
              @blur="touched.host = true"
            />
            <span v-if="touched.host && shareForm.host && !isValidHost(shareForm.host)" class="share-dialog__error">
              Use an IP address or hostname.
            </span>
            <span v-else-if="touched.host && !shareForm.host.trim()" class="share-dialog__error">
              Host is required.
            </span>
          </label>
          <label class="share-dialog__field">
            <span class="share-dialog__label">Share name</span>
            <q-input
              v-model="shareForm.shareName"
              dense borderless
              placeholder="e.g. Media"
              autocapitalize="none"
              autocorrect="off"
              spellcheck="false"
              class="share-dialog__input"
              @blur="touched.shareName = true"
            />
            <span v-if="touched.shareName && !shareForm.shareName.trim()" class="share-dialog__error">
              Share name is required.
            </span>
          </label>

          <q-separator class="share-dialog__sep" />

          <label class="share-dialog__toggle">
            <q-toggle v-model="shareForm.isGuest" color="primary" />
            <span>Guest access (no password)</span>
          </label>

          <template v-if="!shareForm.isGuest">
            <label class="share-dialog__field">
              <span class="share-dialog__label">Username</span>
              <q-input
                v-model="shareForm.username"
                dense borderless
                autocomplete="username"
                autocapitalize="none"
                autocorrect="off"
                spellcheck="false"
                class="share-dialog__input"
              />
            </label>
            <label class="share-dialog__field">
              <span class="share-dialog__label">Password</span>
              <q-input
                v-model="shareForm.password"
                :type="showSharePassword ? 'text' : 'password'"
                autocomplete="current-password"
                dense borderless
                class="share-dialog__input"
              >
                <template #append>
                  <q-icon
                    :name="showSharePassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
                    size="var(--icon-sm)"
                    class="cursor-pointer paper-dim"
                    :aria-label="showSharePassword ? 'Hide password' : 'Show password'"
                    @click="showSharePassword = !showSharePassword"
                  />
                </template>
              </q-input>
            </label>
          </template>
        </div>

        <div class="share-dialog__footer">
          <button class="btn-ghost" @click="shareDialogOpen = false">Cancel</button>
          <button class="btn-archive" :disabled="!isShareFormValid" @click="saveShare">Save</button>
        </div>
      </q-card>
    </q-dialog>

  </q-page>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useQuasar } from 'quasar'
import { useSettingsStore } from '../stores/settings'
import { isValidHost } from '../utils/helpers'

const $q = useQuasar()
const settingsStore = useSettingsStore()

const shareDialogOpen = ref(false)
const editingShare = ref(null)
const showSharePassword = ref(false)
const shareForm = reactive({
  name: '',
  host: '',
  shareName: '',
  username: '',
  password: '',
  isGuest: true
})
const touched = reactive({ name: false, host: false, shareName: false })

const isShareFormValid = computed(() =>
  shareForm.name.trim() &&
  shareForm.host.trim() &&
  isValidHost(shareForm.host) &&
  shareForm.shareName.trim()
)

function showShareDialog(share) {
  editingShare.value = share
  showSharePassword.value = false
  touched.name = false
  touched.host = false
  touched.shareName = false
  // Populate form synchronously with whatever we already know, then pop the dialog
  // immediately. Credentials load in the background so a slow/failing secure-storage
  // call never blocks the UI from appearing.
  if (share) {
    shareForm.name = share.name
    shareForm.host = share.host
    shareForm.shareName = share.shareName
    shareForm.isGuest = share.isGuest !== false
    shareForm.username = ''
    shareForm.password = ''
    shareDialogOpen.value = true
    settingsStore.getShareCredentials(share.id).then(creds => {
      // Only apply if the dialog is still showing this share
      if (editingShare.value?.id === share.id) {
        shareForm.username = creds.username || ''
        shareForm.password = creds.password || ''
      }
    })
  } else {
    shareForm.name = ''
    shareForm.host = settingsStore.shares[0]?.host || ''
    shareForm.shareName = ''
    shareForm.username = ''
    shareForm.password = ''
    shareForm.isGuest = true
    shareDialogOpen.value = true
  }
}

function editShare(share) { showShareDialog(share) }

function saveShare() {
  if (!isShareFormValid.value) return
  if (!isValidHost(shareForm.host)) {
    $q.notify({ message: 'Invalid host address', color: 'negative', timeout: 3000 })
    return
  }

  const meta = {
    name: shareForm.name.trim(),
    host: shareForm.host.trim(),
    shareName: shareForm.shareName.trim(),
    isGuest: shareForm.isGuest
  }

  let shareId
  const isEdit = !!editingShare.value
  if (isEdit) {
    shareId = editingShare.value.id
    settingsStore.updateShare(shareId, meta)
  } else {
    shareId = settingsStore.addShare(meta)
  }

  // Close dialog immediately — credential persistence runs fire-and-forget.
  // settingsStore wraps the calls so they never throw.
  shareDialogOpen.value = false
  $q.notify({
    message: isEdit ? 'Shelf updated' : 'Shelf added',
    color: 'positive', timeout: 2000
  })

  if (shareForm.isGuest) {
    settingsStore.clearShareCredentials(shareId)
  } else {
    settingsStore.setShareCredentials(shareId, {
      username: shareForm.username,
      password: shareForm.password
    })
  }
}

function removeShare(share) {
  $q.dialog({
    title: 'Remove shelf',
    message: `Remove "${share.name}"? This cannot be undone.`,
    cancel: true,
    ok: { label: 'Remove', color: 'negative' }
  }).onOk(async () => {
    await settingsStore.removeShare(share.id)
    $q.notify({ message: `"${share.name}" removed`, color: 'positive', timeout: 2000 })
  })
}

function confirmResetDefaults() {
  $q.dialog({
    title: 'Restore default shelves',
    message: 'Re-adds any missing default shelf (Media, PrivateBackup). Shelves you added yourself are kept.',
    cancel: true,
    ok: { label: 'Restore', color: 'primary' }
  }).onOk(async () => {
    const added = await settingsStore.resetToDefaultShares()
    $q.notify({
      message: added ? 'Default shelves restored' : 'Defaults already present',
      color: 'positive', timeout: 2500
    })
  })
}

</script>

<style lang="scss" scoped>
.settings-page {
  padding: 0 20px 96px;

  &__hero {
    padding: 32px 0 24px;
    margin-bottom: 8px;
    border-bottom: 1px solid var(--rule-soft);
  }

  &__title { margin: 8px 0 6px; }

  &__section {
    margin-top: 28px;
  }

  &__label {
    color: var(--paper-dim);
    margin-bottom: 10px;
  }

  &__add-btn {
    width: 100%;
    margin-top: 4px;
  }

  &__restore-btn {
    width: 100%;
  }

  &__danger-label {
    color: var(--rust);
    margin-bottom: 10px;
  }

  &__danger-card {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 18px;
    background: var(--ink-1);
    border: 1px solid rgba(200, 90, 60, 0.35);
    border-radius: var(--r-md);
  }

  &__danger-body { flex: 1; min-width: 0; }

  &__danger-title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
  }

  &__danger-sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 4px;
    line-height: 1.4;
  }

  &__toggle {
    :deep(.q-btn) {
      font-family: var(--font-body);
      font-size: 12px;
      letter-spacing: 0.04em;
      min-height: 30px;
      padding: 0 12px;
      color: var(--paper-mute);
    }
  }

  &__select {
    :deep(.q-field__native) {
      color: var(--paper) !important;
      font-family: var(--font-body);
      font-size: 13px;
    }
  }

  &__mini-btn {
    min-height: 32px;
    padding: 0 12px;
    font-size: 11px;
    letter-spacing: 0.08em;
  }
}

.settings-card {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;
  margin-bottom: 12px;

  &--link {
    width: 100%;
    text-align: left;
    cursor: pointer;
    padding: 0;
    color: inherit;
    transition: background var(--dur-fast) var(--ease-archive);
    &:hover { background: var(--ink-2); }
    &:active { background: var(--ink-2); }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 18px 6px;
  }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 18px;
    color: var(--paper);
    letter-spacing: -0.01em;
  }

  &__actions { display: flex; gap: 12px; }

  &__rows { padding: 0 18px 16px; }

  &__row {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    padding: 6px 0;
    font-family: var(--font-body);
    font-size: 13px;

    &:first-child { padding-top: 8px; }
  }

  &__key {
    color: var(--paper-dim);
    font-size: 11px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    padding-top: 1px;
  }

  &__val {
    color: var(--paper);
    text-align: right;
  }

  &__line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    padding: 16px 18px;
    border-top: 1px solid var(--rule-soft);

    &:first-child { border-top: none; }
  }

  &__line-body { flex: 1; min-width: 0; }
  &__line-title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
  }
  &__line-sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 2px;
    line-height: 1.4;
  }

  &__footer {
    padding: 12px 18px 18px;
    border-top: 1px solid var(--rule-soft);
  }

  &__error {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--rust);
    margin-top: 8px;
  }

  &__ok {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--moss);
    margin-top: 8px;
  }
}

.share-dialog {
  background: var(--ink-1) !important;
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  color: var(--paper);
  min-width: 320px;
  max-width: 420px;
  padding: 28px;

  &__eyebrow { color: var(--paper-dim); margin-bottom: 8px; }
  &__title { color: var(--paper); margin-bottom: 24px; }

  &__fields { display: flex; flex-direction: column; gap: 14px; }

  &__field { display: block; }

  &__label {
    display: block;
    font-family: var(--font-body);
    font-size: 11px;
    color: var(--paper-dim);
    letter-spacing: 0.12em;
    text-transform: uppercase;
    margin-bottom: 4px;
  }

  &__input {
    :deep(.q-field__control) {
      background: transparent;
      border: none !important;
      border-bottom: 1px solid var(--rule) !important;
      border-radius: 0;
      min-height: 38px;
      padding: 0 2px;
    }
    :deep(.q-field__control:before),
    :deep(.q-field__control:after) { border: none !important; box-shadow: none !important; }
    :deep(.q-field--focused .q-field__control) { border-bottom-color: var(--marigold) !important; }
    :deep(.q-field__native) {
      color: var(--paper) !important;
      font-family: var(--font-body);
      font-size: 14px;
    }
  }

  &__sep { background: var(--rule-soft); margin: 4px 0; }

  &__error {
    display: block;
    margin-top: 6px;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--rust);
    line-height: 1.3;
  }

  &__toggle {
    display: flex;
    align-items: center;
    gap: 12px;
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-mute);
    cursor: pointer;
  }

  &__footer {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 24px;
  }
}

</style>
