<template>
  <q-dialog
    :model-value="modelValue"
    position="bottom"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <q-card class="backup-sheet">
      <!-- Own header with chevron-down to close -->
      <div class="backup-sheet__head">
        <button
          class="backup-sheet__close"
          aria-label="Close"
          @click="$emit('update:modelValue', false)"
        >
          <q-icon name="mdi-chevron-down" size="22px" />
        </button>
        <div class="backup-sheet__title">Advanced options</div>
        <span class="backup-sheet__close" aria-hidden="true" />
      </div>

      <!-- Body (scrollable) -->
      <div class="backup-sheet__body">
        <div class="settings-card">
          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Include photos</div>
              <div class="settings-card__line-sub tabular">
                {{ photoLabel }}
              </div>
            </div>
            <q-toggle v-model="backupStore.includePhotos" color="primary" />
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Include videos</div>
              <div class="settings-card__line-sub tabular">
                {{ videoLabel }}
              </div>
            </div>
            <q-toggle v-model="backupStore.includeVideos" color="primary" />
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Wi-Fi only</div>
              <div class="settings-card__line-sub">Pause when on mobile data.</div>
            </div>
            <q-toggle v-model="backupStore.wifiOnly" color="primary" />
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Home network only</div>
              <div class="settings-card__line-sub">
                Only back up when the NAS is reachable — skips on café / guest / mobile networks.
              </div>
            </div>
            <q-toggle v-model="backupStore.homeNetworkOnly" color="primary" />
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Charger only</div>
              <div class="settings-card__line-sub">Only back up while the phone is plugged in.</div>
            </div>
            <q-toggle v-model="backupStore.chargerOnly" color="primary" />
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Only within time window</div>
              <div class="settings-card__line-sub">
                Skip scheduled runs outside the chosen hours. Tapping "Back up now" always runs.
              </div>
            </div>
            <q-toggle v-model="backupStore.windowEnabled" color="primary" />
          </div>

          <div v-if="backupStore.windowEnabled" class="settings-card__line settings-card__line--stack">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Window hours</div>
              <div class="settings-card__line-sub">
                Active {{ formatHour(backupStore.windowStartHour) }} – {{ formatHour(backupStore.windowEndHour) }}.
                End before start wraps past midnight.
              </div>
            </div>
            <div class="window-row">
              <q-select
                v-model="backupStore.windowStartHour"
                :options="hourOptions"
                emit-value map-options dense borderless
                class="window-row__select"
              />
              <span class="window-row__sep">to</span>
              <q-select
                v-model="backupStore.windowEndHour"
                :options="hourOptions"
                emit-value map-options dense borderless
                class="window-row__select"
              />
            </div>
          </div>

          <div class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Delete from phone after backup</div>
              <div class="settings-card__line-sub">
                After a successful upload + integrity check, queue the file for deletion.
                You confirm each batch via Android's system dialog — nothing disappears silently.
              </div>
            </div>
            <q-toggle v-model="backupStore.autoCleanup" color="primary" />
          </div>

          <div v-if="backupStore.autoCleanup" class="settings-card__line settings-card__line--stack">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Grace period</div>
              <div class="settings-card__line-sub">
                Files stay on the phone for this long after backup before becoming eligible for cleanup.
              </div>
            </div>
            <q-btn-toggle
              v-model="backupStore.cleanupGraceDays"
              dense flat
              toggle-color="primary"
              no-caps
              :options="graceOptions"
              class="backup-sheet__toggle"
            />
          </div>

          <div class="settings-card__line settings-card__line--stack">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Folder layout</div>
              <div class="settings-card__line-sub">
                {{ organizationHint }}
              </div>
            </div>
            <q-btn-toggle
              v-model="backupStore.backupOrganization"
              dense flat
              toggle-color="primary"
              no-caps
              :options="organizationOptions"
              class="backup-sheet__toggle"
            />
          </div>

          <div class="settings-card__line settings-card__line--stack">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Check for new files</div>
              <div class="settings-card__line-sub">
                How often the background scan runs. 15 min minimum (Android limit).
              </div>
            </div>
            <q-btn-toggle
              v-model="backupStore.scheduleInterval"
              dense flat
              toggle-color="primary"
              no-caps
              :options="scheduleOptions"
              class="backup-sheet__toggle"
            />
          </div>

          <div v-if="settingsStore.shares.length > 1" class="settings-card__line">
            <div class="settings-card__line-body">
              <div class="settings-card__line-title">Target shelf</div>
              <div class="settings-card__line-sub">Where backups land.</div>
            </div>
            <q-select
              v-model="backupStore.backupShareId"
              :options="settingsStore.shares.map(s => ({ label: s.name, value: s.id }))"
              emit-value map-options dense borderless
              style="min-width: 140px"
              class="backup-sheet__select"
            />
          </div>
        </div>

        <!-- Exclude patterns -->
        <div class="backup-sheet__excludes">
          <div class="caption-sc backup-sheet__section-label">Exclude patterns</div>
          <div class="exclude-card">
            <div class="exclude-card__hint">
              Glob-style patterns; matches against filename or path.
              Examples: <code>*.tmp</code>, <code>screenshot_*.png</code>, <code>.thumbnails/*</code>
            </div>
            <div class="exclude-card__chips">
              <span
                v-for="pat in backupStore.excludePatterns"
                :key="pat"
                class="exclude-card__chip"
              >
                <span class="exclude-card__chip-text tabular">{{ pat }}</span>
                <button
                  class="exclude-card__chip-x"
                  :aria-label="`Remove pattern ${pat}`"
                  @click="removePattern(pat)"
                >
                  <q-icon name="mdi-close" size="14px" />
                </button>
              </span>
              <button class="exclude-card__add" @click="addPattern">
                <q-icon name="mdi-plus" size="var(--icon-sm)" />
                Add
              </button>
            </div>
          </div>
        </div>

        <!-- Verify against shelf -->
        <div class="backup-sheet__verify">
          <div class="caption-sc backup-sheet__section-label">Sync with shelf</div>
          <div class="verify-card">
            <div class="verify-card__head">
              <div class="verify-card__body">
                <div class="verify-card__title">Verify against shelf</div>
                <div class="verify-card__sub">
                  Checks the NAS for each backed-up file. Drops entries whose
                  files were deleted on the server so they re-upload next time.
                </div>
              </div>
              <button
                v-if="!isVerifying"
                class="btn-ghost verify-card__btn"
                :disabled="verifyLaunching"
                @click="launchVerify"
              >
                <q-icon name="mdi-sync" size="var(--icon-sm)" />
                Verify
              </button>
              <button
                v-else
                class="btn-danger-ghost verify-card__btn"
                @click="backupStore.stopVerify()"
              >
                Stop
              </button>
            </div>

            <!-- Progress / result -->
            <div v-if="isVerifying || verifyStatusText" class="verify-card__status">
              <q-spinner
                v-if="isVerifying"
                color="primary"
                size="14px"
                class="q-mr-sm"
              />
              <q-icon
                v-else-if="backupStore.verifyError"
                name="mdi-alert-circle-outline"
                size="var(--icon-sm)"
                class="rust q-mr-sm"
              />
              <q-icon
                v-else-if="backupStore.verifyLastRunAt"
                name="mdi-check-circle-outline"
                size="var(--icon-sm)"
                class="moss q-mr-sm"
              />
              <span class="verify-card__status-text">{{ verifyStatusText }}</span>
            </div>

            <!-- Live progress bar -->
            <div v-if="isVerifying && backupStore.verifyTotal > 0" class="verify-card__bar">
              <div
                class="verify-card__bar-fill"
                :style="{ width: verifyPct + '%' }"
              />
            </div>
          </div>
        </div>

        <!-- Danger zone -->
        <div class="backup-sheet__danger">
          <div class="caption-sc backup-sheet__danger-label">Danger zone</div>
          <div class="backup-sheet__danger-card">
            <div class="backup-sheet__danger-body">
              <div class="backup-sheet__danger-title">Reset backup history</div>
              <div class="backup-sheet__danger-sub">
                Clears what's been backed up. Files on the shelf are untouched.
              </div>
            </div>
            <button class="btn-danger-ghost" @click="resetHistory">Reset</button>
          </div>
        </div>
      </div>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useQuasar } from 'quasar'
import { useBackupStore } from '../stores/backup'
import { useSettingsStore } from '../stores/settings'
import { useGalleryStore } from '../stores/gallery'
import { formatSize } from '../utils/helpers'

defineProps({ modelValue: { type: Boolean, default: false } })
defineEmits(['update:modelValue'])

const $q = useQuasar()
const backupStore = useBackupStore()
const settingsStore = useSettingsStore()
const galleryStore = useGalleryStore()

const MIN = 60 * 1000
const HOUR = 60 * MIN

const scheduleOptions = [
  { label: '15 min', value: 15 * MIN },
  { label: '30 min', value: 30 * MIN },
  { label: '1 hr',   value: 1 * HOUR },
  { label: '6 hr',   value: 6 * HOUR },
  { label: '12 hr',  value: 12 * HOUR },
  { label: 'Daily',  value: 24 * HOUR }
]

const hourOptions = Array.from({ length: 24 }, (_, h) => ({
  label: formatHour(h),
  value: h
}))

function formatHour(h) {
  const hr = ((h % 12) || 12)
  return `${hr}:00 ${h < 12 ? 'AM' : 'PM'}`
}

const organizationOptions = [
  { label: 'Source', value: 'source' },
  { label: 'Year/Month', value: 'year-month' },
  { label: 'Flat', value: 'flat' }
]

const graceOptions = [
  { label: '0 d',  value: 0 },
  { label: '1 d',  value: 1 },
  { label: '3 d',  value: 3 },
  { label: '7 d',  value: 7 },
  { label: '14 d', value: 14 },
  { label: '30 d', value: 30 }
]

const organizationHint = computed(() => {
  switch (backupStore.backupOrganization) {
    case 'year-month':
      return 'Files land under YYYY/MM/ — clean date tree regardless of source.'
    case 'flat':
      return 'All files dump into the backup root — no subfolders.'
    default:
      return 'DCIM/Camera, Pictures/Screenshots, etc. are preserved, then bucketed by YYYY-MM.'
  }
})

const photoLabel = computed(() =>
  `${galleryStore.photos.length.toLocaleString('en-US')} items · ${formatSize(galleryStore.photoBytes)}`
)

const videoLabel = computed(() =>
  `${galleryStore.videos.length.toLocaleString('en-US')} items · ${formatSize(galleryStore.videoBytes)}`
)

const isVerifying = computed(() => backupStore.verifyRunning || backupStore.verifyQueued)
// Local tap-lock so back-to-back presses don't queue a second verify while we
// wait for the first state-poll cycle to flip isVerifying to true.
const verifyLaunching = ref(false)

async function launchVerify() {
  if (verifyLaunching.value || isVerifying.value) return
  verifyLaunching.value = true
  try {
    await backupStore.verifyAgainstShelf()
  } finally {
    // Release after a short delay; poll typically reflects running=true within 500ms.
    setTimeout(() => { verifyLaunching.value = false }, 1200)
  }
}

const verifyPct = computed(() => {
  const t = backupStore.verifyTotal
  if (!t) return 0
  return Math.min(100, Math.max(0, (backupStore.verifyCurrent / t) * 100))
})

const verifyStatusText = computed(() => {
  if (backupStore.verifyError) return backupStore.verifyError
  if (isVerifying.value) {
    if (backupStore.verifyPhase === 'scanning') return 'Reading phone library…'
    if (backupStore.verifyPhase === 'connecting') return 'Connecting to shelf…'
    if (backupStore.verifyPhase === 'checking') {
      const t = backupStore.verifyTotal
      const c = backupStore.verifyCurrent
      return t ? `Checking ${c} of ${t} folders…` : 'Checking shelf…'
    }
    return 'Verifying…'
  }
  if (backupStore.verifyLastRunAt) {
    const d = backupStore.verifyDropped
    if (d === 0) return 'Everything matches the shelf.'
    return `Dropped ${d.toLocaleString('en-US')} missing entr${d === 1 ? 'y' : 'ies'} — they'll re-upload on the next backup.`
  }
  return ''
})

function addPattern() {
  $q.dialog({
    title: 'Add exclude pattern',
    prompt: {
      model: '',
      type: 'text',
      label: 'Pattern (e.g. *.tmp)',
      isValid: v => typeof v === 'string' && v.trim().length > 0
    },
    cancel: true
  }).onOk(raw => {
    const p = (raw || '').trim()
    if (!p) return
    if (backupStore.excludePatterns.includes(p)) return
    backupStore.excludePatterns = [...backupStore.excludePatterns, p]
  })
}

function removePattern(p) {
  backupStore.excludePatterns = backupStore.excludePatterns.filter(x => x !== p)
}

function resetHistory() {
  const count = backupStore.backedUpCount || 0
  $q.dialog({
    title: 'Reset backup history',
    message: count > 0
      ? `This clears the record of ${count.toLocaleString('en-US')} items. The next backup will re-upload them all (files already on the shelf aren't deleted, but will be overwritten). Continue?`
      : 'Nothing to reset yet — no backup has run.',
    cancel: true,
    ok: { label: 'Reset', color: 'negative' }
  }).onOk(() => {
    backupStore.resetBackup()
    $q.notify({ message: 'Backup history reset', color: 'positive', timeout: 2500 })
  })
}
</script>

<style lang="scss" scoped>
.backup-sheet {
  background: var(--ink-1) !important;
  border-top: 1px solid var(--rule);
  border-radius: var(--r-lg) var(--r-lg) 0 0;
  color: var(--paper);
  width: 100%;
  max-width: 100%;
  max-height: 92vh;
  display: flex;
  flex-direction: column;
}

.backup-sheet__head {
  display: grid;
  grid-template-columns: 44px 1fr 44px;
  align-items: center;
  padding: 6px 8px 12px;
  border-bottom: 1px solid var(--rule-soft);
  flex-shrink: 0;
}

.backup-sheet__close {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  color: var(--paper-mute);
  cursor: pointer;
  border-radius: 50%;
  transition: background var(--dur-fast) var(--ease-archive), color var(--dur-fast) var(--ease-archive);

  &:hover { color: var(--paper); background: var(--ink-2); }
  &:active { background: var(--ink-2); }
}

.backup-sheet__title {
  text-align: center;
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 17px;
  letter-spacing: -0.01em;
  color: var(--paper);
}

.backup-sheet__body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px 32px;
}

.backup-sheet__select {
  :deep(.q-field__native) {
    color: var(--paper) !important;
    font-family: var(--font-body);
    font-size: 13px;
    text-align: right;
  }
}

.backup-sheet__toggle {
  :deep(.q-btn) {
    font-family: var(--font-body);
    font-size: 11px;
    letter-spacing: 0.04em;
    min-height: 30px;
    padding: 0 10px;
    color: var(--paper-mute);
  }
}

.window-row {
  flex-basis: 100%;
  display: flex;
  align-items: center;
  gap: 10px;

  &__select {
    flex: 1;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-sm);
    padding: 2px 8px;
    :deep(.q-field__native) {
      color: var(--paper);
      font-family: var(--font-body);
      font-size: 13px;
    }
  }

  &__sep {
    color: var(--paper-mute);
    font-family: var(--font-body);
    font-size: 12px;
  }
}

// Reuse the settings-card line shape consistent with the rest of the app.
.settings-card {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;

  &__line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    padding: 14px 16px;
    border-top: 1px solid var(--rule-soft);

    &:first-child { border-top: none; }
  }

  &__line-body { flex: 1; min-width: 0; }

  // Stack variant: when the trailing control is too wide to sit inline
  // comfortably (e.g. the 6-button schedule picker), drop it below the label
  // on narrow screens.
  &__line--stack {
    flex-wrap: wrap;
    row-gap: 10px;
  }
  &__line--stack .backup-sheet__toggle {
    flex-basis: 100%;
  }

  &__line-title {
    font-family: var(--font-body);
    font-weight: 500;
    font-size: 14px;
    color: var(--paper);
    line-height: 1.3;
  }

  &__line-sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 3px;
    line-height: 1.35;
  }
}

.backup-sheet__section-label {
  color: var(--paper-dim);
  margin-bottom: 10px;
}

.backup-sheet__excludes {
  margin-top: 24px;
}

.exclude-card {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 14px 16px 16px;

  &__hint {
    font-family: var(--font-body);
    font-size: 11.5px;
    color: var(--paper-mute);
    line-height: 1.5;
    margin-bottom: 12px;

    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 11px;
      padding: 1px 5px;
      background: var(--ink-1);
      border-radius: 3px;
      color: var(--paper);
    }
  }

  &__chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  &__chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 4px 4px 10px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: 999px;
    color: var(--paper);
  }

  &__chip-text { line-height: 1; }

  &__chip-x {
    width: 20px;
    height: 20px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    background: transparent;
    color: var(--paper-dim);
    cursor: pointer;
    border-radius: 50%;

    &:hover, &:focus-visible { color: var(--rust); background: var(--ink-2); }
  }

  &__add {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 12px;
    font-family: var(--font-body);
    font-size: 12px;
    font-weight: 500;
    background: transparent;
    border: 1px dashed var(--rule);
    border-radius: 999px;
    color: var(--paper-mute);
    cursor: pointer;

    &:hover { color: var(--paper); border-color: var(--paper-mute); }
  }
}

.backup-sheet__verify {
  margin-top: 24px;
}

.verify-card {
  background: var(--ink-0);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  padding: 16px;

  &__head {
    display: flex;
    align-items: flex-start;
    gap: 12px;
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
  }

  &__sub {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 4px;
    line-height: 1.4;
  }

  &__btn {
    flex-shrink: 0;
    min-height: 36px;
    padding: 0 12px;
    font-size: 12px;
    letter-spacing: 0.04em;
  }

  &__status {
    display: flex;
    align-items: center;
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid var(--rule-soft);
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
  }

  &__status-text {
    line-height: 1.4;
  }

  &__bar {
    margin-top: 10px;
    height: 2px;
    background: var(--rule-soft);
    border-radius: 1px;
    overflow: hidden;
  }

  &__bar-fill {
    height: 100%;
    background: var(--marigold);
    transition: width 0.3s var(--ease-archive);
  }
}

.backup-sheet__danger {
  margin-top: 24px;
}

.backup-sheet__danger-label {
  color: var(--rust);
  margin-bottom: 10px;
}

.backup-sheet__danger-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: var(--ink-0);
  border: 1px solid rgba(200, 90, 60, 0.35);
  border-radius: var(--r-md);
}

.backup-sheet__danger-body { flex: 1; min-width: 0; }

.backup-sheet__danger-title {
  font-family: var(--font-body);
  font-weight: 500;
  font-size: 14px;
  color: var(--paper);
}

.backup-sheet__danger-sub {
  font-family: var(--font-body);
  font-size: 12px;
  color: var(--paper-mute);
  margin-top: 4px;
  line-height: 1.4;
}
</style>
