<template>
  <q-page class="pulse-page">
    <header class="page-head">
      <div class="page-head__body">
        <div class="caption-sc">Network</div>
        <h1 class="page-head__title">Signal check</h1>
        <div class="page-head__sub">Latency, shelves, and diagnostics.</div>
      </div>
    </header>

    <!-- Primary shelf card -->
    <section class="pulse-page__section">
      <div class="caption-sc pulse-page__label">Current shelf</div>
      <div class="settings-card">
        <div class="settings-card__head">
          <div class="settings-card__title">{{ smbStore.activeShare?.name || 'No shelf' }}</div>
          <NetworkStatus :status="smbStore.connectionStatus" />
        </div>
        <div class="settings-card__rows">
          <div class="settings-card__row"><span class="settings-card__key">Host</span><span class="settings-card__val tabular">{{ activeHost }}</span></div>
          <div class="settings-card__row">
            <span class="settings-card__key">Ping</span>
            <span class="settings-card__val tabular" :class="pingTime > 0 ? 'moss' : 'rust'">
              <q-icon
                :name="pingTime > 0 ? 'mdi-check-circle-outline' : 'mdi-close-circle-outline'"
                size="var(--icon-xs)"
                class="q-mr-xs"
              />
              {{ pingTime > 0 ? `${pingTime} ms` : '—' }}
            </span>
          </div>
          <div class="settings-card__row"><span class="settings-card__key">Protocol</span><span class="settings-card__val">SMB 2.x / 3.x</span></div>
          <div class="settings-card__row">
            <span class="settings-card__key">Last seen</span>
            <span class="settings-card__val tabular">
              {{ smbStore.lastConnectedAt ? new Date(smbStore.lastConnectedAt).toLocaleTimeString() : 'never' }}
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- Sparkline -->
    <section class="pulse-page__section">
      <div class="pulse-page__spark-head">
        <div class="caption-sc">Latency · last {{ samples.length }} samples</div>
        <div v-if="samples.length" class="caption-sc tabular marigold">
          ~{{ avgPing }} ms
        </div>
      </div>
      <div class="pulse-page__spark">
        <svg v-if="samples.length > 1" viewBox="0 0 300 60" preserveAspectRatio="none" class="pulse-page__spark-svg">
          <polyline
            :points="sparkPoints"
            fill="none"
            stroke="var(--marigold)"
            stroke-width="1.4"
            stroke-linejoin="round"
            stroke-linecap="round"
            vector-effect="non-scaling-stroke"
          />
        </svg>
        <div v-else class="pulse-page__spark-empty">Run ping to begin recording latency.</div>
      </div>
    </section>

    <!-- Shelves -->
    <section class="pulse-page__section">
      <div class="caption-sc pulse-page__label">All shelves</div>
      <div
        v-for="share in settingsStore.shares"
        :key="share.id"
        class="settings-card"
      >
        <div class="settings-card__head">
          <div class="settings-card__title">{{ share.name }}</div>
        </div>
        <div v-if="shareInfo[share.id]" class="pulse-page__space">
          <q-linear-progress
            :value="shareInfo[share.id].usedRatio"
            size="1px"
            color="primary"
            track-color="dark"
          />
          <div class="pulse-page__space-text tabular">
            {{ formatSize(shareInfo[share.id].usedSpace) }} of {{ formatSize(shareInfo[share.id].totalSpace) }} used
          </div>
        </div>
      </div>
    </section>

    <!-- Actions -->
    <section class="pulse-page__section">
      <div class="pulse-page__actions">
        <button class="btn-ghost" :disabled="pinging" @click="runPing">
          <q-icon name="mdi-radar" size="16px" />
          {{ pinging ? 'pinging…' : 'ping' }}
        </button>
        <button class="btn-ghost" :disabled="reconnecting" @click="reconnect">
          <q-icon name="mdi-refresh" size="16px" />
          {{ reconnecting ? 'linking…' : 'reconnect' }}
        </button>
      </div>
    </section>

    <!-- Log -->
    <section class="pulse-page__section">
      <div class="caption-sc pulse-page__label">Log</div>
      <div class="pulse-page__log">
        <div v-if="logs.length === 0" class="pulse-page__log-empty">Nothing logged.</div>
        <div
          v-for="(log, i) in logs"
          :key="i"
          class="pulse-page__log-entry"
          :class="{ 'rust': log.level === 'error' }"
        >
          <span class="tabular paper-dim">{{ log.time }}</span>
          <span>{{ log.message }}</span>
        </div>
      </div>
    </section>
  </q-page>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useSmbStore } from '../stores/smb'
import { useSettingsStore } from '../stores/settings'
import { SmbPlugin } from '../plugins/SmbPlugin'
import { formatSize } from '../utils/helpers'
import NetworkStatus from '../components/NetworkStatus.vue'

const smbStore = useSmbStore()
const settingsStore = useSettingsStore()

const activeHost = computed(() => smbStore.activeShare?.host || '—')
const pingTime = ref(-1)
const pinging = ref(false)
const reconnecting = ref(false)
const shareInfo = reactive({})
const logs = ref([])

// Rolling sparkline of the last 60 ping samples
const MAX_SAMPLES = 60
const samples = ref([])

const avgPing = computed(() => {
  if (!samples.value.length) return 0
  const sum = samples.value.reduce((a, b) => a + b, 0)
  return Math.round(sum / samples.value.length)
})

const sparkPoints = computed(() => {
  if (samples.value.length < 2) return ''
  const min = Math.min(...samples.value)
  const max = Math.max(...samples.value)
  const range = Math.max(1, max - min)
  return samples.value.map((s, i) => {
    const x = (i / (samples.value.length - 1)) * 300
    const y = 58 - ((s - min) / range) * 52
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
})

onMounted(async () => {
  await runPing()
  await loadShareInfo()
})

async function runPing() {
  pinging.value = true
  try {
    const result = await SmbPlugin.ping({ host: activeHost.value })
    pingTime.value = result.responseTime
    if (result.reachable) {
      samples.value.push(result.responseTime)
      if (samples.value.length > MAX_SAMPLES) samples.value.shift()
      addLog('info', `${activeHost.value} · ${result.responseTime} ms`)
    } else {
      addLog('error', `${activeHost.value} did not respond`)
    }
  } catch (e) {
    pingTime.value = -1
    addLog('error', `Ping failed · ${e.message}`)
  }
  pinging.value = false
}

async function loadShareInfo() {
  for (const share of settingsStore.shares) {
    try {
      const creds = await settingsStore.getShareCredentials(share.id)
      const info = await SmbPlugin.getShareInfo({
        host: share.host,
        share: share.shareName,
        username: creds.username,
        password: creds.password
      })
      if (info.totalSpace > 0) {
        shareInfo[share.id] = {
          ...info,
          usedSpace: (info.totalSpace || 0) - (info.freeSpace || 0),
          usedRatio: info.totalSpace ? ((info.totalSpace - info.freeSpace) / info.totalSpace) : 0
        }
      }
    } catch {
      addLog('error', `Could not read ${share.shareName}`)
    }
  }
}

async function reconnect() {
  reconnecting.value = true
  const result = await smbStore.checkConnection()
  if (result.reachable) {
    addLog('info', 'Reconnected')
    smbStore.loadDirectory(smbStore.currentPath)
    await loadShareInfo()
  } else {
    addLog('error', 'Reconnect failed')
  }
  reconnecting.value = false
}

function addLog(level, message) {
  const time = new Date().toLocaleTimeString('en-US', { hour12: false })
  logs.value.unshift({ level, message, time })
  if (logs.value.length > 50) logs.value.pop()
}
</script>

<style lang="scss" scoped>
.pulse-page {
  padding-bottom: 96px;

  &__section {
    padding: 24px 20px 4px;
  }

  &__label {
    color: var(--paper-dim);
    margin-bottom: 10px;
  }

  &__space { padding: 0 18px 16px; }

  &__space-text {
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    margin-top: 8px;
    letter-spacing: 0.02em;
  }

  &__spark-head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-bottom: 10px;
  }

  &__spark {
    padding: 20px 18px;
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-md);
    min-height: 80px;
  }

  &__spark-svg {
    width: 100%;
    height: 60px;
    display: block;
  }

  &__spark-empty {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-dim);
    text-align: center;
    padding: 16px 0;
  }

  &__actions {
    display: flex;
    gap: 10px;

    button { flex: 1; }
  }

  &__log {
    background: var(--ink-1);
    border: 1px solid var(--rule);
    border-radius: var(--r-md);
    max-height: 260px;
    overflow-y: auto;
    padding: 12px 16px;
  }

  &__log-empty {
    font-family: var(--font-body);
    font-size: 13px;
    color: var(--paper-dim);
    padding: 8px 0;
  }

  &__log-entry {
    display: grid;
    grid-template-columns: 80px 1fr;
    gap: 14px;
    padding: 6px 0;
    font-family: var(--font-body);
    font-size: 12px;
    color: var(--paper-mute);
    border-bottom: 1px solid var(--rule-soft);

    &:last-child { border-bottom: none; }
  }
}

.settings-card {
  background: var(--ink-1);
  border: 1px solid var(--rule);
  border-radius: var(--r-md);
  overflow: hidden;
  margin-bottom: 12px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 18px 6px;
    gap: 16px;
  }

  &__title {
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 18px;
    color: var(--paper);
    letter-spacing: -0.01em;
  }

  &__rows { padding: 0 18px 16px; }

  &__row {
    display: flex;
    justify-content: space-between;
    gap: 16px;
    padding: 6px 0;
    font-family: var(--font-body);
    font-size: 13px;
  }

  &__key {
    color: var(--paper-dim);
    font-size: 11px;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    padding-top: 1px;
  }

  &__val { color: var(--paper); text-align: right; }
}
</style>
